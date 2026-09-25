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

---

## Pass 4 — W1-4: Connections screen (MVVM worked example) (2026-09-24)

### What changed
- **`feature/connections/ConnectionsRepository.kt`**: `ConnectionProfile`, the `ConnectionsRepository` interface, and `DefaultConnectionsRepository`. It holds in-memory profiles (two SITL defaults) and delegates the link to `ConnectionManager`.
- **`ConnectionsUiState.kt`**: the screen's one immutable state and the pure functions that build it (`buildUiState`, `toStatusUi`, `describe`, `toConfigOrError`).
- **`ConnectionsViewModel.kt`**: `state: StateFlow<ConnectionsUiState>`, `onXxx` event functions, and one-shot `effects` ("Vehicle found", "Vehicle heartbeat lost").
- **`ConnectionsScreen.kt`**: `ConnectionsRoute` (Koin, state collection, snackbar) and the stateless `ConnectionsScreen`. It uses two columns on wide screens and one on phones.
- **`di/ConnectionsModule.kt`**: repository singleton and `viewModelOf(::ConnectionsViewModel)`.
- **`app/shared`**:
  - `di/AppModule.kt`: the app `CoroutineScope`, `Dispatchers.IO`, and the list of all modules.
  - `App.kt`: `KoinApplication`, a navigation rail and `NavHost`.
  - `build.gradle.kts`: navigation, lifecycle and `core:mavlink`.
- **Tests**: `ConnectionsViewModelTest` (7).

### How it works
```
ConnectionsScreen ──onConnect(id)──▶ ConnectionsViewModel ──connect(id)──▶ ConnectionsRepository ──▶ ConnectionManager
       ▲                                   │ combine(profiles, linkState, form)                              │
       └──── StateFlow<ConnectionsUiState> ◀┘ ◀──────────────── StateFlow<LinkState> ◀────────────────────────┘
       └──── effects (snackbar) ◀── Channel ◀── "vehicle appeared/disappeared" watcher
```
1. The route gets the ViewModel from Koin and collects `state` with `collectAsStateWithLifecycle`, which stops collecting when the window is hidden.
2. `state` is `combine(profiles, linkState, form)` passed through the pure `buildUiState`, so every link change redraws the status card and the "Active" profile.
3. The form text lives in the ViewModel, because it belongs to this screen only. Profiles and the link live in the repository and `ConnectionManager`, because they must outlive the screen: the link stays up when you go to the Fly view.

### Engineering learnings
- **One `UiState`, built by a pure function.** `buildUiState` has no coroutines, so the "what does the screen show" logic is tested with plain function calls. *Why over computing in the composable:* composables can't be unit-tested cheaply, but functions can.
- **`stateIn(WhileSubscribed(5_000))`.** The upstream stops 5 s after the last collector leaves, so a rotation or quick tab switch doesn't restart it, but a closed screen stops work. *Why not `Eagerly`:* it would keep combining flows for a screen nobody sees.
- **Effects as a `Channel`, not state.** "Show a snackbar once" isn't something to redraw after rotation. If it were state, the message would reappear on every recomposition or need manual clearing. `receiveAsFlow()` delivers each effect to exactly one collector.
- **Repository interface on purpose.** The ViewModel test uses `FakeConnectionsRepository` and sets `linkState` by hand. No sockets, no Koin, no time.
- **`Dispatchers.setMain` in tests.** `viewModelScope` runs on Main, which doesn't exist in unit tests, so the test points it at a `StandardTestDispatcher`. StateFlow conflation is real: the effect test runs the dispatcher between steps, exactly as spaced-out real events would be.
- **Navigation lives in `app:shared`.** Features expose a `XRoute()` composable and nothing else, so `feature:connections` can't import `feature:fly` (CLAUDE.md §2). String routes are enough for now, and type-safe routes would add the serialization plugin for no gain yet.
- **`KoinApplication(configuration = koinConfiguration { … })`** is the non-deprecated Koin 4.2 entry point.

**Ponytail review:** no cuts. The two-column layout is spec P0 (large screen + phone fallback), and in-memory profiles are marked `ponytail:` until the storage pass.

### What to look at
1. `feature/connections/src/commonMain/kotlin/com/kft/gcs/feature/connections/ConnectionsViewModel.kt:36`: the whole state pipeline in one expression.
2. `feature/connections/src/commonMain/kotlin/com/kft/gcs/feature/connections/ConnectionsUiState.kt:45`: `buildUiState`, domain in and screen out.
3. `feature/connections/src/commonMain/kotlin/com/kft/gcs/feature/connections/ConnectionsScreen.kt:44`: the Route/Screen split.

### Tests
- `ConnectionsViewModelTest` (7, JVM + Android host):
  - link idle → connecting → vehicle found, with headline, detail and tone;
  - clicks forwarded to the repository;
  - saving a valid form (a kind change fills its default port; a blank name falls back to the summary);
  - an invalid port shows an error and saves nothing;
  - found/lost effects fire once each, and arming alone doesn't re-fire;
  - the reconnect warning text;
  - host validation.
- **SITL, done on the dev laptop** with Mission Planner's bundled ArduCopter SITL: `ArduCopter.exe --model quad --home -35.363261,149.165230,584,353 --defaults <MP>\sitl\default_params\copter.parm -I0` (TCP 5760), then `gradlew.bat :app:desktop:run`.
  - Connect on "SITL (TCP 5760)" → "ArduCopter · system 1 · disarmed", 0.0 % loss.
  - Killing SITL → "Reconnecting to TCP 127.0.0.1:5760 (attempt 4) / Connection refused".
  - Restarting SITL → reconnected by itself.
  - Disconnect → "Not connected".
- **Android emulator, for you:** UDP listen inside the emulator needs a forward from the host: `adb emu redir add udp:14550:14550`. TCP to SITL on the host uses host `10.0.2.2`, port 5760.

### Open questions / next
- `ponytail:` profiles aren't persisted yet. This needs a small storage pass: JSON files in the platform app-data folder, `expect`/`actual` for the path.
- The rail uses letter icons. Material icons need a dependency, and that choice can wait for the UI polish pass.
- Next: **Pass 5, vehicle on the map**: telemetry model in `core:vehicle` (position, attitude, GPS, battery, mode), the first `MapView` in `ui:map` following ADR-001, and a Fly screen. That's the week-1 exit check.

---

## Pass 5 — W1-5: Vehicle on the map (telemetry, `MapView`, Fly view) (2026-09-24)

### What changed
- **`core/vehicle`**:
  - `VehicleState.kt`: `VehicleState` in plain units, plus a pure `reduce(message)` for GLOBAL_POSITION_INT, VFR_HUD, ATTITUDE, GPS_RAW_INT, SYS_STATUS and STATUSTEXT, and `flightModeName`.
  - `VehicleRepository.kt`: folds the autopilot's frames into one `StateFlow<VehicleState>` and requests telemetry streams whenever a vehicle appears.
  - `di/VehicleModule.kt`, and Koin in `build.gradle.kts`.
- **`ui/map`**:
  - `MapModel.kt`: `TileSourceConfig` + `TileSources` (OpenFreeMap Street, Esri World Imagery), `MapOverlay` (Vehicle, Track), `CameraRequest`, and `expect fun esriApiKey()`.
  - `MapView.kt`: the one map composable. It follows ADR-001: one permanent base style, raster basemaps on top, Esri token added at request time, and a heading arrow drawn as a `SymbolLayer`.
  - `jvmMain`: `DesktopMapHost.kt` (GPU host for a window) and the env-based Esri key. `androidMain`: no key yet.
  - `build.gradle.kts`: maplibre-compose as `implementation` (not `api`), plus the per-platform runtimes.
- **`feature/fly`**: `FlyUiState.kt` (pure HUD formatting and track spacing), `FlyViewModel.kt`, `FlyScreen.kt` (Route + stateless Screen) and `di/FlyModule.kt`.
- **`app/shared`**:
  - Fly is now the start destination.
  - `vehicleModule` and `flyModule` are registered.
  - Safe-drawing insets are applied (ADR-001 F3).
  - `jvmMain/DesktopApp.kt` wraps `App()` in the desktop map host.
- **`app/desktop`**:
  - JVM 25 bytecode: the MapLibre Windows runtime is published as "JVM 25+".
  - `run` gets `ESRI_API_KEY` from `local.properties`.
  - `Main.kt` calls `DesktopApp()`.
