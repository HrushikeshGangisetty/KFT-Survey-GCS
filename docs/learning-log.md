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
