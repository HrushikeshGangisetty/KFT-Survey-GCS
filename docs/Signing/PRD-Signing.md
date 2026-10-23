# VisionNav MAVLink v2 Signing — Scope & PRD

Oct 23, 2026 · @Hrushikesh Gangisetty

## Summary

VisionNav will move from unsigned to **signed MAVLink v2 with one key per drone (scheme B)**, so that only key holders can command the aircraft. Other KFT projects stay unsigned. DGCA compliance is out of scope for this work.

Four components change: the VisionNav FC firmware fork, the Pi 5 companion (which routes all GCS traffic and also sends its own odometry), SurveyGCS (Kotlin Multiplatform, desktop first, Android second), and a new provisioning tool.

- **Part A — Scope** says exactly what changes and where, down to the SurveyGCS files.
- **Part B — PRD** holds the requirements, architecture and key management, then a staged build plan. Each stage comes with a ready-to-paste prompt and a recommended model (Opus, Sonnet or Codex), so two agents can work in parallel.

What signing buys: forged, injected or replayed commands are rejected. What it does not buy: telemetry stays readable, and jamming is unaffected.

## Part A — Scope: system context

All GCS traffic reaches the FC through the Pi 5 on one UART, so every endpoint on that path must sign. Exempting the Pi's port would open a back door for anything the Pi forwards.

&#91;embedded content: VisionNav link topology with signing\]

The Pi forwards GCS frames byte-for-byte and needs no key for that. It does need K\_drone to sign its own ODOMETRY or VISION\_POSITION\_ESTIMATE. Without that, the FC drops the position source and the drone loses navigation in a GNSS-denied environment.

**Threat model.** The main threat is an adversary who sends commands to the drone (defence use). The table below sets what is in and out of scope.

| Threat | In scope | How it is handled |
| --- | --- | --- |
| Injected or spoofed commands over the GCS link | Yes | Per-packet signature with K\_drone |
| Replay of captured commands | Yes | Monotonic 48-bit timestamp per (link\_id, sysid, compid) |
| Tampering by a relay in the path | Yes | Signature covers header, payload and CRC |
| Captured GCS laptop or tablet | Partly | Keys are encrypted at rest behind an operator PIN; a lost device means rekeying its drones |
| Captured drone, SD card pulled | Partly | Pi key is wrapped with the Pi 5 OTP device key |
| Eavesdropping on telemetry | No | Signing does not encrypt |
| RF jamming | No | Out of scope |
| Physical USB access to the FC | Yes | USB bypass restricted in the fork (see components) |
| Per-laptop revocation without rekeying (scheme C) | No | Deferred; key store is built to allow it later |

## Scope: what changes in each component

Four codebases change; the GCS is the largest piece because signing has to sit below mavlink-kotlin rather than use its built-in signing.

**Blocking finding: do not use mavlink-kotlin's own signing.** In the published API, `MavConnection.sendSignedV2` takes the timestamp as a `UInt`, and `MavFrameV2.signatureTimestamp` / `MavRawFrame.signatureTimestamp` are `UInt` too ([MavConnection docs](https://divyanshupundir.github.io/mavlink-kotlin/connections/connection-core/com.divpundir.mavlink.connection/-mav-connection/index.html), [MavFrameV2 docs](https://divyanshupundir.github.io/mavlink-kotlin/api/com.divpundir.mavlink.api/-mav-frame-v2/index.html)). The MAVLink timestamp is 48 bits. Today's value is about 3.7 × 10¹³ ticks, while a UInt tops out at 4.3 × 10⁹ (about 12 hours after 2015-01-01). A truncated timestamp would make the FC reject every GCS frame as too old, and the GCS could not track replay on incoming frames. Stage 0 confirms this against version 1.2.15. The plan assumes it holds and signs at the byte level instead.

| Component | Where | What changes | Size |
| --- | --- | --- | --- |
| SurveyGCS | `KFT_Survey_GCS` (KMP, desktop + Android) | Own MAVLink v2 signer and verifier under mavlink-kotlin; all writes through one frame writer; per-vehicle key binding in the gateway; signed heartbeats; new `core:keys` vault module; Signing keys screen; status badges on Links and Fly | Large |
| Pi companion | `GNSS-Denied/platform` | Add a MAVLink router (the bridge owns `/dev/ttyAMA0` today, so GCS traffic has no path yet); bridge signs its ODOMETRY, HEARTBEAT and KFT handshake with pymavlink signing; key file wrapped with the Pi 5 OTP key; router verified to pass signed frames untouched | Medium |
| FC firmware | VisionNav ArduPilot fork (4.6.x) | Signing switched on per drone; SETUP\_SIGNING and disable refused while armed; bad-signature counter sent to the GCS; MAVLink2 on every telemetry port; Clause 7 login kept as is | Small |
| Provisioning tool | New, `GNSS-Denied/tools/signing` (Python) | Reads the STM32 UID over USB, derives K\_drone, sends SETUP\_SIGNING over USB only, exports encrypted GCS bundles and a Pi bundle, writes a key-free log | Medium |

**What stays the same**

- The KFT Clause 7 login in SurveyGCS (`KftLogin.kt`) and in the Pi bridge (`kft_auth.py`). It still runs, now inside signed frames.
- The TX allowlist (`TxPolicy`) and the rule that only `MavTxGateway` writes. Signing is added inside the gateway's single write path.
- Unsigned vehicles. Any drone without a key keeps working unsigned, so other KFT drones connect as before.
- The companion WebSocket (`core:companion`, Ktor) to the Pi. It is read-only diagnostics and is out of scope; see risks.

**Existing facts the design relies on**

- The GCS sends as system 255, component 190 (`MavTxGateway.GCS_SYSTEM_ID`, `GCS_COMPONENT_ID`).
- Every send already names its target vehicle (`MavTxGateway.forVehicle(systemId)`), which is exactly where a per-drone key is chosen.
- The bridge sends as component 197 at 921600 baud on `/dev/ttyAMA0`, using pymavlink, which supports 48-bit signing natively.
- The KFT login key in SurveyGCS is compiled in from `KFT_APP_SECRET` at build time. Signing keys must not follow that pattern; they come from the vault.

## Scope: SurveyGCS edits, file by file

Signing lands inside `core:mavlink` at the byte level, plus one new module for keys; features only gain status badges and a keys screen. Paths are relative to `KFT_Survey_GCS`, read at commit `230cceb` (P33). All rules in `CLAUDE.md` still apply: only the gateway writes, no `java.*` in `commonMain`, tests with every pass.

**New files**

