# S9 — SITL end-to-end results (Track A run)

24 October 2026. Owner: Hrushikesh. Run by Claude Code on `h/signing-gcs`.
Scope: the GCS side of the PRD SITL test table ("Testing and acceptance"). The attack scripts that the PRD assigns to
Codex (`GNSS-Denied/tools/signing/attacks/`) were never written. Hrushikesh decided to mark their rows **BLOCKED**
rather than have this pass write them.

## Setup

```
GCS 1 (vault: A, B; link_id 60)  ─UDP 14550─┐                          ┌─ tap ─ SITL 0  sysid 1  key A
GCS 2 (vault: A;    link_id 135) ─UDP 14551─┼─ mavlink-router (S7) ────┼─ tap ─ SITL 1  sysid 2  key B
T5 spoof injector                ─UDP 14560─┘   (WSL Ubuntu 22.04)     └─ tap ─ SITL 2  sysid 3  no key
```

| Item | Identity |
| --- | --- |
| SITL | KFT fork `ardupilot-kft`, `h/S8-fc-signing` at `ae32f54a2b` **plus the uncommitted working tree** (`GCS_Signing.cpp`, change note). Binary SHA-256 `0478c198ff42ad122023c90d9d409143653d1fcf8b3fb01182ba15e73f39c7eb`. This is not the `a0eab23c…` binary recorded in S8: it was rebuilt afterwards. |
| Router | mavlink-router `v4-19-g5e38c7a`, SHA-256 `ec870aaed54a6744fa4acf971ac560f3662e74f0b81bc398accec2bf5b782981` (same as S7) |
| Keys | `tools/sitl/s9_make_keys.py`: S6 `provision.derive_key` from a fixed public test master; bundles sealed by S6 `provision.seal` (VNKB) and imported by the GCS's own `BundleImport` |
| GCS | `SigningSitlRun` (core:vehicle jvmTest), production `Vault` / `VaultKeyring` / `Links` / UDP transport / `MavTxGateway` / `Fleet` |
| Harness | `tools/sitl/s9_signing_sitl.py up` (3× `arducopter --model hexa --wipe`, `kft_locked.parm`, `SERIAL1_PROTOCOL 2`, key installed by SETUP_SIGNING on disarmed SERIAL0) |

The router config differs from the production template because this is SITL: it has three TCP endpoints instead of one
UART, a second GCS server (S7 notes that one server endpoint serves one GCS peer), and a loopback injection server. TCP
listening is still off and the router holds no key. See [router.conf](s9-evidence/router.conf).

**"Router log".** `mavlink-routerd -v` logs only endpoint set-up ([router.log](s9-evidence/router.log)). The per-frame
log is the harness's taps: a byte-transparent TCP pass-through between the router and each SITL SERIAL1. Each tap logs
every frame entering the FC and checks its signature against each key. Excerpts are in
[taps-excerpt.txt](s9-evidence/taps-excerpt.txt). The full 26 MB log stays in `build/`.

## Results (final run, `./gradlew :core:vehicle:jvmTest --tests '*SigningSitlRun*'` with `KFT_S9_RUN=1`: BUILD SUCCESSFUL)

| ID | Scenario | Result | Evidence |
| --- | --- | --- | --- |
| T1 | Right key: connect, KFT login, params, mission up and down | **PASS** (sysid 1, 2) | Badge `Signed`, `KFT login: OK`, 1434 params, missions of 4 and 5 items written and read back equal, seq 0 = home |
| T2 | Unsigned PARAM_SET / COMMAND_LONG / MISSION_COUNT injected at the router | **BLOCKED** | `inject_unsigned.py` not written. Indirect: S8 group 1 (unsigned ignored on SERIAL1/2) |
| T3 | Same frames signed with a wrong key | **BLOCKED** | `wrong_key.py` not written. Indirect: S8 group 2 |
| T4 | Replay after 2 s and after an FC reboot | **BLOCKED** | `replay.py` not written. Indirect: S8 group 3 (replay counted) |
| T5 | Unsigned and wrong-key HEARTBEAT + GLOBAL_POSITION_INT claiming sysid 1, sent to the GCS | **PASS, partly** | 120 frames (60 unsigned, 60 signed with a wrong key) via the router. Position stayed at CMAC (spoof said 10° N 10° E), badge stayed `Signed`, still 3 vehicles. The PRD's "spoof counter rises" **can't be observed**: `SigningCounters` isn't exposed outside `SigningSource` (finding 4). Injected by the harness `spoof` subcommand, not by the missing `spoof_telemetry.py` |
| T6 | Vault locked, signed drone connected | **PASS, with a gap** | Once the next heartbeat marks it `Keys locked`, sends are refused with `signing keys are locked; unlock in Settings`. The unsigned sysid 3 still answered and GCS 2 was unaffected. After unlock, sysid 1 was `Signed` again and answered. In the first second after `lock()` the send came back as `TxResult.Failed(IOException …)`, not `Rejected(LOCKED)`. Nothing was sent, but no refusal toast either (finding 3) |
| T7 | No key for a signing drone | **PASS** | GCS 2 holds only key A. Sysid 2 is listed as `No key` on Links, is not a vehicle, and a send is refused with `vehicle signs its traffic; no key for it on this device` |
| T8 | Unsigned drone on the same GCS | **PASS** | Sysid 3 badge `Unsigned link`, `KFT login: OK`, 1434 params, 6-item mission written and read back |
| T9 | Two signed drones (different keys) + one unsigned on one GCS | **PASS** | Bound as sysid 1 → A, 2 → B, 3 → Unsigned. Every SITL received per second one heartbeat signed with A, one with B (link_id 60) and one unsigned (taps) |
| T10 | Two GCS installations on one drone | **PASS** | link_id 60 (GCS 1) and 135 (GCS 2) each asked sysid 1 twice, interleaved: 4/4 answered. SITL 0 received `A/60` and `A/135`. The FC's internal stream table can't be read over MAVLink; answers to both link_ids are the evidence. GCS 2 was a second, independent set of objects in the same JVM (own vault, link_id, timestamp file, socket), not a second app process (finding 7) |
| T11 | GCS restart with the laptop clock 1 h back | **PASS (simulated)** | The system clock wasn't changed. Instead, after a disconnect, drone A's saved timestamp was set 1 h ahead of the wall clock, which is the same situation. After reconnecting, sysid 1 rebound and answered, and every frame to it was stamped above the saved value (`…341907 > …341900`) |
| T12 | Pi bridge with wrong key | Not in this run | Track B: S7 evidence (wrong-key bridge → FAILED health, no ODOMETRY) |
| T13 | SETUP_SIGNING over telemetry, and over USB while armed | Not in this run | Track B: S8 group 4 (`Signing change refused: armed` on SERIAL0/1/2) |
| T14 | Trailer fuzzing, 1,000 frames | **BLOCKED** | `fuzz_trailer.py` not written |
| T15 | Odometry latency signed vs unsigned | Not in this run | Pi/bench item (S7 lists it as unverified) |
| — | `far_future.py` (FC behaviour with a timestamp 1 day ahead) | **BLOCKED** | Not written |
| — | S8 SIGFAIL reaches the GCS | **PASS** | `VehicleState.fcSignatureFailures` populated for sysid 1 and 2 (none for 3, which has no key) |