- **`app/android`**: debug builds get `applicationIdSuffix = ".dev"` (see Open questions).
- **`.gitignore`**: `*.tlog.raw` (MAVProxy's raw log).

### How it works
```
ConnectionManager.frames ──┐
ConnectionManager.state ───┼─▶ VehicleRepository (one collector) ──reduce──▶ StateFlow<VehicleState>
   (vehicle appears) ──────┘        └─ REQUEST_DATA_STREAM(ALL, 4 Hz) ─▶ MavTxGateway
                                                                              │
FlyScreen ◀── StateFlow<FlyUiState> ◀── FlyViewModel: combine(VehicleState, local{basemap, track, camera})
   └─ MapView(basemap, overlays = [Track, Vehicle], cameraRequest)  ──▶ maplibre-compose layers
```
1. When a heartbeat appears, the repository sends one REQUEST_DATA_STREAM through the gateway. ArduPilot then streams position, attitude, GPS, battery and messages at 4 Hz, and the same happens again after every reconnect.
2. Only frames from the autopilot's own system/component id are reduced, so a gimbal's ATTITUDE can't overwrite the aircraft's.
3. The Fly ViewModel keeps the track (1 m spacing, 2 000 points max) and auto-centres once on the first position. After that the camera is yours; "Centre" sends a new `CameraRequest`.
4. `MapView` turns `MapOverlay`s into GeoJSON sources and layers. Features never see a MapLibre type.

### Engineering learnings
- **Reducer + repository split.** `reduce` is a pure function (message in, state out), so every unit conversion has a hand-calculated test. The repository only does plumbing. *Why:* the maths is where the bugs hide (mm vs m, cdeg, radians, sentinels), and it's cheapest to test without coroutines.
- **"Unknown" is not zero.** MAVLink uses sentinels (0,0 position, `hdg = 65535`, `-1 %` battery). They become `null` and show as "–". A "0 %" battery on a real tablet would be a false alarm, and an unknown heading keeps the last known one.
- **Dependencies as flows + a typed send function.** `VehicleRepository` takes `frames`, `link` and `suspend (RequestDataStream) -> TxResult`, not the `ConnectionManager`. *Why:* the manager can't be faked outside `core:mavlink` (its test constructor is internal), and the lambda type allows exactly one message, still routed through the gateway in the app.
- **`implementation` vs `api` for the map engine.** `ui:map` hides maplibre-compose, so a feature importing it doesn't compile. That's the compiler enforcing "no map-library imports outside `ui:map`".
- **`@UiComposable` on `MapView`.** The Compose compiler infers which "applier" a composable targets from what it calls. The MapLibre layer calls inside made it guess wrong and warn at every call site, so the annotation states the intent.
- **Gradle JVM-version attributes.** A library published as "JVM 25+" is refused by a module compiling for 21. KMP modules don't send that attribute, which is why the spike never hit it.
- **Pure helpers for the UI too.** `hudItems` and `appendTrack` are plain functions, so "359.6° shows as 0°" and "a 0.56 m move is ignored" are one-line tests.

**Ponytail review:** no cuts. Marked `ponytail:` in code: the Windows-only desktop runtime, no Esri key on Android, and the fixed 20 % battery warning.

### Safety
- The only new transmission is REQUEST_DATA_STREAM. It was already **Always allowed** in the Pass 3 allowlist (read-only stream request) and goes through `MavTxGateway`. The allowlist is unchanged.

### What to look at
1. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/VehicleRepository.kt:41`: link and frames in one collector, and the stream request.
2. `ui/map/src/commonMain/kotlin/com/kft/gcs/ui/map/MapView.kt:51`: the abstraction boundary and the ADR-001 rules in code.
3. `feature/fly/src/commonMain/kotlin/com/kft/gcs/feature/fly/FlyViewModel.kt:40`: how screen-only state (track, camera) combines with vehicle state.

### Tests
- `VehicleStateTest` (6): SITL home ×1e7 → degrees; mm → m; cdeg → deg; sentinels → null; π/6 rad = 30°; RTK fixed vs STATIC; 12 600 mV = 12.6 V; STATUSTEXT severity; mode names (Copter 5 = Loiter, 2 = Alt Hold, Plane 10 = Auto).
- `VehicleRepositoryTest` (4): one stream request per vehicle appearance (and again after a heartbeat gap); mode/armed/connected from the heartbeat; a gimbal's frames ignored; disconnect clears the state.
- `GeoJsonTest` (1): GeoJSON is `[lon, lat]`.
- `FlyViewModelTest` (5): first position centres once, later moves don't, "Centre" issues a new request; track grows and clears; basemap selection; HUD formatting (dashes, units, 359.6° → 0°, battery warning below 20 %); track spacing (0.56 m ignored, 111 m added, cap drops the oldest).
- `./gradlew check` passes; `:app:android:assembleDebug` builds.
- **SITL end-to-end, done on the dev laptop.** Mission Planner's ArduCopter SITL, with MAVProxy on tcp:5760 forwarding `--out udp:127.0.0.1:14550`, and the app on the "SITL (UDP 14550)" profile:
  - The vehicle arrow appears at CMAC and the camera auto-centres.
  - The HUD shows Stabilize / Disarmed / 0.0 m / 356° / RTK fixed · 10 sats / 12.6 V · 100 %, plus status texts.
  - A second MAVProxy on tcp:5762 sent GUIDED, arm, takeoff 40, then CIRCLE. The HUD showed Circle / ARMED / 27.5 m / 3.4 m/s; the arrow rotated with the heading and the orange track followed.
  - Satellite (Esri) rendered under the overlays with its attribution.
  - (SITL then reported "Hit ground". Copter's CIRCLE takes altitude from the RC throttle, which SITL holds low. That's SITL behaviour, and the app showed it correctly.)
  - MAVProxy tip: on Windows it needs a real console window, and `--cmd` runs before the link is up, so give it the commands after it connects.
- **Android emulator (Medium_Tablet, Android 15):** the Fly view renders on OpenGL, the UI clears the status bar, and only Street is offered (no key on Android).

### Open questions / next
- **Android application id.** Your existing KFT GCS v1.3.4 already uses `com.kft.gcs`, so a same-id build can't install next to it. Debug is now `com.kft.gcs.dev`. The release id is your call: keep `com.kft.gcs` and replace the old app, or pick a new id.
- `ponytail:` Esri on Android waits for the key-distribution decision (GS-1). The desktop runtime is Windows x64 only. The battery warning is a fixed 20 %.
- The vehicle arrow's heading is a `const` rebuilt at 4 Hz. That's fine at this rate; a data-driven `iconRotate` would be the upgrade if we draw many vehicles.
- **Next: Pass 6, the command protocol.** COMMAND_LONG with ACK/retry/timeout as a tested state machine, then the Fly actions: arm/disarm, takeoff, mode change, RTL, land. After that comes mission upload/download (week 2–3 exit: arm → takeoff → mission → RTL in SITL).

---

## Pass 5.1 — W1-5 follow-up: map crash on navigation, Android app id (2026-09-25)

### What changed
- `app/shared/.../App.kt`: the Fly screen is composed once, under the `NavHost`, and never disposed. The Fly route is now empty, and Links draws on an opaque `Surface` over the map.
- `app/android/build.gradle.kts`: `applicationId = "com.kft.survey"`, and debug keeps the `.dev` suffix (`com.kft.survey.dev`). The existing `com.kft.gcs` app is untouched.
- `docs/decisions/ADR-001-map-engine.md`: new "GS-2 follow-up" section (finding F10 and the fix).

### How it works
```
Row ─ AppRail
    └ Box ─ FlyRoute()            ← always composed; owns the one MapView / MapLibre session
          └ NavHost (on top)
              ├ "fly"         → {}                        (draws nothing, so Fly shows through)
              └ "connections" → Surface { ConnectionsRoute() }  (opaque: hides the map, blocks its clicks)
```
Before this pass, the NavHost swapped Fly out when you left it. That disposed the `MaplibreMap`, so MapLibre closed its render session, and coming back created a new one. On desktop, the second teardown corrupts the native heap (`0xC0000374`). Now navigation only changes what draws on top, and the map session lives as long as the window.

### Engineering learnings
- **Composition lifetime is resource lifetime.** In Compose, "leaving the screen" means leaving composition, and that disposes everything the screen `remember`ed, native GPU sessions included. *Why hoist rather than dispose cleanly:* clean disposal depends on getting maplibre-compose's internal teardown order right (a library bug we can't fix from outside). Never disposing takes the whole failure path away, and it also keeps camera, tiles and track when you come back.
- **Z-order instead of routing.** Siblings in a `Box` stack in declaration order. Compose passes a pointer event to the layer below when the top layer has no input handler at that point, so the empty Fly route lets the map receive drags. M3 `Surface` deliberately blocks clicks, so Links can't pan the map through itself.
- **Trade-off:** the hidden map keeps receiving telemetry and may redraw at 4 Hz while you're on Links. That's cheap for one vehicle, and it's the price of never disposing.
- **ViewModel scope moved.** `FlyRoute()` now runs outside the NavHost, so `koinViewModel()` uses the window's/activity's `ViewModelStoreOwner` instead of a back-stack entry. The track and camera already survived tab switches (saveState); now they're simply never torn down.

**Ponytail review:** one cut, a single-use `Covering {}` helper inlined into the `NavHost`. Nothing else to remove.

### What to look at
1. `app/shared/src/commonMain/kotlin/com/kft/gcs/app/App.kt:43`: the hoist, and why.
2. `docs/decisions/ADR-001-map-engine.md`, "GS-2 follow-up": the finding, the reproduction, and the rule for the Plan screen.

### Tests
- `./gradlew check` passes. `:app:android:assembleDebug` / `assembleRelease` produce `com.kft.survey.dev` / `com.kft.survey` (from `output-metadata.json`).
- **No new unit test.** The bug is in native rendering and needs a GPU window to show up. The existing tests don't create a MapLibre window, and a headless Compose test wouldn't reproduce it. The manual repro below is the proof.
- **Manual, dev laptop (Vulkan, RTX 4050):**
  - *Before the fix:* Fly→Links→Fly→Links → process died with `0xC0000374`, after `Host surface lost` in the log. Reproduced.
  - *After the fix:* 24 Fly↔Links switches, then panned the map: no crash, `Host surface lost` count 0, one map runtime created per run.
  - *SITL (ArduCopter.exe from Mission Planner's `sitl` folder, TCP 5760, no MAVProxy):* Fly→Links→Connect "SITL (TCP 5760)"→Fly, where the arrow at CMAC and the HUD were live (Stabilize, 12.6 V, RTK fixed · 10 sats). Then 10 more switches while telemetry streamed, and zoomed: no crash. Closing the window exited cleanly (exit 0).
- **Not checked:** the Android emulator. The same layering applies there; the MapLibre surface is covered by a Compose `Surface`, which should be fine, but it's untested.

### Open questions / next
- Report the dispose/recreate crash upstream to maplibre-compose, with a minimal repro.
- **Plan screen (W2):** it must reuse the one hoisted map (for example, the map layer takes overlays from whichever tab is active), not create a second `MapView`. That design goes in the Plan pass.
- **Next: Pass 6, the command protocol.** Waiting for Hrushikesh's go-ahead.

---

## Pass 6 — W2-1: Scope v3 (S9–S11) and the command protocol (non-flight) (2026-09-25)

### What changed
- **`docs/spec/01_survey_gcs_feature_spec.md`** → v3:
  - S9 (no flight actions from the GCS), S10 (check every message against the dialect XML and ArduPilot's source), S11 (mission seq 0 is home), and open item GS-7.
  - §2.2 command and mission rows updated. §2.5 actions row removed. Mission progress raised to P0.
  - The new week 2–3 exit check.
- **`CLAUDE.md` §4**: S9, S10 and S11 as rules. The old "safe-direction modes are always allowed" line is gone, because S9 is stricter.
- **`core/mavlink`**:
  - `TxPolicy.kt`: new `PILOT_ONLY` category. It replaces `OPERATOR` for flight commands and the `SAFE_DIRECTION` / `ENTER_GUIDED` mode rules. The mode-number tables and the vehicle-kind lookup are deleted. KDoc lists the dialect of every allowed message.
  - `MavTxGateway.kt`: a `MavSender` interface, which the gateway implements. The gateway no longer needs the vehicle kind.
  - `ConnectionManager.kt`: builds the gateway with the pod status only.
- **`core/vehicle`**:
  - `CommandProtocol.kt` (new): COMMAND_LONG / COMMAND_INT → COMMAND_ACK matching, retry with `confirmation`, timeout, and IN_PROGRESS / DENIED / UNSUPPORTED handling.
  - `VehicleRepository.kt`: on every vehicle appearance it sends REQUEST_DATA_STREAM, then REQUEST_MESSAGE(AUTOPILOT_VERSION), REQUEST_MESSAGE(HOME_POSITION) and SET_MESSAGE_INTERVAL(HOME_POSITION, 10 s). It takes a `MavSender` instead of a one-message lambda.
  - `VehicleState.kt`: `home` (from HOME_POSITION) and `firmwareVersion` (from AUTOPILOT_VERSION).
  - Tests: `FakeFc.kt` (a scripted flight controller), `CommandProtocolTest.kt`, new cases in `VehicleRepositoryTest` / `VehicleStateTest`, and `jvmTest/SitlCheck.kt` (a real-SITL check, skipped unless `KFT_SITL` is set).
- **`feature/fly`**:
  - Shows "ArduPilot 4.6.3" under the HUD title.
  - The KDoc and the module comment now say "monitoring only (S9)".

### How it works
```
vehicle appears (LinkState) ──▶ VehicleRepository ──launch──▶ requestStartupMessages()
                                                                 │ REQUEST_DATA_STREAM (no ACK in ArduPilot)
                                                                 │ CommandProtocol.send(REQUEST_MESSAGE 148)
                                                                 │ CommandProtocol.send(REQUEST_MESSAGE 242)
                                                                 └ CommandProtocol.send(SET_MESSAGE_INTERVAL 242, 10 s)
CommandProtocol.send(cmd):
   subscribe to frames (before sending) ─▶ send attempt 0 ─▶ wait 1.5 s for COMMAND_ACK(cmd, from target, to us)
        no ACK ─▶ resend with confirmation+1 (max 3 attempts) ─▶ NoAck
        IN_PROGRESS ─▶ stop resending, wait up to 30 s for the final ACK
        ACCEPTED ─▶ Accepted      DENIED / UNSUPPORTED / … ─▶ Refused(result), never retried
        gateway refused / no link ─▶ NotSent(tx), never retried
HOME_POSITION / AUTOPILOT_VERSION frames ──reduce──▶ VehicleState.home / firmwareVersion
```

### Engineering learnings
- **Subscribe before you send.** The ACK listener starts with `CoroutineStart.UNDISPATCHED`, so it is already collecting the shared frame flow before the first byte leaves. *Why:* a SharedFlow doesn't replay, so an ACK that arrives before collection starts is lost. A fast fake FC shows this at once; a fast link would too.
- **Startup requests run in their own coroutine.** They suspend while waiting for ACKs, and the ACKs arrive on the same frames the repository's collector reads. If the collector made the requests itself, it would stop reading the frames it is waiting for. The job is cancelled when the vehicle disappears.
- **Default-deny plus one blunt category.** Under S9 the mode-number logic isn't needed (GUIDED is 4 on Copter but 15 on Plane, and so on), because every mode change is refused whatever the number. *Why delete it rather than keep it "for the pod":* the stricter rule already satisfies both pod rows. Git keeps the old code in case GS-7 ever reverses S9.
- **`MavSender` interface.** `MavTxGateway`'s constructor is internal, so `core:vehicle` tests couldn't build one. A one-method interface lets `FakeFc` stand in for the gateway and the vehicle behind it. The app still passes the real gateway, so the allowlist still applies to everything.
- **Mutex, not a per-command map.** MAVLink matches ACKs by command id only. Sending one command at a time removes the ambiguity. With a few commands per connect it costs nothing and needs no bookkeeping.
- **ArduPilot facts (S10)**, from `ardupilot/libraries/GCS_MAVLink/GCS_Common.cpp` (master 2026-09):
  - Every COMMAND_LONG/INT gets exactly one ACK, addressed back to the sender's sysid/compid.
  - COMMAND_LONG is converted to COMMAND_INT internally. A build without COMMAND_LONG answers `COMMAND_INT_ONLY`.
  - SET_MESSAGE_INTERVAL DENIES a nonzero param3 and clamps the interval to 1 ms–60 s.
  - HOME_POSITION is in no stream group. It is sent on request, on change (AP_AHRS `set_home`), or at an interval we set.
  - AUTOPILOT_VERSION lives in `standard.xml`, not `common.xml`.
  - REQUEST_DATA_STREAM is deprecated in `common.xml`, but ArduPilot maps it to its SRx groups and never ACKs it.

**Ponytail review:** one cut. Nobody ever set `CommandProtocol`'s three timing constructor parameters, so they're constants now. Kept:
- `send(CommandInt)`: you asked for COMMAND_INT, though nothing calls it yet.
- The `MavSender` testing seam.
- All tests.

### Safety
- **Allowlist change (S9).** These are now rejected as immediate `COMMAND_LONG`/`COMMAND_INT` in every pod state, with the reason "…spec S9": `COMPONENT_ARM_DISARM`, `NAV_TAKEOFF`, `DO_SET_MODE` (any mode), `NAV_RETURN_TO_LAUNCH` and `NAV_LAND`. `SET_MODE` is also rejected with any mode number, including GUIDED, RTL, LOITER, LAND and BRAKE.
- **Beyond the list you gave**, so yours to reverse: `MISSION_START` (it switches ArduPilot to AUTO), `DO_CHANGE_SPEED` and `DO_PAUSE_CONTINUE` (they change what a flying aircraft does) are `PILOT_ONLY` too.
- **Still allowed as mission items.** `MISSION_ITEM_INT` stays `MISSION_CHANGE` whatever command it carries, so TAKEOFF, LAND, RTL and DO_CHANGE_SPEED can still be in a mission (`flightCommandsAreAllowedAsMissionItems`).
- **Pod contract §3** *permits* operator commands and safe-direction modes but doesn't *require* them. S9 is stricter and compatible, so I didn't stop. The mismatch is recorded as **GS-7**: the counter-uav table should say the GCS won't revoke GUIDED, so the pod side doesn't count on it.
- **`MISSION_SET_CURRENT`** is unchanged (`MISSION_CHANGE`) and unused. It can redirect an aircraft flying AUTO, so it is arguably a flight action. Worth deciding before anyone uses it.
- **New transmissions:** REQUEST_MESSAGE and SET_MESSAGE_INTERVAL. Both were already `ALWAYS` (read-only).

### What to look at
1. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/TxPolicy.kt:122`: the command table, with S9 in one block.
2. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/CommandProtocol.kt:102`: the retry and IN_PROGRESS loop.
3. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/VehicleRepository.kt:84`: what's asked for on connect, and why.

### Tests
- `MavTxGatewayTest` (9):
  - `flightActionsAreRejectedAsImmediateCommands`: 8 commands × LONG/INT, plus 7 modes via SET_MODE and DO_SET_MODE, with no pod and with the worst-case pod.
  - `flightCommandsAreAllowedAsMissionItems`.
  - The existing row tests, updated.
- `CommandProtocolTest` (10, virtual time):
  - Accepted on the first try, with no waiting.
  - Lost once: resent with confirmation 1 after exactly 1.5 s.
  - No ACK: 3 attempts, 4.5 s.
  - DENIED, UNSUPPORTED and TEMPORARILY_REJECTED are final: 1 send each.
  - IN_PROGRESS, then ACCEPTED 5 s later: Accepted, with no resend.
  - IN_PROGRESS forever: NoAck at 30 s.
  - ACKs for another command, GCS or vehicle are ignored.
  - An old-firmware ACK with zero target fields still matches.
  - A gateway refusal gives `NotSent` with no wait.
  - COMMAND_INT resends are identical.
- `VehicleRepositoryTest` (+2): the exact connect sequence (ids 148 / 242, 10 000 000 µs), and HOME_POSITION / AUTOPILOT_VERSION reaching the state.
- `VehicleStateTest` (+2):
  - 584 000 mm → 584 m.
  - `0x040603FF` → "4.6.3", `0x04070000` → "4.7.0-dev", `0xC0` → rc, `0x80` → beta.
- **SITL, done:** Mission Planner's ArduCopter SITL on TCP 5760, then `KFT_SITL=127.0.0.1:5760 gradlew :core:vehicle:jvmTest --tests '*SitlCheck*'`. Result: `SITL: ArduPilot 4.8.0-dev, home (-35.363261, 149.1652299) 584.09 m`, through the real TCP transport and gateway.
- `./gradlew check` passes. No compiler warnings in the touched modules.

### Open questions / next
- **KFT firmware GCS authentication (found while checking S10).**
  - `ardupilotKFT` (ArduCopter 4.6.3, "MOINA") drops every incoming message except HEARTBEAT and the MAV_CMD_USER_1/2 HMAC challenge-response until the GCS authenticates (`GCS_Common.cpp:4178`, `KFT_GCSAuth.h`). Stock SITL doesn't do this.
  - On a KFT flight controller, this GCS will see heartbeats and whatever the FC streams by default, but its stream requests and mission upload will be dropped.
  - Implementing the auth means handling the app secret, so that decision is yours (where the key lives, which app id). It blocks the real-FC check, not SITL.
- GS-7 (see Safety).
- **Next: Pass 7, the mission protocol.**

---

## Pass 7 — W2-2: Mission protocol (upload, download, clear, progress) (2026-09-25)

### What changed
- **`core/vehicle/Mission.kt`** (new):
  - `MissionItem`, `AltitudeFrame`, `MissionCommand` and `Mission(home, items)`: plain types with no MAVLink in them.
  - `missionToWire` / `missionFromWire`: the only place seq 0 is built or removed (S11).
- **`core/vehicle/MissionProtocol.kt`** (new): the upload/download/clear state machine, with per-message resend, timeouts, cancel, and the ArduPilot quirks listed in its KDoc.
- **`core/vehicle/MissionRepository.kt`** (new):
  - A `MissionRepository` interface for screens, and `DefaultMissionRepository`.
  - It refuses to start without a vehicle, and refuses to upload without a home.
- **`core/vehicle/VehicleState.kt`**: `mission: MissionProgress?` from MISSION_CURRENT and MISSION_ITEM_REACHED.
- **`core/vehicle/di/VehicleModule.kt`**: binds `MissionRepository`.
- **`feature/fly`**: a "Mission" HUD item ("3 / 5", "Done").
- **Tests**:
  - `MissionTest` (S11, frames, command numbers).
  - `MissionProtocolTest` (12 cases against `ArduPilotMissionFake`).
  - `MissionRepositoryTest` (3).
  - `VehicleStateTest.missionProgress`, and `FlyViewModelTest.missionProgressText`.
  - `SitlCheck.missionUploadReadBackCompareAndClear`.

### How it works
```
PlanViewModel (Pass 8) ──upload(items)──▶ DefaultMissionRepository
                                             │ vehicle ids from LinkState, home from VehicleState
                                             │ missionToWire(home, items): [home=seq0, items…=seq1…]   (S11)
                                             ▼
                                         MissionProtocol ──MISSION_*──▶ MavTxGateway ──▶ link
                                             ▲ inbox: mission replies from that vehicle, addressed to us
                                    ConnectionManager.frames
```
**Upload state machine** (download and clear use the same `exchange` step):
```
            send COUNT(n)                         any wait: 1.5 s without an accepted reply
   ┌──────────────────────────┐                   → resend the last message (COUNT or item k)
   ▼                          │                   → after 5 sends: fail "no answer …"
 [WAIT] ──REQUEST(k) / REQUEST_INT(k)──▶ send ITEM(k) ──▶ [WAIT]      (a repeated k is simply resent)
   │  ──ACK(INVALID_SEQUENCE)──▶ ignore, keep waiting    (ArduPilot keeps the upload open)
   │  ──ACK(ACCEPTED)──────────▶ [DONE]
   │  ──ACK(other error)───────▶ [FAILED] "the vehicle refused: NO_SPACE … may now hold a partial mission"
   └─ coroutine cancelled ─────▶ send ACK(OPERATION_CANCELLED), rethrow
```
Download: REQUEST_LIST → COUNT(n) → for k in 0 until n: REQUEST_INT(k) → ITEM_INT(k) → our ACK(ACCEPTED). Clear: CLEAR_ALL → ACK.

### Engineering learnings
- **One `exchange` step for every wait.** The pieces are: send, then wait until a handler *accepts* a reply, then resend on timeout. Every step of all three flows is that one function plus a small handler that says which reply it wants. Replies it doesn't want don't restart the clock, so a noisy link can't keep a dead transfer alive. *Why not a hand-written state enum:* the order of steps is the program itself. A `while (reply is Request)` loop is the state machine, and it reads top to bottom.
- **`Result` + one exception type.** Operator-facing failures ("the vehicle refused: DENIED") are `MissionTransferException`, and they come back as `Result.failure`. Cancellation is *not* caught: structured concurrency needs `CancellationException` to propagate, so the cancel ACK is sent in `NonCancellable` and the exception is rethrown.
- **S11 in two pure functions.** The seq-0 rule lives only in `missionToWire` / `missionFromWire`, so the test that guards it is a plain function call, with no protocol or time involved.
- **The fake behaves like ArduPilot, not like the spec.** `ArduPilotMissionFake` requests with MISSION_REQUEST, answers a wrong seq with INVALID_SEQUENCE without ending the upload, counts home in MISSION_COUNT, and keeps home on clear. Each behaviour comes from the ArduPilot source, so the tests fail the way the real vehicle would.
- **ArduPilot facts (S10)**, from `MissionItemProtocol.cpp` and `AP_Mission.cpp`, master 2026-09:
  - It requests upload items with the deprecated MISSION_REQUEST (hence the `@Suppress("DEPRECATION")`).
  - It re-requests every 1 s and gives up after 8 s. Our 1.5 s × 5 = 7.5 s stays inside that.
  - It writes items as they arrive, so a failed upload leaves a partial mission. The error message says so.
  - It ignores MISSION_ACK from a GCS, so our cancel and complete ACKs are for other autopilots.
  - It ignores whatever is written at index 0.
  - It returns frame GLOBAL for commands that store no location (DO_CHANGE_SPEED, RTL). SITL showed this, and the check now compares frames only for location commands.
  - MISSION_CURRENT.total excludes home.

**Ponytail review:** lean already. `MissionRepository` is an interface on purpose (CLAUDE.md §7: repositories), and every other type has exactly one job and a caller.

### Safety
- These transmissions start being used now: MISSION_COUNT, MISSION_ITEM_INT and MISSION_CLEAR_ALL (`MISSION_CHANGE`: blocked while the pod reports LOCKED/TERMINAL/ENGAGE), and MISSION_REQUEST_LIST, MISSION_REQUEST_INT and MISSION_ACK (`ALWAYS`). **The allowlist is unchanged.** Everything goes through the gateway via `MavSender`.
- Uploading a mission doesn't fly it. The pilot switches to AUTO on the RC (S9).
- A failed or cancelled upload can leave a partial mission on the vehicle. The error text tells the operator to upload again or clear, and it's worth re-reading before flight.

### What to look at
1. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/MissionProtocol.kt:79`: the upload loop, then `exchange` at `:162`.
2. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/Mission.kt:49`: S11 in one function.
3. `core/vehicle/src/commonTest/kotlin/com/kft/gcs/core/vehicle/MissionTest.kt:20`: the test that fails if seq 0 isn't home.

### Tests
- `MissionTest` (5):
  - Seq 0 is home (NAV_WAYPOINT, GLOBAL, home lat/lon ×1e7, 584 m) and planned items start at seq 1.
  - Download drops seq 0 into `home` (arrival order doesn't matter).
  - An empty download works.
  - The `_INT` frames map the way AP_Mission maps them.
  - Our command constants equal the `common.xml` numbers.
- `MissionProtocolTest` (12, virtual time):
  - Clean upload via MISSION_REQUEST, and via MISSION_REQUEST_INT.
  - A stale re-request is answered with no timeout, and its INVALID_SEQUENCE is ignored.
  - A lost item is resent at exactly 1.5 s.
  - NO_SPACE fails with a "partial mission" warning.
  - A silent vehicle fails after 5 COUNTs at 7.5 s.
  - Cancelling sends OPERATION_CANCELLED.
  - Upload then download gives back `Mission(home, plan)`, and ends with our ACK.
  - A lost download request is re-sent.
  - An empty vehicle downloads fine.
  - DENIED fails.
  - Clear keeps only home.
- `MissionRepositoryTest` (3): no vehicle → nothing sent; no home → nothing sent (S11); the upload goes to the linked sysid with count = home + items.
- `VehicleStateTest.missionProgress`: seq/total/reached/complete, and the 0 / 65535 totals. `FlyViewModelTest.missionProgressText`.
- **SITL, done** (ArduCopter 4.8.0-dev from Mission Planner's `sitl` folder):
  - Setup: MAVProxy on TCP 5760 as the permanent SERIAL0 client, then `KFT_SITL=127.0.0.1:5762 gradlew :core:vehicle:jvmTest --tests '*SitlCheck*'`.
  - Upload progress was 1/6 … 6/6.
  - The read-back matched the upload: TAKEOFF 20 m relative, DO_CHANGE_SPEED (1, 8, -1), two waypoints at 30 m with identical 1e-7 lat/lon, and RTL.
  - Home was 584.09 m, and clearing it left an empty mission.
  - **Finding:** the Windows SITL build exits when its SERIAL0 TCP client disconnects. So SITL checks go through SERIAL1 (5762) while MAVProxy holds 5760, which is also how Pass 10 runs.
- `./gradlew check` passes, with no warnings.

### Open questions / next
- The `Mission` HUD shows the vehicle's own item numbers (1 = first after home), which match the editor's list numbering from Pass 8.
- Partial/resume upload (MISSION_WRITE_PARTIAL_LIST) isn't used. A full re-upload of a survey (hundreds of items) takes about 2 × n round trips; measure it on the real radio before optimising.
- **Next: Pass 8, the waypoint editor.**

---

## Pass 8 — W2-3: Waypoint editor on the shared map (2026-09-25)

### What changed
- **`ui/map`**:
  - `MapModel.kt`: new `MapOverlay.Route`, `MapOverlay.Marker(id, position, label, style, draggable)` and `MarkerStyle`.
  - `MapView.kt`:
    - Route line, markers (circle + number), and `onMapClick` / `onMarkerClick` / `onMarkerDrag` callbacks.
    - F4 drag handling on the map's own modifier, plus a pure `hitMarker`.
    - The layers are always declared, so each source keeps its place.
- **`feature/plan`** (was empty):
  - `PlanItems.kt` (pure): `PlanItem`, `addWaypoint` (Copter item 1 = NAV_TAKEOFF), `seqNumbers`, `toMissionItems` / `fromMissionItems` (speed as DO_CHANGE_SPEED, passthrough for anything the editor can't edit).
  - `PlanUiState.kt`: state, overlays and form parsing, all pure.
  - `PlanViewModel.kt`: edit events; Upload / Read / Clear / Cancel through `MissionRepository`; snackbar effects.
  - `PlanScreen.kt`: the right-hand panel, with a confirm dialog for Clear.
  - `di/PlanModule.kt`.
- **`feature/fly`**: `FlyScreen` no longer draws a map, only its panels.
- **`core/vehicle`**: `VehicleState.vehicleKind`, from the heartbeat.
- **`app/shared`**:
  - `App.kt`: `MapAndScreens()` owns the one `MapView` and both map ViewModels, and gains a "Plan" tab.
  - `AppModule.kt`: registers `planModule`.
  - `build.gradle.kts`: `ui:map`, lifecycle-runtime-compose and koin-compose-viewmodel in commonMain.
- **`docs/decisions/ADR-001-map-engine.md`**: an F10 note saying the Plan tab now shares the map.

### How it works
```
App() ─ MapAndScreens
   ├─ FlyViewModel  ─state─┐                         (both created here, window scope)
   ├─ PlanViewModel ─state─┤
   │                        ▼
   ├─ MapView(basemap/camera from Fly, overlays = plan.overlays + fly.overlays,
   │          callbacks = Plan tab ? plan::onMapClick/onMarkerClick/onMarkerDragged : null)
   └─ NavHost ─ Fly  → FlyRoute(fly)   panels only
              ─ Plan → PlanRoute(plan) panel on a Surface (blocks clicks from reaching the map)
              ─ Links→ Surface { ConnectionsRoute() }

click on map ──▶ PlanViewModel.onMapClick ──▶ items.addWaypoint(at, vehicleKind) ──▶ state ──▶ markers/route
drag marker  ──▶ MapView (Initial pass, consumes) ──▶ onMarkerDrag(id, LatLon) ──▶ item.position updated
Upload ──▶ toMissionItems(items) ──▶ MissionRepository.upload (home added as seq 0, S11) ──▶ snackbar
Read   ──▶ MissionRepository.download ──▶ fromMissionItems(mission.items) ──▶ rows (home never a row)
```

### Engineering learnings
- **Hoisting the map to the app.** F10 says one map per window, so no screen owns it. Each map screen's ViewModel produces map *data* (overlays, and camera for Fly), and `App()` routes the input callbacks to whichever tab is active. *Why not a "map layer" interface per feature:* there are two screens. A `when` on the current route in one place is less code, and it's easy to read.
- **ViewModels at window scope, passed down.** `koinViewModel()` inside a NavHost entry would create a *second* instance tied to the back-stack entry, so the map and the panel would disagree. Creating both in `MapAndScreens` and passing them to the routes gives one instance each.
- **`rememberUpdatedState` for gesture code.** `pointerInput(mapState)` starts once and must not restart on every recomposition, or a drag would be cut off mid-gesture. It reads the latest markers and callbacks through `rememberUpdatedState`.
- **Tap vs drag by touch slop.** A press on a marker is a click unless it moves more than `viewConfiguration.touchSlop` (the platform's own number), so selecting a waypoint doesn't nudge it.
- **Surface blocks, background doesn't.** Compose sends a pointer event to the layer underneath when nothing on top handles it. The Plan panel is an M3 `Surface`, so clicking empty panel space doesn't add a waypoint behind it.
- **Speed is an item, not a field.** NAV_WAYPOINT has no speed parameter, so a row's speed becomes a DO_CHANGE_SPEED item in front of it. The rows show the *vehicle's* seq numbers (`seqNumbers`), so "item 4" on the map, in the list and in the Fly HUD's "Mission 4 / 5" are the same item. On download, only a DO_CHANGE_SPEED that looks exactly like ours folds back into a row. Anything else stays a passthrough row, so read → upload never changes a mission it didn't understand.
- **ArduPilot facts (S10)**, from ArduPilot master 2026-09:
  - DO_CHANGE_SPEED type 0 is horizontal speed on Copter and the airspeed target on Plane.
  - On Plane, type 1 sets the *minimum groundspeed* (`Plane::do_change_speed`), so we always send 0.
  - ArduCopter's NAV_TAKEOFF ignores lat/lon, so the takeoff row has no position.

**Ponytail review:** no cuts. `MarkerStyle` (four values) and the three callbacks are all used. `PlanItem.passthrough` exists to keep read-back lossless. `PlanScreen`'s confirm dialog guards a destructive action.

### Safety
- No allowlist change. The editor only uploads, reads and clears the mission, through `MissionRepository` → gateway.
- Upload doesn't start the mission. The snackbar says "Switch to AUTO on the RC to fly it" (S9). The editor has no flight buttons.
- Clear asks for confirmation. A cancelled transfer warns that the vehicle mission may be incomplete.
- Copter plans start with NAV_TAKEOFF (item 1), so AUTO from the ground climbs before it travels. Plane plans don't get one (the pilot launches, per the exit-check flow).

### What to look at
1. `app/shared/src/commonMain/kotlin/com/kft/gcs/app/App.kt:68`: one map, two screens, callbacks by tab.
2. `ui/map/src/commonMain/kotlin/com/kft/gcs/ui/map/MapView.kt:171`: the marker gesture (F4).
3. `feature/plan/src/commonMain/kotlin/com/kft/gcs/feature/plan/PlanItems.kt:68`: rows ↔ mission items, with speed as DO_CHANGE_SPEED.

### Tests
- `PlanItemsTest` (6):
  - Copter's first click gives [takeoff 30 m, waypoint], and the next waypoint takes the previous altitude.
  - Plane gets no takeoff, at 100 m.
  - Seq numbers count the speed items: [1, 3, 4].
  - The exact mission items: DO_CHANGE_SPEED (0, 8, -1).
  - Round trip with RTL passthrough; a throttle-setting or trailing speed change stays as its own row.
  - An AMSL waypoint from another tool isn't silently made relative.
- `PlanViewModelTest` (9):
  - Home is the "H" marker (never a row), markers are labelled by seq, the newest is selected, and the route starts at home.
  - Drag moves a waypoint; dragging home is ignored.
  - Altitude/speed editing, with parse errors.
  - Upload progress "Uploading 2 / 3…", then the result.
  - Upload error text.
  - Cancel message.
  - Read replaces the rows.
  - The item being flown gets the CURRENT style.
  - Offline: you can plan, but not transfer.
- `GeoJsonTest` (+2): markers keep their labels and `[lon, lat]`; a one-point route is dropped; `hitMarker` picks the nearest marker within 24 dp (5 dp beats 30 dp).
- `VehicleRepositoryTest`: `vehicleKind` from the heartbeat.
- **Manual, desktop + SITL (ArduCopter 4.8.0-dev, MAVProxy → UDP 14550):**
  - Links → "SITL (UDP 14550)" → Connect → Plan. Three map clicks gave "1 Takeoff, 2 Waypoint, 3 Waypoint, 4 Waypoint" with numbered markers and a route from home.
  - Dragged waypoint 3 to a new place, and typed speed 6 on the selected waypoint, which renumbered it to 4 ("30.0 m · 6.0 m/s").
  - Upload → "Uploaded 5 items. Switch to AUTO on the RC to fly it." Read → "Read 5 items from the vehicle", with identical rows.
  - Plan→Fly→Links→Plan→Links→Fly: no crash. Fly shows the mission route, "ArduPilot 4.8.0-dev" and "Mission 0 / 5".
  - Closing the window exited cleanly. The only "Host surface lost" was at close.
- `./gradlew check` and `:app:android:assembleDebug` pass.

### Open questions / next
- MapLibre logged "Invalid geometry in line layer" once during the run. The route and track never emit fewer than two points, so it may be from the OpenFreeMap style. Harmless so far; worth a look if it repeats.
- `ponytail:` the plan is in memory only. `.plan` save/load belongs to weeks 4–5.
- On the ground, the home marker sits under the vehicle arrow. That's expected (the vehicle is drawn on top).
- **Not checked:** touch dragging on the Android emulator or tablet (same code path per F4). It's in the Pass 10 tablet checklist.
- **Next: Pass 9, serial transports.**

---

## Pass 9 — W2-4: Serial transports (desktop jSerialComm, Android USB-OTG) (2026-09-25)

### What changed
- **`core/mavlink`**:
  - `SerialTransport.kt` (new, common):
    - `SerialPortInfo`, and `expect class SerialPorts` (list and open).
    - An internal `SerialLink` seam (read with a 200 ms timeout, write, close).
    - `SerialTransport` (a `MavTransport` over a `SerialLink`), and `STANDARD_BAUD_RATES`.
  - `SerialPorts.jvm.kt` (new): jSerialComm. `SerialPorts.android.kt` (new): usb-serial-for-android, plus the USB permission request.
  - `LinkConfig.Serial(port, baud)`.
  - `createTransport` takes the platform's `SerialPorts`, as does `ConnectionManager`'s production constructor, and `mavlinkModule` gets it from Koin.
  - `build.gradle.kts`: jSerialComm on `jvmMain`, usb-serial-for-android on `androidMain` (both were already in the version catalog).
- **`settings.gradle.kts`**: the JitPack repository, limited to `com.github.mik3y`.
- **`app/shared`**:
  - `App(platformModule)`.
  - `DesktopApp` passes `SerialPorts()`, and a new `AndroidApp(context)` passes `SerialPorts(context)`.
- **`app/android`**: `MainActivity` hosts `AndroidApp(this)`. The manifest has `uses-feature usb.host` with `required="false"`.
- **`feature/connections`**:
  - A "Serial" link kind.
  - Device chips with Refresh, and baud-rate chips (9600 … 921600, default 57600).
  - Form validation.
  - `ConnectionsRepository.serialPorts()`.
- **Tests**: `SerialTransportTest` (7), `DesktopSerialPortsTest` (2), and `ConnectionsViewModelTest` (+2).

### How it works
```
Links: Serial · COM7 · 57600 ──save──▶ LinkConfig.Serial("COM7", 57600) ──connect──▶ ConnectionManager
    reconnect loop ──▶ createTransport(config, serialPorts) ──▶ SerialTransport { serialPorts.open("COM7", 57600) }
                                                                   │ open() inside MavTransport.open():
                                                                   │   desktop: jSerialComm 8N1, semi-blocking 200 ms reads
                                                                   │   Android: find USB driver → no permission? request it, throw
                                                                   │            (the loop retries every ≤5 s; after "Allow" it opens)
                                                                   ▼
                    BufferedMavConnection (framing) ◀── Source: poll read(200 ms) until bytes, or closed → IOException
                                                    ──▶ Sink: one write() per frame (flush)
```

### Engineering learnings
- **One seam, three implementations.** `SerialLink` is three methods. Everything with logic (polling, close handling, one write per frame) is in the common `SerialTransport` and tested against a fake. The platform files are thin library calls, so the untested surface is as small as it can be. That's the "interface on purpose, for testing" CLAUDE.md allows.
- **Poll with a timeout instead of blocking.** Neither library reliably wakes a blocked read when another thread closes the port. A 200 ms read timeout plus a `closed` flag means the reader notices a disconnect within 0.2 s, and the connection manager's existing reconnect loop handles the rest.
- **Permission as a retryable error.** Android asks for USB permission asynchronously. Instead of a BroadcastReceiver with its own lifecycle, `open()` requests permission and throws "Waiting for USB permission". The reconnect loop (1, 2, 4, 5 s…) calls `open()` again, which succeeds once `hasPermission` is true. There's no new state and no new code path.
- **expect/actual class with different constructors.** `SerialPorts` needs a `Context` on Android and nothing on desktop. The expect class declares no constructor, so each shell builds its own and gives it to Koin through `App(platformModule)`. `AndroidApp` mirrors `DesktopApp`, so the Android shell never imports Koin.
- **Repository hygiene.** JitPack is added with `content { includeGroup("com.github.mik3y") }`, so it can only ever serve that one library. An unrestricted extra repository is a supply-chain hole.
- **ArduPilot facts (S10):**
  - SiK radios and ArduPilot's `SERIALn_BAUD = 57` default to 57600 8N1, which is our default.
  - Over the FC's native USB (CDC-ACM) the baud rate is ignored.
  - Some CDC flight controllers wait for DTR before sending, so the Android adapter raises it.

**Ponytail review:** one cut, a `SerialFormEvents` holder class, inlined as three lambdas like every other event on that screen. Kept: the `SerialLink` seam (testing), and `STANDARD_BAUD_RATES` (the picker).

### Safety
- No allowlist change. Serial is only another byte pipe under the same `MavTxGateway`, and nothing new can write to it: `SerialLink`/`SerialTransport` are `internal` to `core:mavlink`, like the socket transports.

### What to look at
1. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/SerialTransport.kt`: the seam and the polling source.
2. `core/mavlink/src/androidMain/kotlin/com/kft/gcs/core/mavlink/SerialPorts.android.kt`: USB permission via the reconnect loop.
3. `feature/connections/src/commonMain/kotlin/com/kft/gcs/feature/connections/ConnectionsUiState.kt`: `toConfigOrError` for serial.

### Tests
- `SerialTransportTest` (7, common, fake port):
  - Bytes pass through after read timeouts.
  - Exactly one `write()` per flush, so a frame isn't split.
  - `close()` makes the pending read throw `IOException`.
  - A detached device throws `IOException`.
  - Open failures surface from `open()` (which the reconnect loop retries).
  - A real MAVLink v2 heartbeat encoded by mavlink-kotlin decodes through the serial transport.
  - `LinkConfig.Serial` validation and summary.
- `DesktopSerialPortsTest` (2, real jSerialComm): a missing port gives a readable `IOException` naming the port, and listing ports doesn't crash.
- `ConnectionsViewModelTest` (+2): Serial requires a device, and the chosen baud is saved (`Serial COM7 @ 115200`). Refresh shows a newly plugged port.
- `./gradlew check` and `:app:android:assembleDebug` pass.
- **Real hardware: yours** (see the tablet checklist after Pass 10):
  - Desktop: Links → Serial → pick the radio's COM port → 57600 → Save → Connect.
  - Android: plug the radio or FC in over OTG → Refresh → pick it → Connect → tap **Allow** → it connects on the next retry, within 5 s.

### Open questions / next
- Desktop: the device list comes from jSerialComm's enumeration, so a Bluetooth SPP pairing appears as its COM port (spec §2.1). Not tried.
- Android: an OTG device plugged in *after* connecting isn't auto-detected; tap Refresh. Auto-launch on attach (a device filter in the manifest) is a P1 nicety.
- **Next: Pass 10, the Plane SITL profile and the week 2–3 exit check.**

---

## Pass 10 — W3-1: SITL profiles and the week 2–3 exit check (2026-09-25)

### What changed
- **`tools/sitl/start-sitl.ps1`** (new): the SITL profile for `-Vehicle copter|plane`.
  - Starts Mission Planner's Windows SITL build (model, CMAC home, default params) minimized.
  - Starts MAVProxy in its own console as the pilot's RC, holding SERIAL0 and forwarding to UDP 14550.
  - Documents the free ports: 5762 for a GCS over TCP, 5763 for tools, and how to reach them from the emulator.
- **`tools/sitl/sitl_pilot.py`** (new, SITL only): sends what MAVProxy sends for `mode guided`, `arm throttle`, `takeoff 20` and `mode auto`, so the exit check can run unattended. It's never part of the app (S9).
- **`ui/map/MapView.kt`**: the planned route now draws *under* the flown track. In the first SITL run the blue route hid the orange track exactly where the vehicle flew.

### How it works
```
start-sitl.ps1 ─▶ ArduCopter.exe / ArduPlane.exe (SITL, -I0)
                    ├─ 5760 SERIAL0 ◀── MAVProxy (RC: mode/arm/takeoff) ──▶ UDP 14550 ─▶ desktop GCS "SITL (UDP 14550)"
                    ├─ 5762 SERIAL1 ◀── GCS over TCP (desktop 127.0.0.1, emulator 10.0.2.2)
                    └─ 5763 SERIAL2 ◀── sitl_pilot.py (unattended runs only)
GCS: Plan → Upload (home = seq 0) ─▶ pilot: GUIDED → arm → takeoff 20 → AUTO ─▶ GCS Fly: "Mission n / N" … "Done"
```

### Engineering learnings
- **The exit check proves S9 end to end.** The GCS only planned, uploaded and watched. Every flight action came from the pilot's side (MAVProxy / the pilot script) on a *different* MAVLink system id (254) and port. The GCS gateway would have refused all of them anyway.
- **Windows SITL quirks** (worth knowing before you run it yourself):
  - The Cygwin SITL build exits when its SERIAL0 TCP client disconnects. That's why MAVProxy holds 5760 and everything else uses 5762/5763.
  - MAVProxy on Windows needs a real console (`prompt_toolkit` fails when detached), so the script gives it its own window.
- **Emulator networking:** UDP into the emulator needs `adb emu redir`, and the host port can have only one listener, so the desktop GCS must be closed. A direct TCP profile to `10.0.2.2:5762` avoids both problems, and that's what the emulator run used.
- **Why a pilot script at all:** the MAVProxy console is a terminal, and my automation can click terminals but not type into them. The script sends the same four MAVLink messages MAVProxy would, so the evidence is the same. **Your manual runs use the MAVProxy console**, exactly as the spec says.

**Ponytail review:** lean already. The launcher is one script with one switch, and the pilot script is ~60 lines with no options beyond link and altitude. The MapView change *removes* a visual bug with a reorder (0 net lines).

### Safety
- No allowlist change. The pilot script is a test tool that plays the RC in SITL. It isn't built, packaged or referenced by the app, and it uses system id 254 so it can't be mistaken for the GCS (255).
- During both runs the GCS sent only REQUEST_DATA_STREAM, REQUEST_MESSAGE, SET_MESSAGE_INTERVAL and the MISSION_* upload. The flight actions came from the pilot side.

### What to look at
1. `tools/sitl/start-sitl.ps1`: ports and the pilot commands in the header.
2. `tools/sitl/sitl_pilot.py`: what "the RC" sends.

### Tests
- **Week 2–3 exit check, Copter (ArduCopter 4.8.0-dev SITL):**
  - **Desktop:** Links → SITL (UDP 14550) → Plan → four clicks gave Takeoff + 4 waypoints → Upload ("Uploaded 5 items") → pilot: GUIDED → ARMED → climbed to 20 m → AUTO.
    - The Fly view showed Auto / ARMED / 30 m, "Mission 2 / 5", "3 / 5" … "Done", with the magenta current-waypoint marker moving along.
    - ArduPilot's "Reached command #5" appeared in the message strip. Then RTL, landed, disarmed.
  - **Android emulator (Medium_Tablet, Android 15):** Links → new TCP profile 10.0.2.2:5762 → "ArduCopter · system 1 · ARMED, 126 msg/s" → Plan → three taps → Upload ("Uploaded 4 items") → pilot sequence.
    - Fly showed Auto / ARMED / 9 m/s, "Mission 2 / 4", then "Done", and "Reached command #4". The orange flown track sat over the blue route after the reorder.
- **Plane: not run.** There's no ArduPlane SITL binary on this machine, and you chose not to download one (2026-09-25). `start-sitl.ps1 -Vehicle plane` is written, and checked only for its missing-binary message.
- `start-sitl.ps1 -Vehicle copter` was checked: it starts SITL and MAVProxy, and SITL answers heartbeats on 5763.
- `./gradlew check` and `:app:android:assembleDebug` pass.
- **Serial on desktop, partial:** the Links form listed this laptop's real ports (COM26/COM27, Bluetooth SPP). No radio was connected.

### Your checklist: Plane SITL on desktop
1. In Mission Planner: Simulation → Plane → start it once (this downloads `ArduPlane.exe` and `plane.parm`), then close Mission Planner.
2. `powershell -ExecutionPolicy Bypass -File tools\sitl\start-sitl.ps1 -Vehicle plane`
3. `gradlew.bat :app:desktop:run` → Links → **SITL (UDP 14550)** → Connect. Expect "ArduPlane · system 1 · disarmed", and "ArduPilot 4.x" under the HUD.
4. Plan → click 4 points 300–500 m apart. Expect **no** Takeoff row (Plane) and 100 m default altitude. Upload → "Uploaded 4 items".
5. In the MAVProxy window: `mode guided`, `arm throttle`, `takeoff 20` (ArduPlane 4.5+; on older firmware `mode takeoff`), then `mode auto` once it's above ~20 m.
6. Expect: "Mission 1 / 4" → … → "Done", the current marker turning magenta, and the orange track following the route. At the end ArduPlane loiters or RTLs by itself.

### Your checklist: physical tablet
1. `gradlew.bat :app:android:installDebug` with the tablet on USB, or copy the APK. The app is "KFT Survey (dev)", id `com.kft.survey.dev`.
2. **Map:** the Fly view shows the street map, pinch-zoom and pan work, and switching Fly ↔ Plan ↔ Links 10 times doesn't crash (ADR-001 F10).
3. **Network SITL:** on the laptop, run `start-sitl.ps1`. MAVProxy only forwards to the laptop itself, so on the tablet add a **TCP client** profile to `<laptop IP>:5762` and allow port 5762 through Windows Firewall. Connect → vehicle found.
4. **Plan by touch:**
   - Tap to add waypoints: Copter gets Takeoff as item 1.
   - Drag a numbered marker with a finger: it moves, and the map does **not** pan underneath.
   - Tap a marker: it's selected, and the altitude/speed fields edit it.
   - Tapping on the panel must not add a waypoint behind it.
5. **Transfer:** Upload → Read gives the same rows. Clear asks to confirm and empties both.
6. **Exit check:** pilot in MAVProxy on the laptop (`mode guided`, `arm throttle`, `takeoff 20`, `mode auto`). The tablet shows "Mission n / N", then "Done".
7. **USB-OTG serial**, with a SiK radio or the FC's USB through an OTG adapter:
   - Links → Serial → Refresh → the device appears (FTDI / CP210x / CH34x / CDC) → pick 57600 for a radio → Save → Connect.
   - Expect "Waiting for USB permission: tap Allow". Tap **Allow**, and within 5 s the link connects.
   - Unplug the radio: "Reconnecting…". Plug it back in: it reconnects.
8. **Desktop serial**, with the same radio on the laptop: Links → Serial → the COM port → 57600 → Connect.
9. **Real flight controller:** if it runs KFT firmware (`ardupilotKFT`, MOINA), expect heartbeats only. Mission upload and the startup requests will be ignored until the GCS HMAC login exists (see Pass 6 open questions).

### Open questions / next
- **Plane exit check still open.** Run the Plane checklist above, or allow the ArduPlane SITL download and I'll run it.
- MAVProxy's UDP output didn't reach the emulator through `adb emu redir` (not investigated; TCP 5762 worked).
- KFT firmware GCS authentication (Pass 6) is still the blocker for a real KFT FC.
- **Next (waiting for your go-ahead):** weeks 4–5, the survey grid.

---

## Pass 11 — Clean-up and Plane: spec sync, MISSION_SET_CURRENT, armed confirmation, saved profiles, Plane exit check (2026-09-25)

### What changed
- **`docs/spec/01_survey_gcs_feature_spec.md`**:
  - GS-7 is closed (app id `com.kft.survey`, debug `com.kft.survey.dev`; `com.kft.gcs` stays with the existing GCS).
  - The pod-contract §3 note is now **GS-8**.
  - S9 gains two sentences: MISSION_SET_CURRENT only while disarmed, and upload/clear while armed ask first.
- **`CLAUDE.md` §4, `TxPolicy.kt`**: the GS-7 references now point to GS-8.
- **`core/mavlink`**:
  - `TxPolicy.kt`: a new `MISSION_CHANGE_DISARMED` category for MISSION_SET_CURRENT. `check()` takes the armed flag.
  - `MavTxGateway.kt`: reads `vehicleArmed()` at send time, next to the pod state.
  - `ConnectionManager.kt`: gives the gateway the armed flag from the vehicle's heartbeat.
  - `LinkConfig.kt`: `@Serializable`, with a stable `@SerialName` per link kind.
  - `build.gradle.kts`: the serialization plugin and kotlinx-serialization-json (both already in the catalog).
- **`feature/plan`**: `PlanUiState.uploadWarning` / `clearWarning`, and an "Upload while armed?" dialog in `PlanScreen`. Clear's existing dialog now uses the armed text too.
- **`feature/connections`**:
  - `ConnectionsRepository.kt`: a `ProfileStore` interface, save on every change, load at start, and pure `encodeProfiles` / `decodeProfiles`.
  - `di/ConnectionsModule.kt`: passes the store to the repository.
- **`app/shared`**:
  - `FileProfileStore.kt` (new, `jvmCommonMain`, shared by desktop and Android): an atomic file write.
  - `DesktopApp.kt`: `%APPDATA%\KFT-GCS\connection-profiles.json`. `AndroidApp.kt`: `filesDir/connection-profiles.json`.
  - `build.gradle.kts`: a `jvmCommon` source set (the same pattern `core:mavlink` uses for sockets).
- **`tools/sitl`**:
  - `start-sitl.ps1`: one working folder per vehicle, so Plane never loads Copter's `eeprom.bin` parameters.
  - `sitl_pilot.py`: Plane uses ArduPlane's TAKEOFF mode.

### How it works
```
vehicle HEARTBEAT ──▶ ConnectionManager ──▶ LinkState.Connected(vehicle.armed)
                                                   │ read at send time
MISSION_SET_CURRENT ──▶ MavTxGateway ──▶ TxPolicy.check(MISSION_CHANGE_DISARMED, pod, armed)
                                           pod LOCKED/TERMINAL/ENGAGE → rejected
                                           armed == false             → sent
                                           armed == true or unknown   → rejected "only while disarmed (S9)"

Plan: Upload ──state.uploadWarning == null──▶ upload
            └─ "Vehicle is ARMED in AUTO: …" ──▶ dialog ──Upload──▶ upload   (warning, never a block)

start: ProfileStore.read() ─decodeProfiles─▶ profiles   (no file or broken file → the two SITL defaults)
add/delete ──▶ _profiles ──encodeProfiles──▶ ProfileStore.write() ──▶ write .tmp, then atomic rename
```

### Engineering learnings
- **Fail closed on unknown state.** With no vehicle heard, `armed` is null, and the gateway treats it as armed. "Allowed only while disarmed" has to mean "known to be disarmed". Otherwise the moment before the first heartbeat would be the one gap in the rule.
- **Read state at send time, don't copy it.** The gateway gets a `() -> Boolean?` rather than a stored flag, the same way it already reads `podStatus.value`. There's one source of truth (the heartbeat in `LinkState`), so the gateway can't act on a stale copy. `gatewayUsesTheHeartbeatArmedFlag` checks the real wiring.
- **Warnings in the UI state, not the composable.** The dialog text is built in the pure `buildPlanUiState`, so the exact sentence is unit-tested. The screen only decides *when* to show it. The dialog keeps the text from the moment you clicked, so a disarm while the dialog is open can't leave a stale flag behind.
- **A file format is an API.** `@SerialName("udp-listen")` etc. are the on-disk names, and `fileFormatIsStable` fails if one changes. `encodeDefaults = true` was **found in the desktop run**: the first saved file left out `"port": 14550` because it equals the class default. If we ever changed that default, every saved profile would silently point somewhere else. Now every field is written.
- **Atomic save.** The store writes a `.tmp` next to the file and renames it over the old one (`ATOMIC_MOVE`). A crash mid-write leaves the old file or the new one, never half of one. A broken file (hand-edited, from a future version) falls back to the defaults instead of crashing at startup.
- **Why `LinkConfig` is `@Serializable` rather than a DTO copy:** its KDoc already said "plain data, so the Connections screen can save it as a profile". A mirror class would be four more types kept in step by hand. The `init` checks run on load, so a file with port 0 is rejected, not half-loaded.
- **ArduPilot facts (S10)**, master 2026-09:
  - MISSION_SET_CURRENT (common.xml #41) is deprecated in favour of `MAV_CMD_DO_SET_MISSION_CURRENT`, but ArduPilot still handles it (`GCS_Common.cpp` `handle_mission_set_current` → `AP_Mission::set_current_cmd`). In AUTO the vehicle jumps to that item immediately.
  - The command form (`MAV_CMD_DO_SET_MISSION_CURRENT`) stays **unlisted**, so it's rejected. Add it with the same rule when resume-from-point needs it.
- **Windows SITL:** SITL stores its parameters in `eeprom.bin` in the *working folder*. Running Plane from Copter's folder would boot ArduPlane with Copter's saved parameters. So each vehicle now gets its own folder. Copter starts with fresh defaults the first time after this change.
- **Build environment note:** in this session `gradlew` failed with "Unable to establish loopback connection". The JDK's internal pipe uses a Unix-domain socket in `%TEMP%`, and the 8.3 path `C:\Users\HRUSHI~1\…` broke it. Setting `TEMP`/`TMP` to a plain path fixed it. Your own terminal isn't affected unless it shows the same error.

**Ponytail review:** one cut. `desktopDataDir()` had one caller, so it's inlined into the Koin module (−3 lines). Kept: `ProfileStore` (the repository's test seam, §7), the `SavedProfile` DTO (keeps the file format separate from the UI type), and the `jvmCommon` source set (the alternative is copying the atomic-write code twice).

### Safety
- **Allowlist change:** MISSION_SET_CURRENT moves from `MISSION_CHANGE` to `MISSION_CHANGE_DISARMED`.
  - Allowed only when the latest heartbeat says disarmed.
  - Rejected while armed or with no vehicle heard, and still blocked by a pod LOCKED/TERMINAL/ENGAGE.
  - Tests: `MavTxGatewayTest.missionSetCurrentOnlyWhileDisarmed`, `gatewayReadsArmedStateAtSendTime`, and `ConnectionManagerTest.gatewayUsesTheHeartbeatArmedFlag`.
- **Mission upload and clear while armed are still allowed** (your call: no block). The GCS asks first and names the mode, and the gateway doesn't care about armed for them. `missionSetCurrentOnlyWhileDisarmed` also asserts MISSION_COUNT stays allowed while armed, so nobody "fixes" this by accident.
- Nothing new is transmitted. The app still never calls MISSION_SET_CURRENT; the rule is ready for resume-from-point.

### What to look at
1. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/TxPolicy.kt:120`: `check()` with the armed rule, and `:94` for why.
2. `feature/plan/src/commonMain/kotlin/com/kft/gcs/feature/plan/PlanUiState.kt:59`: the warning text.
3. `feature/connections/src/commonMain/kotlin/com/kft/gcs/feature/connections/ConnectionsRepository.kt:56`: load at start, and `:76` save on change.

### Tests
- `MavTxGatewayTest` (+2): disarmed → allowed; armed → rejected; unknown → rejected; a LOCKED pod blocks it even when disarmed; MISSION_COUNT is unaffected by armed. The gateway reads the flag at send time.
- `ConnectionManagerTest` (+1): no heartbeat → rejected, disarmed heartbeat → sent, armed heartbeat → rejected, through the real manager.
- `PlanViewModelTest` (+1): the exact strings "Vehicle is ARMED in AUTO: uploading replaces the mission it is flying." and the clear variant. The buttons stay enabled.
- `ProfileFileTest` (5, common):
  - A round trip of all four link kinds (with quotes/backslashes in names).
  - A pinned JSON sample (the file format).
  - Defaults are written out.
  - An empty list stays empty (deleted defaults stay deleted).
  - Empty, truncated, unknown-kind and port-0 files → null.
- `ProfilePersistenceTest` (2, desktop JVM, a real file): add + delete, then a new repository ("restart") sees exactly the saved list. A corrupt file → defaults.
- **Profiles, confirmed in the real app:** before this pass they were **in memory only** (a `ponytail:` note from Pass 4), so they did not survive a restart. After the fix: added "SITL GCS port (TCP 5762)", closed the app, ran `gradlew :app:desktop:run` again, and it was still in Links. Deleting it rewrote the file with explicit ports.
- **Week 2–3 exit check, Plane (ArduPlane 4.8.0-dev SITL, desktop):**
  - Setup: `ArduPlane.elf` (master) from firmware.ardupilot.org/Tools/MissionPlanner/sitl, saved as `ArduPlane.exe`, and `plane.parm` from ardupilot `Tools/autotest/models`. Then `start-sitl.ps1 -Vehicle plane`.
  - Links → SITL (UDP 14550) → "ArduPlane · system 1 · disarmed", about 119 msg/s.
  - Plan → 4 clicks, roughly 400–500 m apart → rows "1–4 Waypoint 100.0 m", **no Takeoff row** → Upload (disarmed: no dialog) → "Uploaded 4 items. Switch to AUTO on the RC to fly it."
  - Pilot (`sitl_pilot.py` on 5763, sysid 254): ARMED → TAKEOFF mode → climbed past 20 m → AUTO.
  - Fly: "ArduPilot 4.8.0-dev", Auto / ARMED / 60 → 100 m / 22.6 m/s, "Mission 1 / 4" → "2 / 4" → … → **"Done"**. The current marker turned magenta. The orange track followed the blue route with the wide Plane turns. ArduPlane's "Mission complete, changing mode to RTL", then it loitered over home.
  - **In flight:** Plan → Upload showed "Upload while armed? Vehicle is ARMED in AUTO: uploading replaces the mission it is flying." Cancel → nothing sent, and the mission carried on. Clear → "Vehicle is ARMED in AUTO: clearing deletes the mission it is flying." Keep.
- `./gradlew check` and `:app:android:assembleDebug` pass. No warnings.

### Open questions / next
- `MAV_CMD_DO_SET_MISSION_CURRENT` (the command form) is still unlisted. Classify it like MISSION_SET_CURRENT when resume-from-point is built.
- The Android profile store isn't checked on a device yet (same code as desktop, different folder). It's in the tablet checklist: add a profile, force-stop the app, reopen.
- The Copter SITL starts from fresh parameters once (new per-vehicle folder).
- **Next: Pass 12, KFT HMAC login.**