| File | Purpose |
| --- | --- |
| `core/mavlink/src/commonMain/.../mavlink/signing/Mav2Signing.kt` | Pure functions: sign an unsigned v2 frame (set incompat bit 0x01, recompute CRC with crc\_extra, append link\_id + 48-bit timestamp + 6-byte signature), verify a signed frame, read its trailer |
| `.../mavlink/signing/Sha256.kt` + `jvmCommonMain/.../Sha256.jvmCommon.kt` | `expect fun sha256(bytes)` backed by `MessageDigest`, mirroring how `core:vehicle` already does `hmacSha256` |
| `.../mavlink/signing/SigningClock.kt` | 48-bit timestamp in 10 µs ticks since 2015-01-01 = max(wall clock, persisted + 1, highest valid incoming + 1), +1 per frame sent |
| `.../mavlink/signing/ReplayGuard.kt` | Last timestamp per (link\_id, sysid, compid); rejects older; first-seen stream accepted only within 6,000,000 ticks (60 s) |
| `.../mavlink/signing/SigningKeyring.kt` | Interface the byte layer asks: candidate keys, key for a bound drone, link\_id to stamp, timestamp persistence. Implemented in `core:keys`, faked in tests |
| `.../mavlink/signing/SigningSource.kt` | okio `Source` filter between the transport and `BufferedMavConnection`: finds v2 frames, verifies signed ones, binds unknown sysids by trial-verifying their HEARTBEAT, applies replay rules, drops spoofed or bad frames before the library sees them, emits per-sysid status |
| `.../mavlink/FrameSink.kt` | The single writer: serialises frames itself (own sequence counter), signs when the target vehicle is bound, writes to the transport sink under a mutex |
| `.../mavlink/signing/SigningStatus.kt` | `UNSIGNED`, `SIGNED(droneId, label)`, `NO_KEY`, `LOCKED`, `BAD_SIGNATURE(count)` |
| `core/keys/` (new module, `kft.kmp.library`) | `Vault` (file format, Argon2id + AES-256-GCM), `VaultKeyring : SigningKeyring`, `TimestampFile`, `BundleImport`, `expect` crypto in `jvmCommonMain`, Android Keystore wrap in `androidMain` |
| `feature/settings/.../SigningKeysScreen.kt`, `SigningKeysViewModel.kt`, `SigningKeysUiState.kt` | Unlock with PIN, import bundle, list drones (label, drone\_id, key version, last seen), remove key, lock now |
| `core/mavlink/src/commonTest/.../signing/*Test.kt` | Vectors generated by pymavlink (Stage 1), replay and timestamp rules, scanner resync, binding |
| `tools/signing/make_vectors.py` | Generates the pymavlink reference frames the Kotlin tests compare against |

**Changed files**

| File | Change |
| --- | --- |
| `core/mavlink/.../MavTransport.kt` | `TransportMavConnection.open()` wraps `streams.source` in `SigningSource` and hands `streams.sink` to a `FrameSink`; `openTransportConnection` returns both the connection (for reading) and the sink (for writing) |
| `core/mavlink/.../MavTxGateway.kt` | `attach` takes a `FrameSink`. `write()` stops calling `connection.sendUnsignedV2`; it asks the keyring whether the target vehicle is bound and signs if so. New refusals: vehicle signs but GCS has no key (`NO_KEY`), keystore locked (`LOCKED`). `BitExactCommandLong` path kept |
| `core/mavlink/.../MavTxGateway.kt` → `sendHeartbeat` | Sends one heartbeat per distinct bound key on the link, plus one unsigned if any vehicle on the link is unsigned or none is heard yet |
| `core/mavlink/.../ConnectionManager.kt` | Builds the per-link `SigningSource` state, merges signing status into `VehicleInfo`, resets binding on reconnect |
| `core/mavlink/.../ConnectionManager.kt` → `VehicleInfo` | New field `signing: SigningStatus = UNSIGNED` |
| `core/mavlink/.../TxPolicy.kt` | SETUP\_SIGNING (id 256) classified `NEVER` with its own test, so the GCS can never send a key over the air |
| `core/mavlink/.../Links.kt`, `di/MavlinkModule.kt` | Pass the `SigningKeyring` from Koin to every `ConnectionManager` |
| `core/mavlink/build.gradle.kts` | No new dependency (SHA-256 from the JDK) |
| `core/mavlink/src/commonTest/.../FakeMavConnection.kt`, `MavTxGatewayTest.kt`, `ConnectionManagerTest.kt` | Fakes move to `FakeFrameSink`; existing tests keep passing; new rows for signed, `NO_KEY`, `LOCKED`, heartbeat fan-out |
| `core/mavlink/.../Tlog.kt` | No change needed: it re-serialises decoded frames unsigned, so no trailer or key reaches the log. Add a test that proves it |
| `settings.gradle.kts`, `gradle/libs.versions.toml` | Add `core:keys`; add `bcprov-jdk18on` (Argon2id only) in its own version-bump pass, per `CLAUDE.md` §6 |
| `app/shared/.../di/*`, `jvmCommonMain/.../FileTextStore.kt` usage | Bind `Vault` to `%APPDATA%\KFT-GCS\signing.vnks` on desktop and the app's private files dir on Android; bind `VaultKeyring` as `SigningKeyring` |
| `app/shared/.../App.kt` | Unlock prompt at start when a vault exists; auto-lock after idle |
| `feature/connections/.../ConnectionsUiState.kt`, `ConnectionsScreen.kt` | Signing badge on each vehicle row |
| `feature/fly/.../FlyUiState.kt`, `FlyScreen.kt`, `FleetScreen.kt` | Signing badge in the HUD and fleet list; bad-signature counter from the FC |
| `docs/decisions/ADR-006-mavlink-signing.md` | New ADR recording why signing sits below the library, scheme B, and the vault format |
| `docs/spec/02_pod_interface_contract.md` | Note that SETUP\_SIGNING is in the never-send list |
| `docs/learning-log.md` | One pass summary per stage, as `CLAUDE.md` §8 requires |

## Part B — PRD: goals and requirements

The release is done when an unsigned or replayed command sent anywhere on the VisionNav link is dropped by the FC, while SurveyGCS and the Pi keep flying the drone normally.

**Goals**

1. Every frame the FC accepts on a non-USB port carries a valid signature made with that drone's key.
2. SurveyGCS signs and verifies on desktop and Android with no change to how operators fly.
3. The Pi keeps feeding odometry to the EKF at the current rate and latency, now signed.
4. Keys never travel over the air and never sit unencrypted on disk.
5. The key store is modular, so scheme C (per-pair keys) later changes only providers and firmware.

**Non-goals**

- Encrypting telemetry or video.
- DGCA or Clause 7 documentation.
- Per-laptop revocation without rekeying (scheme C).
- Signing for other KFT products, the Android kftgcs app, or the Windows tools.

**Functional requirements**

