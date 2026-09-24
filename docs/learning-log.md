# Learning log

One entry per Claude Code pass, oldest first. Each entry follows the template in `CLAUDE.md` §8. Read them in order to see how the codebase was built and why.

---

## Pass 1 — W1-1: Repository and KMP skeleton (2026-09-24)

### What changed
- **Repo root:** `settings.gradle.kts` (module list), `build.gradle.kts` (plugin versions declared once), `gradle.properties`, `.gitignore`, `.gitattributes` (LF line endings everywhere, CRLF only for `.bat`), Gradle 9.7.1 wrapper.
- **`gradle/libs.versions.toml`:** every library version, checked against Maven Central / Google Maven on 2026-09-24 (Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.4.1, mavlink-kotlin 1.2.15, Koin 4.2.2, maplibre-compose 0.17.0, …).
- **`gradle/gradle-daemon-jvm.properties`:** tells Gradle to run on JDK 25 and download it if missing.
- **`build-logic/`:** two convention plugins. `kft.kmp.library` sets up the Android + desktop targets and test libraries. `kft.kmp.compose` adds Compose on top.
- **Modules:** `core:geo`, `core:planning`, `core:mavlink`, `core:vehicle`, `core:terrain`, `core:geo-io`, `ui:map`, `feature:{connections,fly,plan,settings}`, `app:shared`, `app:android`, `app:desktop`. Most are empty for now. Their `build.gradle.kts` files already declare the allowed dependencies.
- **`core:geo`:** `LatLon` (validated WGS-84 position, with MAVLink ×1e7 conversion) and `Geodesy` (distance, bearing, destination point), with 7 tests.
- **`app:shared`:** `App()` root composable, dark theme, and `Platform` (`expect`/`actual`) showing which platform is running.
- **`app:android` / `app:desktop`:** thin shells that host `App()`.
- **`.github/workflows/ci.yml`:** Linux job (all tests + Android APK) and a Windows job (desktop tests + runnable distribution).
- **`CLAUDE.md`, `docs/spec/`, `docs/implementation/`, this log.**

### How it works
```
settings.gradle.kts ──includeBuild──▶ build-logic (compiles kft.kmp.library / kft.kmp.compose first)
        │
        └─include──▶ :core:* :ui:* :feature:*  each applies one convention plugin
                         │
                         ▼
                   :app:shared  (KMP: commonMain App(), androidMain/jvmMain actuals)
                     ▲       ▲
      :app:android ──┘       └── :app:desktop
      (Activity.setContent)      (Window { App() })
```
One `App()` composable is compiled twice: for Android (AAR inside the APK) and for the desktop JVM. Each shell only opens a window/activity and calls `App()`.

### Engineering learnings
- **Kotlin Multiplatform source sets.** `commonMain` is compiled for every target. `androidMain` and `jvmMain` (desktop) only for their own. *Why:* survey maths, MAVLink protocols and ViewModels are written once. Only true platform edges (serial ports, file paths, Bluetooth) are written twice.
- **`expect`/`actual`** (`Platform.kt`). `commonMain` declares what it needs, and each platform supplies it. The compiler refuses to build if a target is missing its `actual`. *Why over an interface + DI:* for a tiny, static fact like the platform name, `expect`/`actual` is simpler. For things with state or lifecycle (transports), we'll use interfaces injected by Koin instead, because those can be faked in tests.
- **Convention plugins (`build-logic`).** Thirteen modules each need the same target setup. Copy-pasting it would drift. *Why an included build over `buildSrc`:* a change in an included build only recompiles the plugins, and it's the pattern Gradle and Google recommend today.
- **Version catalog.** One file owns every version, and modules refer to `libs.xxx`. *Why:* a version bump is one reviewable line, and Claude Code can't quietly introduce a second version of a library.
- **AGP 9 + KMP.** In AGP 9 an Android *application* module can't also be a KMP module. That's why the shared UI lives in `app:shared` (a KMP library using `com.android.kotlin.multiplatform.library`) and `app:android` is a thin application. It also forced **`compileSdk = 37`**, because Compose 1.12's AndroidX artifacts require it.
- **`core:geo` was split out of `core:planning`.** `LatLon` is needed by the map, vehicle and terrain modules. If it lived in `planning`, the map would depend on survey maths. A tiny pure module for shared value types keeps the dependency graph honest.
- **Validation in constructors** (`LatLon.init`). An impossible coordinate fails where it's created, not three layers later inside a mission upload.
- **mavlink-kotlin on Android.** Its artifacts publish `jvm` variants but no `android` variant. The Kotlin Gradle plugin lets an Android target consume a `jvm` variant, and the Android build resolved them successfully. This is the first proof that our MAVLink choice works in both apps.