The first run of the same test had 5 failures. All were test mistakes, fixed before the final run:
- T10 matched any PARAM_VALUE instead of SYSID_THISMAV.
- T6 cast the first refusal to `Rejected`, which is how finding 3 was found.
- T7 checked before the `NoKey` entry had appeared.
- T11 and the SIGFAIL row then failed only because T6 left the vault locked.

The raw final output is [gradle-run.txt](s9-evidence/gradle-run.txt).

## Findings

1. **SIGFAIL counts every frame that doesn't verify, not just attacks.** Through the shared router, each keyed SITL
   received about 125 frames/s of the other FCs' telemetry: the other drone's signed frames plus the unsigned drone's
   frames. Its SIGFAIL climbed by about 250/s, reaching 41,092 and 41,420 by the end. With one router per drone (the S7
   design), FC-to-FC cross-feed doesn't happen. But the GCS heartbeat fan-out alone still gives every keyed FC on a
   shared GCS link 2 counts per second (the other drone's heartbeat and the unsigned one). That applies to any
   multi-drone link, for example one radio network. The counter is useless as an attack signal there, and the PRD's
   T2 expectation ("SIGFAIL unchanged, unsigned is dropped, not a bad signature") contradicts S8, which documents
   counting rejected unsigned frames. Track B and the PRD need a decision. Options: count only frames whose signature
   fails for a stream that already verified with the installed key, or accept the counter as "noise on this link" and
   drop T2's SIGFAIL clause.
2. **Heartbeat fan-out reaches every FC.** A HEARTBEAT has no target, so the router forwards all three GCS copies to
   every endpoint. That is expected and harmless to command security (a frame signed for B can't verify on A), but it
   is what feeds finding 1.
3. **First send after a lock is `Failed`, not `Rejected(LOCKED)`.** Until the next heartbeat (up to 1 s) the vehicle's
   status is still `Signed`, so `FrameSink` is what refuses (`IOException`, nothing written). `Links.signingRefusals`
   only carries `Rejected`, so no "Open signing keys" toast appears for that send. This is safe. The fix is small: in
   `MavTxGateway.forVehicle`, reject with `LOCKED` when `keyring.isUnlocked()` is false before calling `write`.
4. **No visible spoof counter.** `SigningSource.counters` (spoofed, bad signature, replayed, unverified, malformed)
   isn't exposed past the byte layer, so PRD T5's "spoof counter rises" can't be checked or shown. Suggest publishing
   it in `LinkState.Connected` and showing it on the Links card.
5. **Router cross-feed in SITL.** mavlink-router forwards each FC's telemetry to the other FC endpoints. This is
   SITL-only (three FCs on one router), noted because it inflates finding 1's numbers.
6. **Spoofed frames reach other FCs.** The router forwarded the T5 spoof frames to SITLs 1 and 2 (not to SITL 0, the
   real sysid 1). They were dropped there and counted in their SIGFAIL.
7. **Not run:** the visual badge check and a second *app process* (screen access was declined this session). The badge
   text and state were checked through `SigningStatus.label`, the same function the UI draws. The 13-byte overhead
   (PRD risk "GCS link bandwidth") wasn't measured.

## Reproduce

```bash
py -3.12 tools/sitl/s9_make_keys.py --provision ../GNSS-Denied/tools/signing/provision.py --out build/s9-sitl
# in WSL Ubuntu-22.04 (paths as in this run):
python3 tools/sitl/s9_signing_sitl.py up --work build/s9-sitl --sitl ~/src/ardupilot-kft/build/sitl/bin/arducopter \
  --router ~/src/mavlink-router-s7/build/src/mavlink-routerd --kft-params ../GNSS-Denied/tools/sitl/kft_locked.parm
# on Windows once "ready:" prints (Gradle environment as in the loopback note):
KFT_S9_RUN=1 ./gradlew :core:vehicle:jvmTest --tests '*SigningSitlRun*' --rerun
```

Expected: 11 rows PASS, table also in `build/s9-sitl/results.txt`. SITL starts with `--wipe`. A rerun without restarting
the harness works, unless GCS 1 happens to draw the link_id used before T11, whose FC stream is now 1 h ahead (1 in 254).