| ID | Requirement | Owner |
| --- | --- | --- |
| FR-1 | FC rejects unsigned frames on every non-USB port, except RADIO\_STATUS | Firmware |
| FR-2 | FC rejects frames older than the last timestamp seen for that (link\_id, sysid, compid) | Firmware (stock) |
| FR-3 | USB can change or clear the key only while disarmed | Firmware |
| FR-4 | FC counts signature failures and exposes the count as telemetry | Firmware |
| FR-5 | SurveyGCS signs every outgoing frame for a vehicle that has a key | SurveyGCS |
| FR-6 | SurveyGCS validates incoming signed frames and tracks replay per stream | SurveyGCS |
| FR-7 | SurveyGCS identifies an unknown drone by trying stored keys against its signed heartbeat | SurveyGCS |
| FR-8 | SurveyGCS shows per-vehicle signing state: Signed, No key, Bad signature, Unsigned link | SurveyGCS |
| FR-9 | Key store is encrypted at rest and unlocked by an operator PIN; keys are zeroised on lock | SurveyGCS |
| FR-10 | Outgoing timestamp = max(wall clock, last incoming + 1, persisted + 1) | SurveyGCS, Pi |
| FR-11 | Pi forwards GCS frames byte-for-byte and signs only its own frames | Pi |
| FR-12 | Pi key is wrapped with the Pi 5 OTP device key | Pi |
| FR-13 | Provisioning tool derives K\_drone from MASTER and the STM32 UID, writes it over USB, and exports per-device bundles | Tooling |
| FR-14 | No SETUP\_SIGNING frame ever leaves a USB link; tlog writer redacts it | SurveyGCS, Tooling |

**Non-functional requirements**

| ID | Requirement | Target |
| --- | --- | --- |
| NFR-1 | Added latency on the Pi to FC odometry path | Under 1 ms at 921600 baud |
| NFR-2 | Signing cost per frame on the GCS | Under 50 µs |
| NFR-3 | Reconnect after an FC reboot | No manual step |
| NFR-4 | Test coverage of the signing core | Every requirement above has a unit or SITL test |
| NFR-5 | Unsigned vehicles keep working | Other drones connect unchanged |

## Architecture

Signing is a byte-level layer between `MavTxGateway` and the transport on the way out, and between the transport and mavlink-kotlin on the way in. Nothing above the gateway knows about keys.

&#91;embedded content: SurveyGCS signing layers\]

The gateway already decides which vehicle every frame is for, so it is the one place that picks a key. The library still decodes frames; it simply never sees a frame that failed verification.

**Outbound rules (per frame)**

1. `TxPolicy` runs first, unchanged. A refused message never reaches signing.
2. The gateway looks up the target vehicle's `SigningStatus`. `SIGNED` → sign with that drone's key. `UNSIGNED` → send unsigned, as today. `NO_KEY` or `LOCKED` → refuse with a reason and log it.
3. `FrameSink` serialises the frame with its own sequence counter, then signs: set incompat bit 0x01, recompute the CRC over the new header with the message's crc\_extra, append `link_id`, the 48-bit timestamp (little-endian) and the first 6 bytes of SHA-256(key ‖ header ‖ payload ‖ CRC ‖ link\_id ‖ timestamp).
4. The GCS heartbeat goes out once per distinct bound key on the link, plus once unsigned if any vehicle there is unsigned or none is heard yet.

**Inbound rules (per frame)**

1. `SigningSource` finds frame boundaries in the byte stream (STX 0xFD for v2, 0xFE for v1). If it loses sync it passes bytes through untouched and lets the library resync.
2. Signed frame from a bound sysid: verify with that key, then the replay check. Failures are dropped and counted.
3. Signed HEARTBEAT from an unbound sysid: try each unlocked key. The first that verifies binds `(link, sysid) → drone_id`. None verifies → status `NO_KEY` (or `LOCKED` when the vault is locked) and the frame is dropped.
4. Unsigned or v1 frame claiming a bound sysid: dropped and counted as spoofed. RADIO\_STATUS (id 109) is the one exception, because radios inject it unsigned.
5. Unsigned frame from an unbound sysid: passed through; that vehicle is `UNSIGNED`.

**Timestamps**

- Units: 10 µs since 2015-01-01 00:00:00 UTC (Unix 1420070400), 48 bits, little-endian on the wire.
- At link open: `ts = max(wallClockTicks, persisted + 1)`. Each frame sent: `ts += 1`. Each valid incoming frame with a higher timestamp: `ts = incoming + 1` (MAVLink spec rule).
- Persist the highest timestamp every 10 s and on disconnect, per drone, in `signing_ts.json`.

**Identity on the wire**

- GCS stays system 255, component 190, so ArduPilot's SYSID\_MYGCS and GCS failsafe keep working.
- `link_id` = one random byte (1–254) chosen per installation at first run and stored in the vault. Two laptops on one drone therefore form two different replay streams.
- Binding is per link: the same drone heard on two links is bound on each, using the same key.

**Concurrency**

- `FrameSink` holds a mutex around serialise + sign + write, so the sequence and timestamp never interleave between senders.
- `SigningSource` runs on the reader thread only; it publishes status through a `StateFlow` that `ConnectionManager` merges into `VehicleInfo` on its existing single collector.

## Key management

Each drone gets one 32-byte key derived from a master secret and the FC's STM32 UID. The master never leaves the provisioning machine, and field devices hold only the keys of the drones assigned to them.

**Derivation**

```latex
K_{drone} = \mathrm{HMAC\text{-}SHA256}(MASTER,\ \text{"VNAV-SIGN-v1"} \,\Vert\, uid_{96})
```

- `uid_96` is the 12-byte STM32H743 unique ID, read over USB at provisioning.
- The version label (`v1`) lets a fleet-wide rotation happen by bumping it.
- `drone_id` everywhere in software = the UID as 24 hex characters. SYSID\_THISMAV is a routing label, not an identity.

**Where each key lives**

| Holder | What it holds | Storage at rest | Unlocked by |
| --- | --- | --- | --- |
| Provisioning machine | MASTER | Passphrase-encrypted file (Argon2id + AES-256-GCM) on an offline laptop or encrypted USB stick | Passphrase per session |
| FC | Its own K\_drone | ArduPilot signing storage area in flash | Always loaded |
| Pi 5 | Its drone's K\_drone | File wrapped with AES-GCM under a key derived from the Pi 5 OTP device key | Boot service, root only |
| SurveyGCS desktop | K\_drone for assigned drones + timestamps | Encrypted keystore file (Argon2id + AES-256-GCM); optional OS-keychain wrap later | Operator PIN at app start |
| SurveyGCS Android | Same as desktop | Same file format, additionally wrapped by an Android Keystore AES key | Operator PIN at app start |

The raw key must sit in RAM while a vehicle is connected, because MAVLink signing is SHA-256(key ‖ frame) rather than HMAC. Hardware keystores therefore only unwrap the key. SurveyGCS zeroises key bytes on lock, on idle timeout, and on Android when the app goes to the background.

**Keystore file format (shared by desktop and Android)**