### What to look at
1. `build-logic/src/main/kotlin/kft.kmp.library.gradle.kts:11-24`: the two targets every module gets, and why Android host tests are on.
2. `app/shared/src/commonMain/kotlin/com/kft/gcs/app/Platform.kt:9` with its two `actual`s in `androidMain` / `jvmMain`: `expect`/`actual` in its simplest form.
3. `core/geo/src/commonMain/kotlin/com/kft/gcs/core/geo/Geodesy.kt:22` and `GeodesyTest`: the testing pattern every maths function will follow (analytic reference values, round-trip checks).

### Tests
- `GeodesyTest` (7 tests, run twice: desktop JVM + Android host). Checks: 1° of latitude ≈ 111,195 m; equator→pole = πR/2 exactly; shortest path across the antimeridian; bearing east = 90°; destination/distance/bearing round-trip at 4 bearings from the SITL home; lossless ×1e7 conversion; invalid latitude rejected.
- `PlatformTest`: the `actual` name is non-blank on both platforms.
- Build checks: `./gradlew check :app:android:assembleDebug` passes. The desktop app was launched and showed "KFT GCS … Running on Linux · JVM 25".
- **Manual check for you:** `gradlew.bat :app:desktop:run` on Windows should open a dark window that says "Running on Windows 11 · JVM 25…". `gradlew.bat :app:android:installDebug` with the emulator running should show the same screen with "Android …".

### Open questions / next
- Next task: **W1-2, the map spike** (`docs/implementation/00-maps-decision.md` §4, checks M1–M8), then the Connections screen as the MVVM worked example.
- Package root is `com.kft.gcs` (assumed; `in.pavaman…` would need backticks because `in` is a Kotlin keyword).
- `targetSdk` stays at 36 while `compileSdk` is 37. We'll raise targetSdk in its own pass after checking the Android 17 behaviour changes.

---

## Pass 2 — W1-2: Map engine spike (2026-09-24)

### What changed
- **`spikes/map-spike/`** (new, throwaway KMP module): the whole spike screen in `commonMain`, so one composable runs on both platforms.
  - `SpikeApp.kt`: basemap switcher (Street / Satellite / Offline), status line, the map with an editable polygon, the fake vehicle, and the vertex-drag pointer handler. Also `configureEsriAuth()`, which adds the Esri token as a request header.
  - `Editing.kt`: pure helpers (`hitVertex`, `insertionIndex`, `polygonGeoJson`, `pointsGeoJson`), tested in `EditingTest.kt`.
  - `FakeVehicle.kt`: a `StateFlow<Position>` that circles SITL home at 10 Hz.
  - `Heap.*.kt`: `expect`/`actual` used-heap readout for the M2 memory check.
  - `jvmMain/Main.kt`: desktop entry point with the map presentation host.
  - `composeResources/files/offline.mbtiles` + `tools/make_offline_mbtiles.py`: a synthetic 258 KB raster MBTiles file and the stdlib-only script that builds it.
  - `build.gradle.kts`: runtimes, Compose resources (with Android resources switched on, see F2), desktop packaging, and the Esri key passed to `run` only.
- **`spikes/map-spike-android/`** (new, throwaway): the Android application shell. AGP 9 doesn't let an application module be a KMP module, so it has to be separate from `map-spike`.
- **`settings.gradle.kts`**: includes both spike modules.
- **`gradle/libs.versions.toml`**: adds the two runtime artifacts (`opengl-android`, `vulkan-windows-x64`, both 0.17.0) and `compose-components-resources`. No versions were bumped.
- **`docs/decisions/ADR-001-map-engine.md`** (+ two screenshots): the verdict, results for M1–M8, pinned versions, GPUs and findings.

