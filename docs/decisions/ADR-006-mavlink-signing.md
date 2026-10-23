# ADR-006 — MAVLink 2 signing: sign and verify below mavlink-kotlin

**Status:** Proposed (stage S0a). Becomes Accepted when S1 lands the pure signing core against the S1v vectors.
**Date:** 2026-10-23 · **Pass:** 34 (S0a) · **PRD:** `docs/Signing/PRD-Signing.md` ("Scope", "Architecture")

## Context

VisionNav moves to signed MAVLink 2 with one key per drone (PRD scheme B). The GCS must sign every frame it sends to a
keyed drone and verify every signed frame it receives, with a 48-bit timestamp in 10 µs ticks since
2015-01-01 00:00:00 UTC. Today's value is about 3.7 × 10¹³ ticks, so it needs about 45 bits.

The PRD assumed, from the published API docs, that mavlink-kotlin's own signing can't be used because its timestamp is
a `UInt`. This ADR checks that against the version we ship (1.2.15) before we build our own layer.

**Evidence used.** `javap -p -c` on the jars in the Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/com.divpundir.mavlink/{connection-core,api,serialization}-jvm/1.2.15`), the same
method `BitExactCommandLong.kt` uses for its NaN finding. Each claim below is then checked against real bytes in
`core/mavlink/src/jvmTest/kotlin/com/kft/gcs/core/mavlink/SigningLibraryProbeTest.kt`, using signed HEARTBEAT frames
made by **pymavlink 2.4.50** (an independent encoder) with key = 32 × 0x42, link id 7, and timestamps 0x2234_5678_9ABC
(real, late 2026) and 0x5678_9ABC (fits in 32 bits).

## Findings

### 1. `sendSignedV2` can only write a 32-bit timestamp

- Signature: `MavConnection.sendSignedV2-Ndr1L8U(byte, byte, T, byte, int, byte[])`. The `-Ndr1L8U` suffix is Kotlin's
  name mangling for inline-class parameters; the `int` is `timestamp: UInt` (`MavFrameV2.getSignatureTimestamp-pVg5ArA()`
  returns `int` too, and `MavRawFrame` has `private final int signatureTimestamp`).
- `MavRawFrame.Companion.createSignedV2` writes the field as
  `i2l; ldc2_w 4294967295; land; bipush 6; invokestatic encodeInteger(J,I)`, that is
  `encodeInteger(ts.toLong() and 0xFFFFFFFF, 6)`. The top two bytes on the wire are always `00 00`.
- Largest value: 4 294 967 295 ticks = 42 949.7 s, about **11.9 hours after 2015-01-01**.
- Probe `sendSignedV2CanOnlyWriteA32BitTimestamp`: passing `0x2234_5678_9ABC.toUInt()` produces pymavlink's
  *low-timestamp* frame byte for byte (`… 07 bc9a78560000 d9cc6aa23225`), not the real one
  (`… 07 bc9a78563422 2b31429d76c5`).

Consequence: ArduPilot's signature check (`mavlink_signature_check` in the generated MAVLink C headers, used by
`GCS_MAVLink`) rejects a frame whose timestamp is older than the last one seen for its stream, and a new stream whose
timestamp is more than 60 s behind the FC's own clock. Every frame sent this way would be about 11.8 years old, so the FC
would drop all of them. S0b confirms this in SITL.

### 2. Receiving: the frame is decoded, the timestamp truncated, the signature never checked

`BufferedMavConnection.nextRawFrame` looks at `incompat_flags` and, if it **equals** 1 (`iload 4; iconst_1; if_icmpne`),
reads 13 more bytes for the trailer. `MavRawFrame.fromV2Bytes` then reads the timestamp with
`safeDecodeUnsignedInteger(decoder, 6)` (a `Long`) followed by `l2i`, so **all 6 bytes are read but only the low 32 bits
are kept**. The original byte array is stored as `rawBytes`, but `MavFrameV2Impl` keeps its `MavRawFrame` in a
`private final` field, so the frame `next()` returns has no way to reach those bytes.

`nextFrame` → `getMessageCompanionOrNull` only resolves the message id and calls `validateCrc(crcExtra)`. It never calls
`validateSignature`.

| Question | Answer | Probe |
|---|---|---|
| Keeps all 6 timestamp bytes? | No: low 32 bits (`signatureTimestamp == 0x5678_9ABC` for 0x2234_5678_9ABC) | `aSignedFrameIsDecodedButItsTimestampIsTruncated` |
| Checks the signature? | No: a frame with a forged signature comes out like a genuine one | `forgedSignaturesPassAndBadCrcsAreDropped` |
| Drops or passes signed frames? | Passes them: the HEARTBEAT decodes, `isSigned`, link id 7, the 6 signature bytes | `aSignedFrameIsDecodedButItsTimestampIsTruncated` |
| Validates CRC with the incompat flag set? | Yes, correctly: `generateChecksum` runs X.25 over `rawBytes[1 until 10 + len]`, which includes the flag byte, then the CRC extra. A bad CRC is dropped silently and the reader goes on | both probes above |

Side finding: the flag test is `== 1`, not `and 1`. A frame with any other incompat bit set is read as unsigned and its
trailer is left in the stream (the reader then skips it byte by byte). No incompat bit other than 0x01 is defined today,
but our own scanner should test the bit.

### 3. `validateSignature` hashes the raw bytes but the truncated timestamp

`MavRawFrame.validateSignature(key)` calls `generateSignature(rawBytes, signatureLinkId, signatureTimestamp, key)`.
`generateSignature` hashes `key[0..32) ‖ rawBytes[0 until 10 + len + 2) ‖ link_id ‖ encodeInteger(ts and 0xFFFFFFFF, 6)`
with `okio.ByteString.sha256()` and keeps the first 6 bytes. So the header, payload and CRC come from the **original
wire bytes** (good), but the timestamp is the **truncated** value, zero-extended back to 6 bytes.

Probe `validateSignatureFailsForARealTimestampEvenWithTheRightKey`: with the right key, the real-timestamp frame is
rejected and the 32-bit one accepted; a wrong key is rejected. The hash layout is right; only the timestamp is wrong.

### 4. Each connection has its own sequence counter and its own lock

`BufferedMavConnection` has `private volatile int sequence` advanced with `AtomicIntegerFieldUpdater.getAndIncrement`,
**before** taking `private final ReentrantLock writeLock`, which only guards `sink.write(rawBytes); sink.flush()`.

- Two writers on the same sink (say a new `FrameSink` and the library's `send*`) each count from 0 and each take their
  own lock. Sequence numbers repeat, and since okio's `BufferedSink` is not thread-safe, bytes of two frames could mix.
  Probe `twoWritersOnOneSinkRepeatSequenceNumbers` shows the sequences `0, 1, 0`.
- Even one connection with two calling threads can put frames on the wire out of sequence order, because the number is
  taken outside the lock. For signing this matters more: the timestamp must rise with every frame, so sequence, timestamp
  and write must happen under one lock.

## Decision

**Sign and verify below the library, at the byte level** (the PRD's architecture stands):

- **Out:** one `FrameSink` per link is the only thing that writes to the transport sink. It serialises the frame itself
  (its own sequence counter), signs with the 48-bit timestamp when the target vehicle is bound to a key, and writes,
  all under one mutex. `MavTxGateway` stops calling `connection.sendUnsignedV2` (S2/S3). `BufferedMavConnection.send*`
  is not called anywhere once `FrameSink` exists, because of finding 4.
- **In:** a `SigningSource` (okio `Source` filter) sits between the transport and `BufferedMavConnection`. It verifies
  signed frames with the full 6-byte timestamp and applies replay rules, and drops anything that fails before the library
  sees it. Frames that pass can go on unchanged, because finding 2 shows the library decodes signed frames correctly
  (CRC included) and only ignores the trailer.
- The library keeps doing what it does well: message definitions, payload encode/decode, CRC extras.
- Nothing above `core:mavlink` reads `MavFrameV2.isSigned`, `signatureTimestamp` or `validateSignature`; they are wrong
  for real timestamps.

## Alternatives considered

| Option | Why not |
|---|---|
| Use `sendSignedV2` / `validateSignature` as they are | Finding 1 and 3: the FC rejects every frame, and we can't verify any real frame |
| Keep the library's signing but subtract an epoch offset | The timestamp's meaning is fixed by the MAVLink spec and checked by the FC against its own clock; there is no offset to agree on |
| Reach `MavFrameV2Impl.rawFrame` by reflection to verify after decoding | JVM-only, breaks on any library change, and the forged frame would already be inside the library. Verifying before the library is both simpler and safer |
| Patch or fork mavlink-kotlin (widen the field to `ULong`) | A public API change upstream; until it ships we'd maintain a fork. Worth proposing upstream (see below), not worth blocking on |
| Upgrade to a newer mavlink-kotlin | Not checked in this stage: version bumps are their own pass (CLAUDE.md §6). If a later release widens the timestamp, `SigningLibraryProbeTest` fails and this ADR is revisited; our own layer still works |

## Consequences

- `core:mavlink` owns MAVLink 2 framing for writes (header, CRC with crc_extra, signature trailer). `Mav2Signing` (S1)
  is pure and tested against pymavlink vectors (S1v), so bit errors show up in tests, not in the air.
- `BitExactCommandLong` keeps working: `FrameSink` takes the payload from `serializeV2()` like the library does.
- `SigningLibraryProbeTest` stays as a tripwire on the library's behaviour. If an upgrade changes any finding, it fails
  and points here.
- Possible upstream issue for mavlink-kotlin: `signatureTimestamp` should be a `ULong` (48 bits), and the flag test
  should be `and 0x01`.