- Header: magic `VNKS`, format version, Argon2id parameters (memory 64 MiB, 3 iterations, parallelism 1), 16-byte salt.
- Body: AES-256-GCM over a JSON document with a 12-byte random nonce; the header is the associated data.
- JSON: list of `{drone_id, key_b64, key_version, label, sysid_hint, added_at}`.
- Timestamps live in a separate plain file, `signing_ts.json`, keyed by drone\_id and link\_id. They are not secret, change often, and losing them is recoverable.

**Provisioning (new drone)**

1. Connect the FC over USB to the provisioning machine and read the UID.
2. Derive K\_drone, then send SETUP\_SIGNING over USB only. Confirm the FC now rejects an unsigned command on a telemetry port.
3. Export a bundle for the Pi (raw key, to be wrapped on the Pi) and import it on the Pi over SSH.
4. Export a bundle for each SurveyGCS device. The bundle is itself encrypted with a one-time passphrase read out to the operator.
5. Record drone\_id, label, key version and date in the provisioning log. The log holds no key material.

**Rekey and revoke**

| Event | Action |
| --- | --- |
| GCS laptop or tablet lost or captured | Bump key version for every drone it held; reprovision those FCs and Pis; push new bundles to remaining devices |
| Pi SD card lost | Rekey that drone only |
| FC replaced | New UID, so a new key; old key retired |
| Suspected master compromise | Bump the label to v2 and reprovision the fleet |
| Operator forgets PIN | Wipe the keystore and re-import bundles |

## Implementation stages and prompts

Fourteen stages on two tracks: Claude Code builds the SurveyGCS side (Track A), Codex builds the Python and firmware side (Track B), and each reviews the other at the end. Every prompt below is meant to be pasted as is into a fresh session.

**Model choice rule.** Opus for anything bit-exact or security-critical where a subtle mistake ships silently (framing, signatures, replay, gateway rules, firmware). Sonnet for well-trodden code with strong tests around it (vault plumbing, screens). Codex for Python, Linux and tooling, and as the independent reviewer of Claude's work.

| Stage | Track | Model | Depends on | Output |
| --- | --- | --- | --- | --- |
| S0a Verify mavlink-kotlin signing | A | Opus | — | Findings + ADR-006 draft |
| S0b Verify SITL, router, pymavlink | B | Codex | — | Findings + captured signed frames |
| S1 Signing core (pure) | A | Opus | S0a, S1v | `Mav2Signing`, clock, replay guard, tests |
| S1v Test-vector generator | B | Codex | S0b | `tools/signing/make_vectors.py` + JSON vectors |
| S2 Byte layer: FrameSink + SigningSource | A | Opus | S1 | Signing wired under the library |
| S3 Gateway, heartbeat, status | A | Opus | S2 | Per-vehicle signing end to end with a fake keyring |
| S4 `core:keys` vault | A | Sonnet | S1, S6 vectors | Encrypted vault + keyring |
| S5 Screens and badges | A | Sonnet | S3, S4 | Signing keys screen, unlock, badges |
| S6 Provisioning tool | B | Codex | S0b | `provision.py`, bundle format, interop vectors |
| S7 Pi router + bridge signing | B | Codex | S0b, S6 | Router, signed bridge, OTP-wrapped key |
| S8 FC fork changes | B | Codex, reviewed by Opus | S0b | Armed-lock on SETUP\_SIGNING, bad-signature counter |
| S9 SITL attack suite + end-to-end | A + B | Codex scripts, Sonnet runs | S3–S8 | Pass/fail report |
| S10 Cross-review | A + B | Codex reviews A, Opus reviews B | S9 | Review notes + fixes |
| S11 Bench and flight | Human | — | S10 | Signed-off checklist |

**How to run a stage**

- SurveyGCS stages run in Claude Code at the repo root, so `CLAUDE.md` loads by itself. Start each with `/model opus` or `/model sonnet` as listed.
- GNSS-Denied stages run in Codex at that repo root; it reads `AGENTS.md`. Codex sessions on SurveyGCS must be told to read `CLAUDE.md`, because that repo has no `AGENTS.md`.
- Put this document in each repo as `docs/signing/PRD-signing.md` before S0, so prompts can point at it.
- One stage per session, one branch per track: `h/signing-gcs` and `h/signing-pi`. Commit at the end of every stage.

### S0a — Verify mavlink-kotlin signing (Opus, Track A)

```text
You are working in KFT_Survey_GCS. Read CLAUDE.md, docs/signing/PRD-signing.md (sections "Scope" and "Architecture"), and the latest docs/learning-log.md entry.

Goal: prove or disprove that mavlink-kotlin 1.2.15 cannot be used for MAVLink v2 signing, before we build our own layer.

Check, with evidence (javap on the jars in the Gradle cache, the same way BitExactCommandLong.kt documents its NaN finding):
1. The parameter type of `timestamp` in MavConnection.sendSignedV2 and the bytes it writes for the 6-byte timestamp field.
2. How BufferedMavConnection parses a signed frame: does it keep all 6 timestamp bytes, does it check the signature, does it drop or pass signed frames, does it validate CRC with the incompat flag set.
3. What MavFrameV2.validateSignature hashes: the original raw bytes, or a re-serialisation with the truncated timestamp.
4. Whether BufferedMavConnection keeps its own sequence counter, and whether two writers on the same sink would interleave.

Write a jvmTest `SigningLibraryProbeTest` that feeds a pymavlink-style signed HEARTBEAT (timestamp 0x0000_2234_5678_9ABC, key = 32 x 0x42) into BufferedMavConnection and records what comes out. Do not @Ignore it: it documents behaviour, so assert what you observe.

Deliverables: the probe test; docs/decisions/ADR-006-mavlink-signing.md (status Proposed) that records the findings and the decision "sign below the library" (or the alternative if the finding is wrong); a pass summary in docs/learning-log.md. Do not change production code in this stage. Run ./gradlew check.
```

### S1 — Signing core, pure (Opus, Track A)