### How it works
```
FakeVehicle (10 Hz StateFlow) ──collectAsState──▶ SpikeApp recomposes
ring: MutableState<List<Position>> ─────────────▶ rememberGeoJsonSource(JSON string) ──▶ Fill/Line/Circle layers
        ▲                                                                                 (MapLibre Native draws them)
        │ positionFromScreenLocation(dp)
pointer (px) ──toDp──▶ hitVertex(handles in dp) ──hit?──▶ consume gesture, move vertex i each frame
                                              └─miss──▶ leave the event alone ──▶ map pans/zooms as usual
```
1. `rememberMapState` owns the map. The content lambda declares sources and layers the same way Compose declares UI: when `ring` changes, the GeoJSON source gets new data. Nothing is added or removed by hand.
2. Dragging: the `pointerInput` sits on the map's own modifier, so it's the parent of both the map surface and the overlay. It listens in the **Initial** pass, which runs before the map's handlers (they use the **Main** pass). On press it projects every vertex to the screen and checks whether the finger is within 24 dp of one. If so, it consumes every event of that gesture, and the map skips consumed events, so it doesn't pan.
3. Long-press (Android) or right-click (desktop) goes through the map's own `longClick` callback. The new vertex is inserted on the nearest edge, measured in screen space.

### Engineering learnings
- **Declarative map content.** Sources and layers are composables, just like UI. *Why this matters:* section 04's `MapView` can take our own `MapOverlay` model and turn it into layers inside one composable, with no imperative add/remove bookkeeping to get wrong.
- **Pointer event passes (Initial → Main → Final).** A parent sees an event before its children in the Initial pass. *Why over disabling map gestures while dragging:* switching `MapUiOptions` in the middle of a gesture races the gesture detector. Consuming events is the documented Compose way for one handler to claim a gesture.
- **Screen space for hit tests, geo space for storage.** "Did the finger hit the handle?" depends on pixels on the glass, not metres. A 24 dp radius works the same at zoom 10 and zoom 19. Vertices are still stored as lat/lon.
- **The dp vs px boundary.** Compose pointer events are in pixels, while maplibre-compose's projection works in dp. Mixing them up is off by 2.5× at 250 % scaling on the tablet. Convert once, at the edge (`Density.toDp`).
- **Header auth over URL tokens** (`MapRequestInterceptor`). *Why:* a `?token=` in the URL ends up in logs, crash reports and the tile cache key. A header scoped to the Esri host doesn't.
- **Work around the library bug, don't hide it (F1).** Swapping the base style lost our circle layers. The workaround (one permanent base style, basemaps as raster layers on top) is also the better design for satellite + labels, and the ADR records the bug so it can be reported upstream.
- **Spike modules are throwaway on purpose.** The spike skips `MapView`, ViewModels and DI, because its job is to answer "does the engine work?", not to be the architecture. The answers go into the ADR, and section 04 builds the real thing.

