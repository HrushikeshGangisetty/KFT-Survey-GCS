# ADR-003 — KFT HMAC login

**Status:** Accepted
**Date:** 2026-09-25 · **Pass:** 12 · **Spec:** S12, GS-9

## Context

ardupilotKFT (Copter 4.6.3-kftv7, "MOINA") drops every incoming MAVLink message except HEARTBEAT and
`MAV_CMD_USER_1/USER_2` until a GCS completes an HMAC challenge-response (`libraries/GCS_MAVLink/GCS_Common.cpp`
`handle_message`, `KFT_GCSAuth.cpp`). The existing Android GCS already does this with APP_ID 1 and one fleet key.
The survey GCS must do the same, or it can't upload a mission to a KFT drone.

## Decision

1. **Same protocol, same identity.** APP_ID 1 and the Android GCS's fleet key. No firmware change needed.
2. **Key injection.** `KFT_APP_SECRET` comes from `local.properties`, falling back to the environment variable (the
   same sources as the Esri key). A Gradle task in `core:vehicle` writes it into
   `build/generated/kftAppSecret/…/KftAppSecret.kt`. That's under `build/`, so it's git-ignored and never committed.
   - Unlike the Esri key, it can't be passed at `run` time only, because the APK and the packaged desktop app need it.
   - No key, or not exactly 64 hex characters: the generated constant is `""`. The build succeeds (CI), and the app
     shows "KFT login: no key configured" and doesn't try.
3. **Crypto: `javax.crypto` in a shared `jvmCommon` source set**, not KotlinCrypto `hmac-sha2` in `commonMain`.
   - Both of our targets (Android, desktop) are JVMs, so `javax.crypto.Mac` exists on both, with no new dependency.
   - On a security path, the platform's audited provider (the JDK on desktop, Conscrypt/BoringSSL on Android) beats
     a third-party pure-Kotlin implementation. Every added dependency is supply-chain surface.
   - KotlinCrypto would only pay off with an iOS/native target, and none is planned. If one comes, the `expect fun
     hmacSha256` is the single seam to add an `actual` to.
4. **Float packing: bits, never arithmetic.** 24 MAC bytes become six little-endian ints and then
   `Float.fromBits`.
   - **mavlink-kotlin 1.2.15 encodes floats with `Float.floatToIntBits`**, which turns every NaN pattern into
     `0x7FC00000` (checked with `javap` on `SerializationUtilKt.encodeFloat`). About 2.3% of logins would carry one
     and be DENIED.
   - `MavTxGateway` therefore sends every COMMAND_LONG through `BitExactCommandLong`: the same id, CRC extra and
     layout, but raw bits.
5. **Flow.** Heartbeat → login → the connect-time requests → mission traffic allowed.
   - AUTHENTICATED, LEGACY_FIRMWARE (UNSUPPORTED or no ACK), and NO_KEY let traffic through. DENIED and FAILED don't.
   - Re-login after a heartbeat gap over 6 s, at most one start per 2 s, and fresh on every new link.
   - FAILED is retried 3 times (after 2, 4 and 8 s), then left alone until the next gap. The Android GCS retried
     forever.
   - Subscriptions start with `CoroutineStart.UNDISPATCHED` before anything is sent, and the challenge halves are
     `StateFlow`s. That replaces `delay(100)` and 100 ms polling.

## Consequences

- One extraction of the key from any APK or JAR unlocks every drone (GS-9). This is acceptable for the prototype
  fleet, not for a wider release.
- Also found while checking the firmware:
  - The firmware's app keys are committed in `KFT_GCSAuth.h`.
  - The login is global to the flight controller: every link is unlocked once any GCS logs in.
  - Any heartbeat keeps the login alive (its timeout is 10 s, not 5 s).

  All three are firmware-side and recorded under GS-9.
- The `BitExactCommandLong` workaround can go once mavlink-kotlin encodes with raw bits.
  `BitExactCommandLongTest.libraryEncoderCanonicalisesNaN` fails the day that happens.
- Tests:
  - RFC 4231 vectors.
  - A byte-for-byte comparison with the Android computation (javax + `ByteBuffer` LITTLE_ENDIAN) over 2000 random
    cases, about 47 of them containing NaN patterns.
  - A fake ardupilotKFT for accept / wrong key / missing KFTCH2 / UNSUPPORTED / no ACK / re-login.
  - A frame-level NaN test.
  - The golden vector from a real Android login is a placeholder until it's recorded.