```text
You are working in KFT_Survey_GCS on branch h/signing-gcs. Read CLAUDE.md, ADR-006, docs/signing/PRD-signing.md ("Architecture": outbound rules, timestamps), and tools/signing/vectors/*.json produced by S1v.

Build the pure signing core in core:mavlink, package com.kft.gcs.core.mavlink.signing. No I/O, no clocks, no coroutines in these files.

1. Sha256.kt: `internal expect fun sha256(data: ByteArray): ByteArray`; jvmCommonMain actual with java.security.MessageDigest (mirror core:vehicle HmacSha256).
2. Mav2Signing.kt:
   - `fun signV2(unsigned: ByteArray, crcExtra: Byte, key: ByteArray, linkId: UByte, timestamp: Long): ByteArray` — input is a complete unsigned v2 frame. Set incompat_flags bit 0x01, recompute CRC-16/MCRF4XX (X.25) over bytes 1..end-of-payload plus crcExtra, append link_id, timestamp as 6 bytes little-endian, then the first 6 bytes of sha256(key + frameWithoutSignature). Require key.size == 32 and 0 <= timestamp < 2^48.
   - `fun verifyV2(signed: ByteArray, key: ByteArray): Boolean` — constant-time compare of the 6 signature bytes.
   - `data class SignatureTrailer(linkId: UByte, timestamp: Long)` and `fun trailerOf(signed: ByteArray): SignatureTrailer?`.
3. SigningClock.kt: pure state `(current: Long)` with `fun start(wallMs: Long, persisted: Long?)`, `fun next(): Long`, `fun observeIncoming(ts: Long)`. Epoch 1420070400 s, 10 µs ticks, 48-bit wrap is an error, not a wrap.
4. ReplayGuard.kt: `fun accept(linkId: UByte, sysId: UByte, compId: UByte, ts: Long, localNow: Long): Boolean` following the MAVLink spec: reject ts <= last for a known stream; for a new stream reject if ts < localNow - 6_000_000.

Tests in commonTest: every vector in the JSON files (sign and verify both ways), CRC with the flag flipped, wrong key, one bit flipped in each region (header, payload, CRC, link_id, timestamp, signature), timestamp edge values (0, 2^48 - 1), replay rules including the 60 s first-seen window, clock monotonicity across restarts. Independent reference values only: the pymavlink vectors, never our own output.

KDoc every public function with the spec rule it implements and a link to https://mavlink.io/en/guide/message_signing.html. Pass summary with a Safety heading. ./gradlew check must pass.
```

### S2 — Byte layer: FrameSink and SigningSource (Opus, Track A)

```text
You are working in KFT_Survey_GCS on h/signing-gcs. Read CLAUDE.md (§4 gateway rule), ADR-006, docs/signing/PRD-signing.md ("Scope: SurveyGCS edits" and "Architecture"), MavTransport.kt, MavTxGateway.kt, ConnectionManager.kt, Tlog.kt, FakeMavConnection.kt.

Goal: all bytes the GCS writes go through one FrameSink, and all bytes it reads pass a SigningSource before mavlink-kotlin sees them. Behaviour for unsigned vehicles must not change.

1. SigningKeyring.kt (interface, core:mavlink): `val linkId: UByte`; `fun isUnlocked(): Boolean`; `fun candidates(): List<Pair<String, ByteArray>>` (droneId to key); `fun keyFor(droneId: String): ByteArray?`; `fun loadTimestamp(droneId: String): Long?`; `fun saveTimestamp(droneId: String, ts: Long)`. Provide `NoSigning` (empty, unlocked) as the default so today's wiring compiles unchanged, and `FakeKeyring` in commonTest.
2. FrameSink.kt: `internal class FrameSink(sink: BufferedSink, keyring, clock source)` with `suspend fun write(message: MavMessage<*>, sysId, compId, signFor: String?)`. It owns the sequence counter, builds the unsigned frame with MavRawFrame.createUnsignedV2 (keep the BitExactCommandLong path), signs with Mav2Signing when signFor != null, writes and flushes under a Mutex. One SigningClock per droneId.
3. SigningSource.kt: okio Source filter. Parse v2 (0xFD) and v1 (0xFE) frames; on garbage, pass bytes through. Apply the inbound rules from the PRD exactly (bound sysid, trial binding on HEARTBEAT, spoofed unsigned, RADIO_STATUS exception, unbound unsigned passes). Keep a `StateFlow<Map<UByte, SigningStatus>>` and counters. Never block; never allocate per byte.
4. Wire it: TransportMavConnection.open() wraps the source and creates the FrameSink; openTransportConnection returns both. The gateway still writes nothing yet in this stage beyond what it does today: switch its write path to FrameSink with signFor = null and prove byte-identical output against the old sendUnsignedV2 bytes.

Tests: byte streams with mixed v1, unsigned v2, signed v2, garbage and split reads (1 byte at a time); binding by trial; replayed frame dropped; spoofed unsigned dropped; RADIO_STATUS kept; unchanged output for unsigned sends. Run ./gradlew check, /ponytail-review, pass summary with Safety heading.
```

### S3 — Gateway, heartbeat and status (Opus, Track A)

```text
You are working in KFT_Survey_GCS on h/signing-gcs after S2. Read CLAUDE.md §4, ADR-006, the PRD ("Architecture" outbound rules and identity), MavTxGateway.kt, TxPolicy.kt, ConnectionManager.kt, MavTxGatewayTest.kt, ConnectionManagerTest.kt.

1. VehicleInfo gains `signing: SigningStatus = SigningStatus.UNSIGNED`. ConnectionManager merges SigningSource status into VehicleInfo in its existing single collector (no new collector, no race).
2. MavTxGateway.forVehicle: after TxPolicy and the existing target/duplicate checks, look up the vehicle's SigningStatus. SIGNED -> FrameSink.write(signFor = droneId). UNSIGNED -> unsigned. NO_KEY -> Rejected("vehicle signs its traffic; no key for it on this device"). LOCKED -> Rejected("signing keys are locked; unlock in Settings"). Rejections go to `rejections` like today.
3. sendHeartbeat: one signed heartbeat per distinct bound droneId on the link, plus one unsigned heartbeat if any vehicle is UNSIGNED or none is heard.
4. TxPolicy: SETUP_SIGNING (common.xml id 256) classified NEVER with label "SETUP_SIGNING" and a MavTxGatewayTest row proving it is refused for every pod state and armed state. Keys never leave this device over a link.
5. Links/MavlinkModule: inject SigningKeyring (default NoSigning) into every ConnectionManager.
6. Persist timestamps through the keyring every 10 s per bound drone and on disconnect.

Tests (fake transport, virtual time): signed vehicle gets signed frames that verify with its key; unsigned vehicle unchanged; NO_KEY and LOCKED refusals; heartbeat fan-out with 0, 1 and 2 bound drones plus 1 unsigned; rebinding after reconnect; KFT login COMMAND_LONG still bit-exact when signed. Add a jvmTest SITL run `SigningSitlRun` (manual, like FleetSitlRun) with the steps in the pass summary. ./gradlew check, /ponytail-review, Safety heading.
```

### S0b — Verify SITL, router and pymavlink signing (Codex, Track B)

```text
You are working in GNSS-Denied. Read AGENTS.md, CLAUDE.md, FLOW.md (SITL sections and fc_bridge), platform/fc_bridge/README.md, bridge.py, and docs/signing/PRD-signing.md.

Goal: establish the facts Track B builds on. Write findings to Docs/signing/S0b-findings.md with commands and outputs.

1. ArduCopter SITL (same command as FLOW.md, MAVLINK20=1): set a signing key over SERIAL0 with MAVProxy (`signing setup <passphrase>`, `set allow_unsigned False`). Confirm that an unsigned COMMAND_LONG on the --out UDP port is ignored and a signed one is accepted. Note what ArduPilot does with an unsigned frame on SERIAL0 (USB) vs other ports.
2. With pymavlink: `mav.setup_signing(key, sign_outgoing=True, link_id=N, initial_timestamp=T)`. Confirm the bridge's KFT handshake (kft_auth.py) and ODOMETRY are accepted when signed, and dropped when the key is wrong. Measure signing time per frame on this machine.
3. Router: run mavlink-router (or MAVProxy as a stand-in if mavlink-router is unavailable) between SITL and two clients. Confirm signed frames from a client reach SITL byte-for-byte (capture with tcpdump or a pass-through logger) and that the router does not strip, re-sign or reorder the 13-byte trailer.
4. Capture 20 signed frames from SITL and 20 from pymavlink (HEARTBEAT, COMMAND_LONG, ODOMETRY, PARAM_SET, MISSION_ITEM_INT) with their keys, into tools/signing/captures/*.bin plus a JSON index. These feed S1v.
5. Record the ArduPilot fork version the VisionNav FC runs and where GCS_Signing.cpp lives in it.

No production code changes. Commit findings and captures.
```