**Ponytail review:** 3 findings. Applied two: dropped the empty-list GeoJSON guards (the spike can't reach them) and made `FakeVehicle`'s radius a constant. Kept one: the two identical `usedHeapMb()` actuals, because CLAUDE.md bans `java.*` in `commonMain`.

### What to look at
1. `spikes/map-spike/src/commonMain/kotlin/com/kft/gcs/spikes/mapspike/SpikeApp.kt:184` (the vertex-drag `pointerInput`): the pattern `MapView` will reuse.
2. `docs/decisions/ADR-001-map-engine.md`, section "Findings to carry into section 04": the rules the real `MapView` must follow.
3. `spikes/map-spike/build.gradle.kts:10` (`androidResources { enable = true }`): the AGP 9 trap that crashed the app on Android.

### Tests
- `EditingTest` (4 tests, desktop JVM + Android host), with independent reference values: nearest-handle choice (18 vs 12 dp), a 3-4-5 triangle just outside a 4.9 dp radius, nearest-edge insertion on a 100 dp square including the closing edge, and GeoJSON ring closure.
- `./gradlew check` passes.
- **Manual (done):** desktop `:spikes:map-spike:run` (Claude + Hrushikesh), packaged `KFT-MapSpike.exe` on the laptop and on a second PC, and emulator `Medium_Tablet` (Android 15) drag, long-press, and offline in airplane mode.
- **Manual (open):** on the tablet, run `gradlew.bat :spikes:map-spike-android:installDebug`. Expected: the map renders, a handle follows your finger without the map panning, long-pressing an edge adds a vertex there, and **Offline** shows purple tiles in airplane mode.

### Open questions / next
- M5 on the tablet, M4 (Esri key) and M8 (KCEF, deferred to GS-3) are open. See the ADR's "Still open" list.
- `ponytail:` in `SpikeApp.kt`: the Esri style name `arcgis/imagery` is unverified, and the Mapbox alternate isn't wired. Both depend on getting keys.
- Report F1 (circle layers dropped after a base-style swap) to maplibre-compose with a minimal repro.
- Suggestion for a later pass: move `androidResources { enable = true }` into the `kft.kmp.compose` convention plugin (F2).
- Dev environment: Gradle on this laptop needs `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Hrushikesh` (ADR-001, dev-environment note).
- Next task: the Connections screen (MVVM worked example), then section 04 `MapView` on top of the ADR-001 findings.

---

## Pass 3 — W1-3: `core:mavlink` (transports, TX gateway, connection manager) (2026-09-24)

### What changed
- **`core/mavlink/build.gradle.kts`**: a shared `jvmCommon` source set (Android + desktop) for socket code, plus okio and Koin.
- **`MavTransport.kt`**: the `internal` byte-pipe interface, and `TransportMavConnection`, which puts mavlink-kotlin's framing on top of it.
- **`jvmCommonMain/SocketTransports.kt`**: `UdpTransport` (listen and client) and `TcpTransport`, written once for both platforms.
- **`TxPolicy.kt`**: the allowlist as pure functions. It sorts each message into a row of the pod-contract §3 table, then checks that row's rule against `PodStatus`.
- **`MavTxGateway.kt`**: the only `send`. It classifies, checks, and then writes or rejects, and publishes every rejection.
- **`LinkConfig.kt`**: UDP listen, UDP client and TCP, as plain data that can be saved in a profile.
- **`LinkStats.kt`**: message rate and loss % from MAVLink sequence gaps.
- **`ConnectionManager.kt`**: connect/disconnect, auto-reconnect (1, 2, 4, 5 s), 1 Hz GCS heartbeat, vehicle detection, 3 s heartbeat timeout, and a `frames` flow for `core:vehicle`.
- **`di/MavlinkModule.kt`**: Koin bindings and the `IoDispatcher` qualifier.
- **`kft.kmp.library` convention plugin**: test source sets opt in to the virtual-time coroutine APIs.
- **`gradle/libs.versions.toml`**: `okio` 3.17.0, the same version mavlink-kotlin already pulls in. No version bumps.
- **Tests**: `MavTxGatewayTest`, `LinkStatsCounterTest`, `ConnectionManagerTest`, `FakeMavConnection` (commonTest), and `SocketTransportsTest` (jvmTest).

### How it works
```
                     ConnectionManager (app scope)
LinkConfig --connect--> runLink: loop { open -> session -> fail -> backoff }
                              |
                 createTransport(config)  [internal]
                              v
 socket <-bytes-> MavTransport -> TransportMavConnection (framing, CRC) -> CoroutinesMavConnection
                                                                              |            ^
                                                           mavFrame (reader)  |            | sendUnsignedV2
                                                                              v            |
                  frames: SharedFlow <-- session collector (stats, heartbeat)      MavTxGateway.send(msg)
                  state: StateFlow<LinkState>                                       | classify -> check(pod)
                                                                                    +- Rejected -> rejections
```
1. `connect(config)` launches `runLink` in the app scope. Each pass opens a connection, runs a session, and on failure shows "Connecting (attempt n)" and waits 1, 2, 4, then 5 s.
2. A session hands the connection to the gateway, then runs two coroutines: the GCS heartbeat sender, and a single collector that handles both incoming frames and the 1 s tick (stats, vehicle timeout).
3. When the stream fails or `disconnect()` cancels, the `finally` block detaches the gateway and closes the connection. Closing is what unblocks the reader thread stuck in a socket read.

### Engineering learnings
- **The compiler enforces the TX rule.** `MavTransport` and `MavTxGateway.attach` are `internal`, so code outside `core:mavlink` can't reach anything that writes. *Why over a code review rule:* a rule can be forgotten, but an `internal` modifier can't be bypassed without editing this module.
- **Default-deny allowlist.** Messages that aren't listed are rejected. *Why:* mavlink-kotlin knows hundreds of messages. With deny-by-default, a new one needs a deliberate line and a test, never an accident.
- **Mode numbers depend on the firmware.** GUIDED is 4 on Copter and 15 on Plane, so the policy needs the vehicle kind from the heartbeat. Before the first heartbeat, mode changes are refused rather than guessed.
- **RC override counts as "never".** AI-enable and target-lock are RC channels. A GCS `RC_CHANNELS_OVERRIDE` could raise them, which would be engagement over the GCS (pod invariant 4).
- **One collector for shared state.** Frames and ticks are merged into one flow, so `stats`/`vehicle` are only touched by one coroutine at a time. *Why over a Mutex:* nothing to lock or forget, and the order of events is explicit.
- **Closing unblocks blocking I/O.** Cancelling a coroutine doesn't interrupt a thread blocked in `socket.receive()`, but closing the socket does. That's why the `finally` closes before `coroutineScope` waits for its children.
- **Custom KMP hierarchy (`jvmCommon`).** java.net is the same on Android and desktop, so a shared intermediate source set avoids copying socket code. `applyDefaultHierarchyTemplate { group("jvmCommon") { … } }` keeps the default template, so no hierarchy warning.
- **The test seam is the library's own interface.** Tests fake `CoroutinesMavConnection`, which mavlink-kotlin already defines, so the state machine runs on virtual time without sockets. The real sockets get their own small JVM loopback test.

**Ponytail review:** no cuts. `UdpClient` is P0 in the spec (UDP client and listen), and `rejections` is required by the "rejected and logged" rule.

### Safety
- New `MavTxGateway` with the full pod-contract §3 table from day one:
  - **Always:** heartbeat, param reads, mission download, stream requests.
  - **Operator:** arm, takeoff, mission start, speed, pause, param set, other modes.
  - **Mission change:** blocked while the pod is LOCKED, TERMINAL or ENGAGE.
  - **Safe direction:** RTL, LOITER, LAND, BRAKE on Copter; RTL, LOITER, QLAND, QRTL on Plane; plus `NAV_RETURN_TO_LAUNCH` and `NAV_LAND`. Always allowed.
  - **Enter GUIDED:** blocked while pod AI-enable is high.
  - **Never:** `SET_POSITION_TARGET_*`, `SET_ATTITUDE_TARGET`, `DO_REPOSITION`, `RC_CHANNELS_OVERRIDE`, `MANUAL_CONTROL`, `DO_SET_SERVO`, `DO_SET_RELAY`.
  - **Everything else:** rejected as unlisted.
- Each row has a test in `MavTxGatewayTest`. The pod status is fixed at `NoPod` in DI until the pod link exists.

### What to look at
1. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/TxPolicy.kt:58`: the allowlist, one row per line.
2. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/MavTxGateway.kt:58`: `send`, which classifies, checks, then writes.
3. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/ConnectionManager.kt:115`: the reconnect loop and why closing happens in `finally`.

### Tests
- `MavTxGatewayTest` (9): each §3 row, Copter vs Plane GUIDED numbers, unknown vehicle refused, rejections published and not written, pod state read at send time.
- `LinkStatsCounterTest` (3): hand-calculated loss across the 255 -> 0 wrap (2 lost of 6 = 33.3 %), senders tracked separately, per-second rate window.
- `ConnectionManagerTest` (6, virtual time): connected + 1 Hz GCS heartbeat + vehicle detection; vehicle dropped after the 3 s timeout; reconnect after failure with 1 s then 2 s backoff; disconnect closes and stops retrying; backoff 1, 2, 4, 5, 5 s; MAV_TYPE -> Copter/Plane (QuadPlane = Plane).
- `SocketTransportsTest` (2, JVM, real localhost sockets): UDP listen replies to the sender's address; TCP carries frames both ways.
- `./gradlew check` passes.
- **Manual SITL check:** comes with the Connections screen in Pass 4.

### Open questions / next
- Serial (jSerialComm on desktop, usb-serial-for-android) is its own pass. Android USB needs a Context and permission flow.
- MAVLink signing isn't used (ArduPilot allows unsigned by default). Revisit before field use.
- Next: **Pass 4, the Connections screen** (MVVM worked example: UiState, ViewModel, profiles repository, Koin, navigation).