### S1v — Test-vector generator (Codex, Track B)

```text
You are working in GNSS-Denied on branch h/signing-pi. Read AGENTS.md, docs/signing/PRD-signing.md ("Architecture": timestamps; "Key management": derivation), and Docs/signing/S0b-findings.md.

Write tools/signing/make_vectors.py (pymavlink, ardupilotmega dialect, MAVLINK20=1) that writes tools/signing/vectors/signing_v1.json. Each vector: name, key_hex, link_id, timestamp, message name, unsigned_frame_hex, signed_frame_hex, crc_extra. Cover: HEARTBEAT, COMMAND_LONG with NaN-pattern floats, MISSION_ITEM_INT, PARAM_SET, ODOMETRY, RADIO_STATUS; timestamps 0, 1, today's value, 2^48 - 1; link_id 0, 1, 254; payloads with trailing zeros (v2 truncation). Add negative vectors (one bit flipped per region) with expected=false.

Also write derivation vectors: master_hex, uid_hex (12 bytes), label "VNAV-SIGN-v1", expected K_drone = HMAC-SHA256(master, label || uid). Same file, section "derivation".

pytest in tests/signing/test_vectors.py regenerates and compares, and checks every signed frame with pymavlink's own verifier. Copy the JSON into KFT_Survey_GCS/core/mavlink/src/commonTest/resources/signing/ in a separate commit there, or hand it to Track A. Update FLOW.md.
```

### S4 — `core:keys` vault (Sonnet, Track A)

```text
You are working in KFT_Survey_GCS on h/signing-gcs. Read CLAUDE.md (§2 modules, §6 versions), ADR-006, docs/signing/PRD-signing.md ("Key management" in full), core/vehicle HmacSha256 expect/actual, app/shared FileTextStore.kt, and tools/signing/vectors/bundle_v1.json from S6.

First commit (separate version pass): add org.bouncycastle:bcprov-jdk18on to gradle/libs.versions.toml, used only for Argon2BytesGenerator (lightweight API, no JCA provider registration, so it is safe on Android).

Then create module core:keys (kft.kmp.library, purpose comment), depending on core:mavlink for the SigningKeyring interface.

1. Vault file format exactly as the PRD: magic VNKS, version, Argon2id params (64 MiB, t=3, p=1), 16-byte salt, 12-byte nonce, AES-256-GCM over JSON entries, header as AAD. Atomic write via temp file + rename (same pattern as FileTextStore).
2. `class Vault(store, crypto)`: create(pin), unlock(pin), lock() zeroises key bytes, add/remove/list entries, installation link_id chosen once at create.
3. `class VaultKeyring(vault, timestamps) : SigningKeyring`.
4. `TimestampFile`: plain JSON drone_id -> ts, written at most every 10 s.
5. `BundleImport`: decrypt a .vnkey bundle from the provisioning tool with its one-time passphrase (same crypto, its own magic VNKB) and add its entries.
6. androidMain: wrap the vault file's AES key with an Android Keystore AES-GCM key as a second layer (the PIN is still required). jvmMain: PIN only for now; leave an interface seam for DPAPI later.
7. Idle auto-lock timer driven by an injected clock.

Tests: round trip, wrong PIN, tampered header or body fails, lock zeroises, Python bundle vectors decrypt (interop), Argon2id against the RFC 9106 test vector, atomic write survives a crash between write and rename. Bind in app/shared di: desktop %APPDATA%\KFT-GCS\signing.vnks, Android filesDir. ./gradlew check, pass summary with Safety heading.
```

### S5 — Screens and badges (Sonnet, Track A)

```text
You are working in KFT_Survey_GCS on h/signing-gcs after S3 and S4. Read CLAUDE.md §3 MVVM and the ui:design rules, the PRD (FR-8, FR-9), feature/settings, feature/connections, feature/fly, App.kt.

1. feature/settings: SigningKeysScreen + SigningKeysViewModel + SigningKeysUiState. States: no vault (create with PIN, twice), locked (enter PIN), unlocked (list: label, drone_id short form, key version, last seen; actions: import bundle, remove key with confirm, lock now). Never display key bytes.
2. App start: if a vault exists and is locked, show an unlock dialog; Skip leaves it locked and signed vehicles show LOCKED.
3. Badges: Links vehicle rows and the Fly HUD / Fleet list show the vehicle's SigningStatus with KftTheme.status colours: Signed (good), Unsigned (neutral), No key / Locked / Bad signature (warning). Show the FC's bad-signature count when present (from S8's NAMED_VALUE_INT "SIGFAIL").
4. Gateway refusals for NO_KEY and LOCKED surface as the existing rejection snackbar with a "Open signing keys" action.

ViewModel tests with Turbine against fake Vault/keyring; screenshot the screens in light, dark and high-contrast into docs/design/34-*.png. ./gradlew check, pass summary.
```

### S6 — Provisioning tool (Codex, Track B)

```text
You are working in GNSS-Denied on h/signing-pi. Read AGENTS.md, docs/signing/PRD-signing.md ("Key management" in full), Docs/signing/S0b-findings.md, tools/signing/vectors/signing_v1.json.

Build tools/signing/provision.py (Python 3.11, pymavlink, cryptography, argon2-cffi) with subcommands:
- `init-master --out master.vnm`: 32 random bytes, stored with Argon2id (64 MiB, t=3, p=1) + AES-256-GCM under a passphrase. Refuse to overwrite.
- `provision --master master.vnm --port COMx|/dev/ttyACMx --label NAME`: connect over USB serial ONLY (refuse UDP/TCP/telemetry ports; refuse if the FC is armed), read AUTOPILOT_VERSION uid2 (first 12 bytes = STM32 UID), derive K_drone = HMAC-SHA256(master, b"VNAV-SIGN-v1" + uid), send SETUP_SIGNING with initial_timestamp = now in 10 us ticks since 2015-01-01, then prove it: an unsigned PARAM_REQUEST_READ on a second, non-USB connection (optional --verify-port) gets no answer, a signed one does.
- `export-gcs --master ... --drones uid1,uid2 --out ops-laptop-1.vnkey`: bundle format VNKB = same header layout as the GCS vault, encrypted under a generated one-time passphrase (6 words) printed once to the terminal. Entries: drone_id, key, key_version, label, added_at.
- `export-pi --master ... --drone uid --out pi.key`: raw 32-byte key file for S7's wrapper, mode 0600.
- `rekey --drone uid`: bump key_version (label becomes VNAV-SIGN-v1/kv2 style) and re-run provision.
- Every command appends to provisioning-log.csv: time, command, drone_id, label, key_version. Never key bytes.

Write tools/signing/vectors/bundle_v1.json (passphrase, bundle_hex, expected entries) for the Kotlin interop test in S4. pytest: derivation vectors, bundle round trip, refusal on non-USB port and on armed FC (mock), log contains no secrets (grep for every key in the test). Document in FLOW.md and tools/signing/README.md.
```

### S7 — Pi router and bridge signing (Codex, Track B)

```text
You are working in GNSS-Denied on h/signing-pi. Read AGENTS.md, FLOW.md, platform/fc_bridge/README.md, bridge.py, kft_auth.py, Docs/signing/S0b-findings.md, and docs/signing/PRD-signing.md ("Part A" topology, FR-10 to FR-12).

Today bridge.py owns /dev/ttyAMA0 with flock, so GCS traffic has no path to the FC. Fix that and sign the bridge.

1. Router: platform/router/ with a mavlink-router config: UART endpoint /dev/ttyAMA0 at 921600; a UDP server endpoint for the GCS link (port configurable, default 14550); a local UDP endpoint for the bridge (127.0.0.1:14601). A systemd unit that starts it before the bridge. The router must forward signed frames byte-for-byte (S0b proved this; add a regression test using a pass-through capture in SITL).
2. bridge.py: new --signing-key-file and --signing-link-id (default 197) and --signing-ts-file. On connect: `mav.setup_signing(key, sign_outgoing=True, link_id=..., initial_timestamp=max(now_ticks, saved+1))`; persist the timestamp every 10 s and on exit. Keep flock semantics on whatever it opens. Health JSON gains signing: OFF | ON | KEY_MISSING. If the key file is missing and --require-signing is set, refuse to send anything and report FAILED.
3. Key at rest: platform/keys/pi_key_wrap.py. `wrap --in pi.key` derives a wrapping key with HKDF-SHA256 from the Pi 5 OTP customer private key (`rpi-otp-private-key`; verify it exists on our image and document the one-time OTP programming step, which is irreversible), encrypts pi.key with AES-256-GCM into /etc/visionnav/signing.key.enc (root:root 0600), then deletes the plaintext. `unwrap` is called by the bridge's systemd unit into a tmpfs path at start. On a non-Pi dev machine, fall back to a passphrase with a loud warning.
4. Tests (pytest, no hardware): bridge sends signed frames that verify with pymavlink; wrong key -> SITL ignores ODOMETRY and health reports it; timestamp persists across restart and never goes backwards; wrap/unwrap round trip with a fake OTP source; plaintext file removed after wrap.

Update FLOW.md, the fc_bridge README, and LEARNINGS.md as the repo's rules require.
```

### S8 — FC fork changes (Codex writes, Opus reviews, Track B)

```text
You are working in the VisionNav ArduPilot fork at <path from S0b findings>, ArduPilot 4.6.x. Read Docs/signing/S0b-findings.md and docs/signing/PRD-signing.md (FR-1 to FR-4). Keep the KFT Clause 7 login code untouched.

1. libraries/GCS_MAVLink/GCS_Signing.cpp, handle_setup_signing: refuse (and send STATUSTEXT "Signing change refused: armed") when hal.util->get_soft_armed() is true. This covers both a new key and an all-zero key (disable).
2. Bad-signature counter: where the MAVLink parser result is checked for each channel, count results equal to MAVLINK_FRAMING_BAD_SIGNATURE per channel. Once per second, if the total changed, send NAMED_VALUE_INT name "SIGFAIL" value = total since boot on all active channels. Rate-limit a STATUSTEXT "Bad MAVLink signatures: N" to one per 10 s.
3. Leave accept_unsigned_callback as stock (USB channel 0 plus RADIO_STATUS). Add parameter KFT_SIGN_USB (0 = stock; 1 = USB also requires signing while armed) defaulting to 0, documented.
4. Ensure the VisionNav default parameter file sets SERIALn_PROTOCOL = 2 for the telemetry and companion ports.
5. SITL autotest or a pymavlink script under Tools/ that proves: unsigned command ignored on SERIAL1, accepted on SERIAL0; SETUP_SIGNING refused while armed; SIGFAIL increments on a bad signature.

Build for SITL and for the VisionNav board target. Do not touch secure boot or Clause 7 code. Write a change note for review.
```

Opus review prompt for S8 (run in Claude Code on the fork):

```text
Review the diff on this branch against docs/signing/PRD-signing.md FR-1 to FR-4. Check: armed check cannot be bypassed via any channel or via MAVLink forwarding; the counter cannot overflow or spam; NAMED_VALUE_INT goes out on all channels without breaking stream rate budgets; no change to GCS_Signing behaviour for disarmed USB provisioning; Clause 7 code untouched. List findings by severity with file:line, then fix only what is High.
```

### S9 — SITL attack suite and end-to-end (Codex scripts, Sonnet runs, Tracks A + B)

```text
Codex, in GNSS-Denied on h/signing-pi: write tools/signing/attacks/ (pymavlink, each refuses to run unless the target has SIM_ parameters):
- inject_unsigned.py: unsigned PARAM_SET, MISSION_COUNT, COMMAND_LONG to the router's GCS port claiming sysid 255.
- wrong_key.py: same frames signed with a different key.
- replay.py: capture signed GCS frames with a pass-through proxy, replay them after 2 s and after an FC reboot.
- spoof_telemetry.py: unsigned and wrong-key HEARTBEAT and GLOBAL_POSITION_INT claiming the FC's sysid, sent toward the GCS.
- far_future.py: correctly keyed frame with timestamp now + 1 day, then normal traffic from the real GCS (documents the FC behaviour).
- fuzz_trailer.py: random corruption of the 13-byte trailer.
Each prints PASS/FAIL against the expected outcome in the PRD test table and exits non-zero on FAIL.
```

```text
Sonnet, in KFT_Survey_GCS on h/signing-gcs: run SigningSitlRun end to end against two SITL instances with different keys and one without a key, through the S7 router. Steps: unlock vault, connect, confirm binding and badges, KFT login, param download, mission upload and download on each, heartbeat fan-out visible in the router log, run every attack script and record results. Then start a second GCS instance with a different installation link_id and confirm both work against the same drone. Write the results table into docs/signing/S9-results.md and the pass summary.
```

### S10 — Cross-review (Codex reviews Track A, Opus reviews Track B)

```text
Codex, reviewing KFT_Survey_GCS branch h/signing-gcs. Read CLAUDE.md first (this repo has no AGENTS.md) and docs/signing/PRD-signing.md. Review only, no edits. Check in order: (1) every byte written goes through FrameSink and the gateway; grep for any other sink or sendUnsignedV2 use; (2) Mav2Signing against the MAVLink spec, CRC recomputation, constant-time compare; (3) timestamp never decreases, including restart, clock jumps and two links to one drone; (4) SigningSource cannot be made to bind a sysid with a forged frame, cannot be stalled by garbage, and drops spoofed unsigned frames; (5) keys are zeroised on lock and never logged, toString'd, serialised into profiles or tlogs; (6) vault format matches the PRD and the Python bundle. Output findings by severity with file:line and a suggested fix.
```

```text
Opus, reviewing GNSS-Denied branch h/signing-pi and the FC fork branch. Read docs/signing/PRD-signing.md. Review provision.py (USB-only enforcement, armed refusal, derivation, no secrets in logs or exceptions), the bundle crypto, pi_key_wrap.py (OTP use, file modes, plaintext removal, tmpfs), the router config (no endpoint that bypasses signing, no extra open ports), and bridge signing (timestamp persistence, link_id). Output findings by severity with file:line, then propose fixes as patches.
```

### S11 — Bench and flight (human)

Run the bench checklist in the testing section on one real drone, then a tethered or low-risk flight with VisionNav odometry feeding the EKF. Sign it off before a second drone is provisioned.

## Testing and acceptance

The work is accepted when every row below passes in SITL (S9) and the bench checklist passes on one real drone (S11).

**SITL test table** (the attack scripts in S9 check against the Expected column)

| ID | Scenario | Expected |
| --- | --- | --- |
| T1 | SurveyGCS with the right key: connect, KFT login, params, mission up and down | All succeed; badge Signed |
| T2 | Unsigned PARAM\_SET / COMMAND\_LONG / MISSION\_COUNT injected at the router's GCS port | FC ignores all; SIGFAIL unchanged (unsigned is dropped, not a bad signature) |
| T3 | Same frames signed with a wrong key | FC ignores all; SIGFAIL increases |
| T4 | Captured GCS frames replayed after 2 s and after an FC reboot | FC ignores all |
| T5 | Unsigned or wrong-key HEARTBEAT and position claiming the FC's sysid, sent to the GCS | GCS drops them; position on the map does not move; spoof counter rises |
| T6 | Vault locked, signed drone connects | Badge Locked; every send refused with the unlock message; telemetry still shown only if it verifies after unlock |
| T7 | No key for a signing drone | Badge No key; sends refused |
| T8 | Unsigned drone (other KFT product) on the same GCS | Works exactly as before; badge Unsigned |
| T9 | Two signed drones (different keys) and one unsigned on one GCS | Each bound correctly; heartbeat fan-out = 2 signed + 1 unsigned |
| T10 | Two GCS installations on one drone | Both command it; distinct link\_ids in the FC's stream table |
| T11 | GCS restart, laptop clock set 1 h back | GCS keeps working (persisted timestamp wins) |
| T12 | Pi bridge with wrong key | FC drops ODOMETRY; bridge health FAILED; GCS shows companion fault |
| T13 | SETUP\_SIGNING over a telemetry port, and over USB while armed | Refused both times |
| T14 | Trailer fuzzing (1,000 frames) | No crash, no accepted frame, reader keeps up |
| T15 | Odometry latency, Pi to FC, signed vs unsigned at 921600 baud | Increase under 1 ms (NFR-1) |

**Unit and integration gates**

- `./gradlew check` green on every SurveyGCS stage; signing core tested only against the pymavlink vectors.
- `pytest` green in GNSS-Denied for vectors, provisioning, bridge signing and key wrap.
- Python bundle decrypts in Kotlin, and Kotlin-signed frames verify in pymavlink (both directions of interop).

**Bench checklist (S11, one real drone)**

- [ ] Provision over USB; log shows drone\_id and key version, no key bytes
- [ ] Unsigned command from a spare laptop over the GCS link is ignored
- [ ] SurveyGCS shows Signed; KFT login OK; params and mission round trip
- [ ] Pi bridge health OK; FC's LOCAL\_POSITION\_NED follows VIO odometry
- [ ] Power-cycle the FC, Pi and laptop in turn; everything rebinds without manual steps
- [ ] Lock the vault mid-session: sends refused, aircraft unaffected
- [ ] SETUP\_SIGNING over USB refused while armed (props off)
- [ ] Short hover with VisionNav as the position source; no EKF warnings related to latency
- [ ] Remove the Pi's SD card and confirm the key file on it is ciphertext

## Risks, open questions and parallel plan

The two tracks only touch at two hand-offs (test vectors before S1, the bundle format before S4) and then meet at S9, so both agents can run from day one.

&#91;embedded content: Stage plan across two agents\]

Start S0a and S0b together. Track B's S1v is short, so its vectors are ready before Track A finishes S0a. If Opus limits on Claude Pro run out, run S2 or S3 on Sonnet and have Opus review the diff afterwards, rather than the reverse.

**Risks**

| Risk | Effect | Mitigation |
| --- | --- | --- |
| mavlink-kotlin finding turns out different in 1.2.15 | Some of S2 may be unnecessary | S0a settles it before any production code; the byte layer still works either way |
| mavlink-router alters or drops the signature trailer | GCS commands never reach the FC | S0b tests it; fallback is MAVProxy or forwarding inside the bridge |
| GCS link bandwidth unknown | +13 bytes per frame may saturate a thin radio | Measure in S9; retune SRx stream rates if needed |
| No GPS time on the FC (GNSS-denied) | FC timestamp comes only from its saved value and from incoming signed frames | Expected and handled: GCS sends max(wall clock, FC's last timestamp + 1); covered by T11 |
| Pi 5 OTP programming is one-time and irreversible | A mistake bricks the wrap key for that Pi | Program on one spare Pi first; document the exact command in S7 |
| Laptop captured while unlocked | Keys are in RAM | Idle auto-lock; rekey procedure in key management |
| Telemetry stays readable | Position and mission visible to a listener | Accepted for now; link encryption is a separate decision |
| Companion WebSocket (Ktor) to the Pi is unauthenticated | Operator diagnostics could be spoofed | Out of scope; follow-up to add TLS or an HMAC on that channel |
| KFT login key is compiled into the GCS binary | Extractable from any installer | Out of scope; follow-up to move it into the vault |

**Open questions**

- Which physical link carries GCS traffic to the Pi: Wi-Fi, a telemetry radio, or something else? This sets the router endpoint type and the bandwidth budget.
- Where does the VisionNav ArduPilot fork live, and does it run the Clause 7 login (the SITL parameter file `kft_locked.parm` suggests yes)?
- Who holds MASTER, and on which machine?
- Is the OTP customer key on the VisionNav Pi 5 units still unprogrammed?
