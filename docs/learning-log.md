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

---

## Pass 12 — KFT HMAC login (2026-09-25)

### What changed
- **`docs/spec/01_survey_gcs_feature_spec.md`**: **S12** (KFT login: same fleet key and APP_ID as the Android GCS, key from `local.properties` `KFT_APP_SECRET`, never in git) and **GS-9** (the key is extractable, and one fleet key unlocks all drones; plus three firmware findings).
- **`docs/decisions/ADR-003-kft-login.md`** (new): the design and why. There is no ADR-002 in this repo; the number is the one you asked for.
- **`core/mavlink`**:
  - `BitExactCommandLong.kt` (new): COMMAND_LONG serialized with raw float bits (the NaN bug, below).
  - `MavTxGateway.kt`: sends every COMMAND_LONG through it.
  - `TxPolicy.kt`: MAV_CMD_USER_1/USER_2 (31010/31011) are `ALWAYS`.
- **`core/vehicle`**:
  - `build.gradle.kts`:
    - A `generateKftAppSecret` task writes `build/generated/kftAppSecret/…/KftAppSecret.kt` from `KFT_APP_SECRET` (local.properties, else the environment).
    - A `jvmCommon` source set.
  - `HmacSha256.kt` + `jvmCommonMain/HmacSha256.jvmCommon.kt` (new): `expect`/`actual` over `javax.crypto.Mac`.
  - `KftLogin.kt` (new):
    - `KftLoginStatus` (with the UI label), the `Kft` constants, and `parseKftKey`.
    - `kftResponseParams` (the float packing) and `parseChallengeHalf`.
    - `KftLogin.attempt()` (one handshake).
  - `VehicleRepository.kt`:
    - The connect-time sequence: login first, then the Pass 6 requests.
    - Re-login after a 6 s heartbeat gap, a 2 s debounce, bounded FAILED retries, and a fresh login on every new link.
    - The constructor takes `loginKey` and `timeSource`.
  - `CommandProtocol.kt`: per-call `attempts` / `ackTimeout` (the login must never resend USER_1).
  - `VehicleState.kt`: `login`. KFTCH texts stay out of the pilot's message strip.
  - `MissionRepository.kt`: mission transfers wait for the login.
  - `di/VehicleModule.kt`: `parseKftKey(KFT_APP_SECRET_HEX)`.
- **`feature/fly`**: `LoginUi`, and a login line in the HUD under the firmware version.
- **`feature/connections`**: `ConnectionsRepository.login`, and a login line on the Links card (warning colour for NO_KEY / DENIED / FAILED).
- **Tests**:
  - New: `BitExactCommandLongTest`, `KftCryptoTest`, `KftLoginTest` and `KftAndroidReferenceTest`.
  - Updated: the gateway, VehicleRepository, MissionRepository, Fly and Connections tests, and `SitlCheck`.

### How it works
```
build time:  local.properties KFT_APP_SECRET (or env) ─▶ generateKftAppSecret ─▶ build/generated/…/KftAppSecret.kt
             not 64 hex / missing ─▶ ""                                            (git-ignored, never committed)
start:       parseKftKey(KFT_APP_SECRET_HEX) ─▶ 32 bytes, or null (= NO_KEY)

first vehicle heartbeat on a link (or a heartbeat after a gap > 6 s; at most one start per 2 s)
  └─ session ─▶ login?  no key ─▶ NO_KEY ────────────────────────────────────────────────┐
                        key    ─▶ KftLogin.attempt():                                     │
       listen for STATUSTEXT from the FC (UNDISPATCHED: subscribed before sending)        │
       USER_1(param1=1), one attempt, 3 s ─▶ ACCEPTED ─▶ wait ≤ 8 s for KFTCH1 + KFTCH2   │
                                          ├ DENIED ─▶ DENIED  (no retry)                   │
                                          └ UNSUPPORTED / other / no ACK ─▶ LEGACY_FIRMWARE│
       HMAC-SHA256(key, 32-byte challenge)[0..24) ─▶ 6 × Float.fromBits(LE int)            │
       USER_2(1, f1..f6) as COMMAND_LONG ─▶ gateway ─▶ BitExactCommandLong ─▶ wire          │
         ACCEPTED ─▶ AUTHENTICATED · DENIED ─▶ DENIED · else ─▶ FAILED (retry 2/4/8 s, then stop)
  allowsTraffic (AUTHENTICATED, LEGACY_FIRMWARE, NO_KEY) ─▶ REQUEST_DATA_STREAM, REQUEST_MESSAGE ×2, SET_MESSAGE_INTERVAL
  VehicleState.login ─▶ Fly HUD line, Links card line, MissionRepository gate
```

### Engineering learnings
- **The NaN bug was real.** mavlink-kotlin 1.2.15's `encodeFloat` calls `Float.floatToIntBits` (seen with `javap`), which turns *every* NaN into `0x7FC00000`. A random 4-byte chunk is a NaN pattern about 1 time in 256, so about 2.3% of logins (6 floats) would have been DENIED for no visible reason.
  - I can't patch the library, so `BitExactCommandLong` implements `MavMessage` with COMMAND_LONG's id, CRC extra and byte layout, and writes `toRawBits()`.
  - The gateway swaps it in for *every* COMMAND_LONG, so the fix sits where all callers already pass through, not in the login code.
  - `libraryEncoderCanonicalisesNaN` pins the library behaviour. When a library update fixes it, that test fails and tells us to delete the workaround.
- **`Float.fromBits`, not arithmetic.** The float is only a container for 4 bytes. `fromBits`/`toRawBits` don't look at the value, so the bits go in and come out unchanged.
  - *Caveat:* the JVM spec allows `intBitsToFloat` to quiet signalling NaNs on some old CPUs (x87). x86-64 and ARM64 keep them, and the tests pass on this x86-64 JVM.
  - The golden vector recorded on the Android tablet will be the real-device proof.
- **javax.crypto over KotlinCrypto (ADR-003).** Both targets are JVMs, so the platform provider is already there and audited, and it adds no dependency to a security path. The `expect fun hmacSha256` is the one place an iOS `actual` would go if that target ever arrives.
- **Guaranteed subscription instead of `delay(100)`.** `launch(start = UNDISPATCHED)` runs the collector up to its first suspension, which is *inside* `SharedFlow.collect` after it has registered. So the listener exists before USER_1 is sent, on any dispatcher and at any speed. A delay only makes that likely.
- **StateFlows instead of polling.** `combine(half1, half2).filterNotNull().first()` resumes exactly when the second half lands, with no 100 ms loop. The flows are created fresh per attempt, so a half from an old challenge can't be mixed in.
- **Never resend USER_1.** Each USER_1 makes the firmware draw a new challenge (`generate_challenge()`). A retry after a lost ACK could leave KFTCH1 from challenge A and KFTCH2 from challenge B. So the command protocol gained `attempts = 1` per call, and retries happen at the session level with a fresh listener.
- **Two writers, atomic updates.** The login status is written from the session coroutine while the collector writes telemetry. All `VehicleState` writes are now `_state.update { }` (a compare-and-set loop), so neither can overwrite the other's change. A cancelled session checks `ensureActive()` before writing, so it can't overwrite the state after a disconnect.
- **Firmware facts (S10)**, from `ardupilotKFT` `Copter-4.6.3-kftv7`:
  - `handle_message` drops everything but HEARTBEAT and COMMAND_LONG/INT USER_1/USER_2 before login.
  - USER_1 with an unknown app id answers DENIED. Otherwise it sends `KFTCH1:`/`KFTCH2:` as STATUSTEXT (queued, so they can arrive after the ACK) and answers ACCEPTED.
  - USER_2 is intercepted in `handle_command_long` *before* ArduPilot converts COMMAND_LONG to COMMAND_INT (that would turn param5/6 into integers). USER_2 via COMMAND_INT is DENIED. The six params are `memcpy`d to 24 bytes and compared in constant time.
  - A challenge is valid for 10 s. The login is dropped after **10 s** without *any* heartbeat (your breakdown said 5 s; the source says `KFT_HEARTBEAT_TIMEOUT_MS 10000`). The 6 s re-login is early, not late.
  - Stock ArduPilot answers UNSUPPORTED to USER_1. Confirmed in SITL below.
- **The old app's 500 ms wait after AUTHENTICATED is gone.** `verify_hmac` sets `authenticated = true` before the ACK is sent, so the next message is already accepted. The 50 ms wait before USER_2 is gone too (guaranteed subscription).

**Ponytail review:** one fix. `parseChallengeHalf` compared the hex length with `CHALLENGE_BYTES`, which was right only because 16 bytes happen to be 32 hex characters. It now says what it means. Kept:
- `BitExactCommandLong`: the correctness fix, with a test that tells us when it can go.
- The per-call protocol parameters: the login needs them.
- The status enum's `warning` flag: both screens use it.
- All tests (§7).
- Rejected alternative: KotlinCrypto would have been a new dependency for no gain on two JVM targets.

### Safety
- **Allowlist change:** `MAV_CMD_USER_1` (31010) and `MAV_CMD_USER_2` (31011) are `ALWAYS`, in every pod and armed state. They move nothing, and a KFT FC ignores the GCS until they succeed. USER_3 and the rest stay unlisted. Test: `MavTxGatewayTest.kftLoginCommandsAreAlwaysAllowed`.
- **Encoding change:** every COMMAND_LONG now leaves bit-exact (`BitExactCommandLong`). For non-NaN values the bytes are identical to before (`sameBytesAsTheLibraryForOrdinaryValues`). The only difference is that NaN parameters keep their payload.
- **Order of transmissions:** on a new vehicle the GCS now sends only the login until it resolves. The Pass 6 requests (REQUEST_DATA_STREAM, REQUEST_MESSAGE, SET_MESSAGE_INTERVAL) follow AUTHENTICATED / LEGACY_FIRMWARE / NO_KEY. After DENIED or FAILED nothing more is sent, and mission transfers are refused with the login status as the reason.
- **Key handling:**
  - Never logged, never in a `toString`. `KftLogin` keeps it private, and the status enum carries labels only.
  - Never committed: the generated file is under `build/`, and `git grep` over the staged tree found no key bytes.
  - The test key I used for the UI check was random, never the fleet key, and was removed again by a rebuild without it.
  - I did **not** add your key to `local.properties`. There's no `KFT_APP_SECRET` there yet, so the app shows "KFT login: no key configured" until you add it.
- **Firmware findings (GS-9), yours to take to the firmware side:**
  - `ardupilotKFT/libraries/GCS_MAVLink/KFT_GCSAuth.h` contains both app keys in plain text, committed to that repo. I read past them and copied them nowhere.
  - The login is global: once any GCS authenticates, every link to that FC is unlocked (MAVProxy on another port included).
  - `note_heartbeat()` counts heartbeats from any sender, so any heartbeat source keeps the unlock alive.

### What to look at
1. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/KftLogin.kt:116`: `attempt()`, the handshake, and `:73` for the float packing.
2. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/VehicleRepository.kt:118`: when a session starts (gap, debounce), and `:144` for the bounded retries.
3. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/BitExactCommandLong.kt:26` and `MavTxGateway.kt:76`: the NaN fix.

### Tests
- `KftCryptoTest` (4, common):
  - RFC 4231 cases 1–7, also checked with Python's `hmac` while writing them.
  - The key must be 64 hex characters (31/33 bytes and non-hex are rejected; whitespace is trimmed).
  - Challenge halves in the firmware's exact format.
  - The packing, byte by byte against the MAC.
- `KftAndroidReferenceTest` (desktop JVM):
  - 2000 seeded random (key, challenge) pairs, ours against javax + `ByteBuffer.order(LITTLE_ENDIAN).getFloat()` (the Android way), compared as raw bits. The test asserts it actually met NaN patterns (it expects about 47).
  - **`goldenVectorFromTheAndroidApp`: @Ignore placeholder.** Fill in the challenge hex and the six floats' raw bits from one real Android login. The pair is safe to commit, and the test takes the key from your `KFT_APP_SECRET`.
- `BitExactCommandLongTest` (3, common):
  - The library canonicalises NaN (pinned).
  - Our bytes equal the library's for ordinary values, v1 and v2 truncation.
  - **The test you asked for:** a COMMAND_LONG with 0x7FA12345 / 0x7FC00001 / 0xFFFFFFFF / +inf / 1.0 / denormal goes through the gateway and a real mavlink-kotlin frame encoder. The frame bytes hold exactly those patterns, and decoding gives them back.
- `KftLoginTest` (11, common, fake ardupilotKFT, virtual time):
  - Accepted: USER_1, then USER_2, then streams, in that order, with APP_ID 1.
  - Wrong key → DENIED, one attempt, nothing else sent.
  - Unknown app id → DENIED.
  - **Missing KFTCH2** → FAILED at exactly 8 s, then 4 attempts in total and nothing more after 10 minutes.
  - UNSUPPORTED → LEGACY_FIRMWARE with streams requested, and USER_1 is never resent.
  - No ACK → LEGACY at exactly 3 s.
  - No key → NO_KEY with no USER_1, and streams still requested.
  - **Heartbeat gap:** 5 s → no re-login; 7 s → re-login and streams again.
  - A vehicle reappearing after 9 s → exactly one re-login.
  - A new link → a new login.
  - A forged KFTCH1 from another component is ignored, and KFTCH never reaches the message strip.
- Also: `MavTxGatewayTest.kftLoginCommandsAreAlwaysAllowed`, `MissionRepositoryTest.missionTransfersWaitForTheKftLogin`, `VehicleRepositoryTest` (a 3 s dropout doesn't re-request, a 7 s one does), `FlyViewModelTest.hudShowsTheKftLogin`, and `ConnectionsViewModelTest.linkCardShowsTheKftLogin`.
- **Build without a key (CI):** `./gradlew check` and `:app:android:assembleDebug` pass, and the generated constant is `""`.
  - With `KFT_APP_SECRET=<random 64 hex>` the constant holds it.
  - With `KFT_APP_SECRET=not-hex` it's `""`.
  - `git check-ignore` confirms the generated file is ignored.
- **Stock SITL, the LEGACY path (ArduCopter 4.8.0-dev):**
  - `KFT_SITL=127.0.0.1:5762 gradlew :core:vehicle:jvmTest --tests '*SitlCheck*'` → "SITL: ArduPilot 4.8.0-dev, home … 584.09, KFT login: not needed (firmware without KFT login)".
  - Then the full mission upload (1/6…6/6), read-back comparison and clear passed. That's two of two tests, with the login attempted using a dummy key.
- **Desktop UI, same SITL, with a random test key compiled in:**
  - Links card: "ArduCopter · system 1 · disarmed / 113 msg/s · 0.0% loss / KFT login: not needed (firmware without KFT login)".
  - Fly HUD: "ArduPilot 4.8.0-dev", then "KFT login: not needed (firmware without KFT login)", and telemetry flowing (the Pass 6 requests went out after the login).

### Your checklist: first real login
1. Add `KFT_APP_SECRET=<the Android GCS's 64 hex characters>` to `local.properties`. It's git-ignored, and the name matches the Android project.
2. `gradlew.bat :app:desktop:run`, then connect to a KFT flight controller over USB/serial. Expect Links → "KFT login: in progress…" → "KFT login: OK", then telemetry, and ArduPilot's own "KFT: KFT_GCS_Android authenticated" in the message strip.
3. Plan → Upload should work. Before this pass, a KFT FC dropped it.
4. Pull the cable for more than 6 s, then plug it back in. Expect "in progress…" → "OK" again.
5. Record the golden vector (see the test's KDoc) from the Android app, and send it to me.

### Is SITL built from your KFTV7 fork practical for a real end-to-end check?
**Possible, but not cheap. A bench flight controller over USB is the faster real check.** Reasons, from the fork:
- **The POST reads flash at a hardware address.** `KFT_GCSAuth::verify_firmware_post()` hashes memory at `KFT_APP_BASE`, and there's no SITL guard in `KFT_GCSAuth.cpp`. It runs on a successful login (`send_post_status()`) and in the Copter arming checks (`AP_Arming.cpp:50`). In SITL that address isn't a flash image, so expect a crash or a permanent "POST: FAILED" at the moment of login.
  - The fork needs a small SITL-only change (`#if CONFIG_HAL_BOARD == HAL_BOARD_SITL`: skip POST, report "SITL"). That's a firmware change your certification note says needs care ("DO NOT modify without recertification"). A SITL-only `#if` doesn't change the flight binary, but that's your call.
- **The toolchain:** ArduPilot SITL builds on Linux (waf). This machine has an "Ubuntu" app entry, but the WSL service isn't installed (`Wsl/ERROR_SERVICE_DOES_NOT_EXIST`), so it's WSL2 setup + `Tools/environment_install/install-prereqs-ubuntu.sh` + `./waf configure --board sitl && ./waf copter`. That's about an hour, once.
- **What it would buy:** the handshake code (`GCS_Common.cpp`, the `KFT_GCSAuth.cpp` HMAC) is portable C++ and would run unchanged in SITL. A KFTV7 SITL would be a repeatable CI-able end-to-end test of exactly this pass.
- **Suggestion:** do step 2 above on a bench FC first; it needs nothing new. Set up the KFTV7 SITL later, if you want the login in automated tests.

### Open questions / next
- The golden vector (placeholder test, waiting on you).
- The Android device's float path (ARM64) is covered only by the golden vector check on the tablet.
- `ponytail:` other float-carrying messages (for example PARAM_SET of a NaN) still go through the library encoder. Only COMMAND_LONG needed raw bits. Extend `BitExact…` if another message ever carries bit patterns.
- GS-9 and the firmware findings above.
- **Next: Pass 13, the survey maths.**

---

## Pass 13 — Survey maths in `core:planning` (2026-09-25)

### What changed
- **`core/geo/LocalProjection.kt`** (new): `LocalProjection` and `LocalPoint`, a flat metre map around an origin (the "local projection" `core:geo` was always meant to hold). `Geodesy.kt`'s KDoc now points to it.
- **`core/planning/Camera.kt`** (new): `Camera`, `CameraOrientation`, `Footprint`; `gsdM`, `altitudeForGsdM`, `footprint`, `lineSpacingM`, `triggerDistanceM`.
- **`core/planning/SurveyGrid.kt`** (new):
  - `GridSpec`, `EntryCorner`, `Turnaround.Copter` / `Turnaround.Plane`, `Pass`, `SurveyGrid`, `SurveyStats`.
  - `buildSurveyGrid`, `surveyStats`, `polygonAreaM2`, `planeTurnRadiusM`, `planeTurnM`.
- **Tests**: `LocalProjectionTest` (5), `CameraTest` (5), `SurveyGridTest` (14).
- Pure throughout: no I/O, coroutines, clocks or platform code (CLAUDE.md §2). Nothing outside `core:planning` uses it yet; the Plan screen's survey tool is the next step.

### How it works: the maths in plain language
**1. What one photo sees (pinhole camera).** Light through the lens makes two similar triangles: sensor ↔ lens and ground ↔ lens. So ground width / height = sensor width / focal length.
- Footprint across = sensor width × height / focal length. The same for the other side with the sensor height.
- **GSD** (ground sample distance, how much ground one pixel covers) = footprint / pixels = height × sensor width / (focal length × image width).
- Turned round: height for a wanted GSD = GSD × focal length × image width / sensor width.
- *Landscape* means the image's long side lies across the flight line. Portrait swaps the two footprints.

**2. How far apart the lines and photos are.** Overlap is the fraction of each photo that the next one repeats, so only (1 − overlap) of it is new ground.
- Line spacing = footprint across × (1 − side overlap).
- Trigger distance = footprint along × (1 − front overlap).

**3. Laying lines over the area.**
1. Flatten the corners onto a local map in metres.
2. Turn the map so the lines run straight "up" (the grid angle: 0° = north–south lines, 90° = east–west).
3. Measure the area's width sideways to the lines. Use n = ⌈width / spacing⌉ lines, exactly one spacing apart, centred.
   - The outer lines end up at most half a spacing from the edge. Each photo reaches half a footprint sideways, and half a footprint is always more than half a spacing, so the edges are covered.
   - Anything narrower than one spacing gets a single line down the middle.
4. Cut each line where it crosses the area's edges. Sorted crossings pair up as in → out, in → out. A concave area can give one line two or more pieces.
5. Fly them back and forth (boustrophedon), starting from the chosen corner.
6. Add the run-in/run-out outside the area.

**4. Turning, Copter vs Plane.**
- A **copter** stops, turns and hops straight to the next line. Its optional *extension* is straight flight before and after the area, so it's at speed for the first photo.
- A **plane** can't stop. It flies a *lead-in* before the area (to settle after the turn) and a *lead-out* after, then turns with radius r = v² / (g·tan bank).
  - Lines at least 2r apart: a U-turn, πr plus the straight bit across.
  - Lines closer than 2r: the plane has to swing out the other way and loop back (a "bulb" turn), r·(π + 4γ) with cos γ = (spacing + 2r) / 4r.
  - The two formulas give the same length at exactly 2r, so the cost has no jump. Just below 2r the bulb grows quickly (like a square root).

**5. Stats.**
- Area: the shoelace formula on the flat map.
- Photos per pass: ⌊length / trigger distance⌋ + 1. ArduPilot's distance trigger fires once when it's switched on, then every d metres.
- Distance: passes + run-ins/outs + the legs between passes.
- Flight time: distance / speed.

### Worked example (it's the test `rectangleWithNorthSouthLines`; check it by hand)
DJI Phantom 4 Pro: sensor 13.2 × 8.8 mm, 5472 × 3648 px, focal length 8.8 mm. 100 m above ground, landscape, 70 % side / 80 % front overlap. Area 300 m east–west × 200 m north–south, north–south lines (grid angle 0), copter at 10 m/s.

| Step | Sum | Result |
|---|---|---|
| GSD | 100 × 13.2 / (8.8 × 5472) = 1320 / 48 153.6 | **0.0274 m = 2.74 cm/px** |
| Footprint across | 13.2 × 100 / 8.8 | **150 m** |
| Footprint along | 8.8 × 100 / 8.8 | **100 m** |
| Line spacing | 150 × (1 − 0.70) | **45 m** |
| Trigger distance | 100 × (1 − 0.80) | **20 m** |
| Lines | ⌈300 / 45⌉ = ⌈6.67⌉ | **7** |
| Placement | 7 lines span 6 × 45 = 270 m; (300 − 270) / 2 = 15 m spare each side | x = **15, 60, 105, 150, 195, 240, 285 m** |
| Photos | per 200 m line ⌊200 / 20⌋ + 1 = 11; × 7 | **77** |
| Distance | 7 lines × 200 m + 6 hops × 45 m = 1400 + 270 | **1670 m** |
| Flight time | 1670 / 10 | **167 s** (2 min 47 s) |
| Area | 300 × 200 | **60 000 m² = 6 ha** |

Coverage check: the outer line is 15 m from the edge, and each photo reaches 150 / 2 = 75 m sideways, so the edge is well covered.

The same area as a **Plane** (turn radius 50 m, lead-in 30 m, lead-out 20 m):
- Each pass is 30 + 200 + 20 = 250 m.
- The lines are 45 m apart, less than 2r = 100 m, so each turn is a bulb: cos γ = (45 + 100) / 200 = 0.725, γ = 0.7595 rad, turn = 50 × (π + 4 × 0.7595) = 309.03 m.
- The next line's lead-in starts 10 m further out than this line's lead-out ended (30 vs 20), so each leg is 319.03 m.
- Total 7 × 250 + 6 × 319.03 = **3664.19 m**, over twice the copter's distance. That's the cost of a plane on narrow spacing (see Open questions).

### Engineering learnings
- **Flat map, then plain geometry.** Every step after the projection is 2-D school maths: rotation, line crossings, the shoelace formula. The projection is the only spherical part. Its error is tested against the haversine distance and bounded in its KDoc (≈ 2 cm per km at CMAC's latitude). *Why not work in lat/lon directly:* spacing is in metres, and a degree of longitude isn't a fixed number of metres.
- **Scan-line crossing rule.** An edge counts when its ends are on different sides of the line, with "on the line" counted as below. A vertex exactly on a line is then counted once or not at all, so crossings always pair up. No special cases for touching a corner.
- **Centred lines instead of lines from the edge.** Lines starting exactly on the boundary hit single vertices (a zero-length "line" at a triangle's tip). Centring keeps every line strictly inside the width, spacing is exactly what the overlap asks for, and coverage of the edges is guaranteed (above).
- **Where we differ from QGC, on purpose** (checked in QGC master 2026-09, `src/MissionManager/CameraCalc.cc` and `SurveyComplexItem.cc`; read, not copied):
  - The camera formulas are the same. QGC computes along-track footprint as image height × GSD, which assumes square pixels. We use the sensor height directly, which also handles non-square pixels.
  - QGC starts its sweep lines at the bounding box's centre minus a margin, so the first line's distance from the edge is arbitrary. We centre them in the width.
  - QGC's `_intersectLinesWithPolygon` keeps only the two furthest crossings, so over a concave notch it photographs the notch as if it were inside. We keep each inside piece as its own pass (`concaveAreaGetsSeparatePassesPerArm`).
  - Entry corner and turnaround extension work the same way (reverse the line order and/or the first direction; extend the ends along the line).
- **The Plane turn is a model, and it's labelled as one.** It's the shortest (Dubins) 180° turn between parallel lines, plus any along-line offset flown straight. Real ArduPlane turns (L1/TECS, wind) will be longer. It's for estimates and for noticing when spacing < 2r, not for flying.
- **Hand values first, then code.** Every expected number in the tests was worked out on paper (and cross-checked in Python) before running the code. Two tests failed on the first run, and both were my tolerances, not the maths:
  - The projection's diagonal error was 2 cm per km, not the 1 cm I'd guessed, and the KDoc's own error formula predicts exactly that.
  - The plane-turn continuity check used too large a step for a function that rises like a square root.

  Both tolerances now come from the formula, not a guess.

**Ponytail review:** one cut, `MAX_LINES` made `internal` (only the grid uses it). Kept: `LocalProjection` in `core:geo` (the module table puts local projections there, and planning needs it), and `GridFrame` (the rotation both ways, used for every point). Every public function is one formula you asked for.

### What to look at
1. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/SurveyGrid.kt:111`: `buildSurveyGrid`, with the six steps in its KDoc. `:122` is the line count and centring.
2. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/SurveyGrid.kt:268`: the scan-line clip.
3. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/Camera.kt`: the camera formulas, one line each.

### Tests
- `LocalProjectionTest` (5):
  - 0.001° = 111.1951 m at the equator, and 55.5975 m east–west at 60°.
  - Within the predicted error of haversine over 1 km at CMAC.
  - A round trip back to lat/lon.
  - Across ±180°: 0.2° = 22 239 m.
- `CameraTest` (5, P4P):
  - GSD 0.027412 m at 100 m; 72.96 m for 2 cm/px, and the inverse.
  - Footprint 150 × 100 m (portrait 100 × 150).
  - Spacing 45 m, trigger 20 m.
  - Overlap 1.0, negative overlap and a zero focal length are rejected.
- `SurveyGridTest` (14):
  - **The worked example**: 7 lines at x = 15…285, alternating direction; 77 photos, 1670 m, 167 s, 60 000 m².
  - **Grid angle 90**: 5 lines at y = 190, 145, 100, 55, 10; 1680 m.
  - **Grid angle 45** on a 100 m square: 5 chords of 21.42 / 81.42 / 141.42 / 81.42 / 21.42 m (the diamond formula), the middle one exactly corner to corner.
  - **Concave U**: 4 lines, 6 passes (two arms on the upper lines), photo length 1000 m, 1380 m in total, straight across the notch.
  - **Very thin** 1000 × 10 m: 1 centre line at y = 5, 51 photos.
  - **Very small** 5 × 5 m: 1 line, 1 photo, 25 m².
  - **Entry corners**: all four start where named.
  - **Copter extension** 10 m: entry at y = −10, exit at y = 210, 1810 m.
  - **Plane**: lead-in/out positions (including the southbound line's lead-in north of the area), legs of 319.032 m, 3664.19 m.
  - **Plane turn lengths**: 157.080 / 207.080 / 366.519 / 301.626 m, continuous at 2r, never shorter for closer lines. **Turn radius** 70.648 m at 20 m/s and 30° bank.
  - **Area at CMAC latitude**: 60 000 m² within 5 m².
  - **Rejected**: 2 corners, collinear corners, spacing 0, 3000 lines, a polygon crossing the **antimeridian**. Accepted: an area right next to it.
- `./gradlew check` and `:app:android:assembleDebug` pass, with no compiler warnings in the touched modules.

### Open questions / next
- `ponytail:` flight time uses constant speed, with no copter slow-down at turns and no wind. Add a per-turn allowance once SITL or field logs show how far off it is.
- **Plane on narrow spacing:** bulb turns more than double the distance in the example. QGC offers "fly alternate transects" for fixed wing (skip lines so each turn is at least 2r). It's a line-order option on top of this code, worth adding with the Plane survey UI.
- Not built (yet): crosshatch (spec P1), camera trigger mission items (`DO_SET_CAM_TRIGG_DIST`), terrain following, and a camera database (GS-5: we still need KFT's actual survey payload).
- **Next (waiting for you):** the Plan screen's survey tool: draw a polygon → grid on the map → stats → mission items with the camera trigger → upload → SITL flies it (the weeks 4–5 prototype).

---

## Pass 14 — Camera list, Plane line order, stats (2026-09-25)

### What changed
- **`core/planning/Camera.kt`**: `Camera` gains `minTriggerIntervalS`, `mbPerPhoto`, `name` and `unverified`, all with defaults, so every Pass 13 call still compiles unchanged. Landscape/portrait stays a per-survey choice (`CameraOrientation`), as in QGC: the same camera can be mounted either way.
- **`core/planning/Survey.kt`** (new): `SurveyHeight` (altitude-first or GSD-first), `SurveyParams`, `SurveyLimits`, `SurveyWarning`, `SurveyPlan` and `planSurvey()`: one pure function from the operator's choices to everything the panel shows.
- **`core/planning/SurveyGrid.kt`**: Plane lines closer than 2 × turn radius are flown every k-th line (`minLineSkip`, `planeLineOrder`). `SurveyGrid` gains `lineSkip` and `loopTurns`.
- **`core/geo-io`**: kotlinx.serialization added. `Cameras.kt` (new) has the bundled presets as JSON (all `"unverified": true`), `CameraEntry` (the on-disk shape) and `decodeCameras`.
- **`feature/plan/PlanSettings.kt`** (new): `PlanSettings` (custom cameras, battery minutes per vehicle kind, GSD warning limit), `SettingsStore` (a text blob, like the connection profiles' store) and `PlanSettingsRepository`. The survey panel and the file behind the store arrive in Pass 15.
- **`docs/spec/01_survey_gcs_feature_spec.md`**:
  - §1: an ArduDeck column and a planning-UX note (GPL-3.0: ideas and screenshots only).
  - §2: rows for this batch's P0 items, ArduDeck's "later" items (autosave P1; circular/spiral grids, flight preview, pasted ground points P2), phone layout moved to P1, `.plan`/`.waypoints` as plain items moved to P0, and "Photos taken" moved to P0.
- **Tests**:
  - New: `SurveyTest` (5), `CamerasTest` (2), `PlanSettingsTest` (3).
  - `SurveyGridTest`: the old Plane test (every turn a loop, 3664.19 m) is replaced by four Plane-order tests and a property test.

### How it works
```
SurveyParams ──planSurvey()──▶ height: Altitude(h) → GSD = camera.gsdM(h) · Gsd(g) → h = camera.altitudeForGsdM(g)
  (polygon, camera,             footprint(h, orientation) ─▶ spacing = across × (1 − side) · trigger = along × (1 − front)
   overlaps, angle, entry,      buildSurveyGrid(GridSpec) ─▶ Plane and spacing < 2r? ─▶ k = ⌈2r / spacing⌉, planeLineOrder(n, k)
   speed, turnaround)           surveyStats ─▶ batteries = ⌈time / usable time⌉ · data = photos × MB
                                warnings: interval < camera minimum · GSD > limit · plane loops left
                           ──▶ SurveyPlan (everything the panel shows, plus the grid the mission is built from)
```

**The Plane order, worked by hand** (it's `planeFliesEveryThirdLineSoNoTurnNeedsALoop`). Same 300 × 200 m area, 45 m spacing, turn radius 50 m, lead-in 30 m, lead-out 20 m:
- Every other line isn't enough: 90 m < 2r = 100 m, so those turns would still be loops. Your brief said "every other line"; that's the k = 2 case of the general rule, and this example needs k = ⌈100 / 45⌉ = 3.
- Nearest allowed line first, from line 0: 0 → 3 → 6 → 2 → 5 → 1 → 4. The jumps are 135, 135, 180, 135, 180 and 135 m, all ≥ 100 m, so there are no loop turns.
- Turns: π·50 + (lateral − 100) = 192.08 m or 237.08 m, plus 10 m along the line each time (lead-in 30 m vs lead-out 20 m).
- Distance: 7 × 250 + 4 × 202.08 + 2 × 247.08 = **3052.48 m**, down from 3664.19 m with loops (−17 %).

### Engineering learnings
- **GSD-first is the same formula backwards.** `SurveyHeight` is a two-case sealed type rather than two nullable fields, so "both set" or "neither set" can't be written. The panel stores which one the operator typed, and `planSurvey` derives the other.
- **Why "every k-th line" and not QGC's "alternate transects".** QGC flies every other line out, then the rest back. That has two limits:
  - k = 2 doesn't cover 2r > 2 × spacing (this example).
  - The switch-over turn is between neighbouring lines, so it's a loop.

  Here, "consecutive lines at least k apart" is a path through a graph, found by depth-first search, nearest line first. Nearest-first keeps transit short and almost never backtracks. The search is capped at 200 000 steps.
  - The property test checks it: every size up to 80 lines and k = 2…6 gives a valid order, and one always exists from 2k + 1 lines.
  - At exactly 2k lines no order can start at line 0: two lines have a single allowed partner, so they must be the two ends. Then the grid flies in order and **reports** the loops, as a warning rather than silently.
- **One pure entry point.** `planSurvey` is the only function the Plan screen calls. The screen never chains camera, spacing, grid and stats itself, so there's one place where GSD-first and altitude-first can't disagree.
- **Warnings, not errors.**
  - Too short an interval: ArduPilot (`AP_Camera_Backend::take_picture`, master 2026-09) *holds back* a photo requested sooner than `CAM1_INTRVAL_MIN` after the last one and takes it once the interval has passed (corrected in Pass 16; this said "skips"), so the photos end up further apart than the overlap needs. That's a coverage gap, not a crash, so it's a warning with the fix in it (the fastest speed that works).
  - GSD above the limit and leftover plane loops are warnings too: the plan is still flyable.
- **Presets as JSON in a Kotlin string**, not a resource file. KMP Android libraries have no common resource loader without adding Compose resources. A `const val` is bundled, needs no I/O, and still reads as plain JSON for whoever checks the numbers. It lives in `core:geo-io`, so `core:planning` stays pure.
- **Battery rounding:** the distance comes through the map projection, so 1670 m is 1670.000 00x m. `⌈167 / 83.5⌉` came out as 3 until the slack went from 1e-9 to 1e-6 of a battery. The first test run caught it.

**Ponytail review:** lean already. `encodeCameras` was cut before the review (custom cameras go through the settings file). `SettingsStore` is kept (the repository's test seam, §7).

### What to look at
1. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/SurveyGrid.kt:214`: `planeLineOrder`, and `:139` where the grid uses it.
2. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/Survey.kt:73`: `planSurvey`, the whole chain in 30 lines.
3. `core/geo-io/src/commonMain/kotlin/com/kft/gcs/core/geoio/Cameras.kt:14`: the preset list to check against spec sheets.

### Tests
- `SurveyGridTest`:
  - `planeFliesEveryThirdLineSoNoTurnNeedsALoop`: the hand example above, with pass positions, legs and total.
  - `planeFliesEveryOtherLineWhenThatIsEnough`: r = 40 m → k = 2, order 0, 2, 4, 6, 3, 1, 5.
  - `planeWithWideSpacingFliesLinesInOrder`: r = 20 m.
  - `tooFewLinesToSkipKeepsTheLoopsAndCountsThem`: 3 lines → 2 loops.
  - `planeLineOrderIsAlwaysValid`: the property test.
- `SurveyTest`:
  - Altitude-first on the worked example: batteries ⌈167 / 60⌉ = 3, data 77 × 8 = 616 MB, interval 2.0 s < 2.5 s → max 8 m/s, GSD 2.74 > 2.5 cm.
  - GSD-first at 2 cm: altitude 72.96 m, footprint 109.44 m, spacing 32.832 m, trigger 14.592 m, no warnings.
  - Exactly 2 batteries.
  - Plane loops → warning.
  - Bad inputs rejected.
- `CamerasTest`: the bundled list parses, every entry is unverified, the P4P GSD equals the hand value, and broken input → null.
- `PlanSettingsTest`: settings survive a restart, saving by name replaces, and a broken file → defaults.
- `./gradlew check` passes with no warnings.

### Open questions / next
- **GS-5 stays open.** The presets are unverified, and none is KFT's payload.
- "Per-vehicle" battery time is per vehicle *kind* (Copter / Plane). Per airframe (by system id or name) is possible later if one fleet has very different batteries.
- The Plane turn radius comes from speed and bank (`planeTurnRadiusM`). Real ArduPlane turns (`NAVL1_PERIOD`, wind) are wider, so the Pass 16 SITL run will show how much margin k needs.
- **Next: Pass 15**, the survey on the Plan screen.

---

## Pass 15 — Survey on the Plan screen (2026-09-26)

### What changed
- **`feature/plan`** (most of the pass):
  - `MissionGroups.kt` (new): the mission as ordered groups.
    - `WaypointGroup` (the Pass 8 editor) and `SurveyGroup` (keeps its `SurveySettings`).
    - `flatten()`: the only place the flat item list is made (upload, export, preview, map route).
    - `surveyItems()`, `defaultSurvey()`, and the group header line.
  - `PlanDocument.kt` (new):
    - Our own `.kftplan` JSON: groups plus survey parameters, with its own file DTOs.
    - `insertCorner()`: where a clicked corner goes.
  - `PlanFiles.kt` (new): the platform's open/save dialogs as an interface.
  - `PlanUiState.kt` (rewritten):
    - Group headers, the survey panel, the sync label, undo/redo flags and the upload preview.
    - `PlanEdit`: the editor's single immutable state, including the history.
    - `buildPlanUiState` stays pure.
  - `PlanViewModel.kt` (rewritten):
    - Groups, corners, survey fields, height mode, cameras, settings.
    - Undo/redo, upload preview → confirm, read and clear.
    - Save, open (all three formats) and export.
  - `PlanScreen.kt` (rewritten):
    - `PlanActions` (the events), a floating toolbar, the group list, the waypoint editor and the survey panel.
    - Custom-camera, rename, clear and upload-preview dialogs.
    - Our own line icons: a disk for files, arrows for the vehicle.
  - `PlanItems.kt`: `addWaypoint(startsMission)` (only the first group gets the automatic copter takeoff) and a shared `speedItem`.
  - `di/PlanModule.kt`: `PlanSettingsRepository` and the new ViewModel dependencies.
- **`core/vehicle`**:
  - `MissionSync.kt` (new): the plan and what the vehicle holds, shared by Plan (writes) and Fly (reads), with `sameMission()`.
  - `Mission.kt`: `DO_SET_CAM_TRIGG_DIST = 206`.
  - Koin: `single { MissionSync() }`.
- **`core/geo-io`**:
  - `MissionFiles.kt` (new): QGC `.plan` and Mission Planner `.waypoints`, encode and decode, as plain items with home.
  - It now depends on `core:vehicle` for `MissionItem` (see Decisions).
- **`ui/map`**:
  - `MapOverlay.Polygon` (fill plus outline, the selected one stronger), `MapOverlay.Photos` (for Pass 16), and `MarkerStyle.CORNER` (smaller handles).
  - `onMarkerDragEnd`, so one drag is one undo step.
- **`feature/fly`**: "Vehicle mission ≠ plan" under the HUD title, from `MissionSync`.
- **`app`**:
  - `FileProfileStore` → `FileTextStore`: one class for both text stores, and a second file, `plan-settings.json`.
  - `DesktopPlanFiles` (AWT `FileDialog`) and `AndroidPlanFiles` (the system document picker, no storage permission).
  - `KeyShortcuts`: the desktop window passes Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y to the Plan tab.
  - `activity-compose` added to `app:shared` androidMain. It was already in the version catalog, so no version change.
- **`CLAUDE.md` §2**: the `core:geo-io` row now lists `core:vehicle`.

### How it works
```
map click / drag / panel field ──▶ PlanViewModel ──▶ PlanEdit (groups, selection, past/future) ──┐
                                                       one edit = old groups pushed on `past`       │
                                                       same key (a drag, one field) = same step     │
vehicle, settings, MissionSync ────────────────────────────────────────────────────────────────────┤
                                                                                                    ▼
                           buildPlanUiState: flatten(groups) ─▶ items + seq numbers + SurveyPlans
                             ├─ overlays: route (home + every item), polygons, corners, numbered markers ─▶ one MapView
                             ├─ panel: group headers "1.7 km · 3 min 34 s · 1.4 cm/px", survey fields, stats, warnings
                             └─ sync: sameMission(flat items, what the vehicle holds) ─▶ "Not uploaded" / "On vehicle"
Upload ─▶ uploadPreview (0 = home, 1…n, warnings) ─▶ Upload ─▶ MissionRepository (home = seq 0, S11)
        ─▶ MissionSync.vehicleHolds(items) ─▶ Fly: warns "Vehicle mission ≠ plan" after any later edit
```
**A survey's items** (`surveyItems`, checked against ArduPilot `AP_Mission`): `[TAKEOFF]` (copter, first in the mission), `DO_CHANGE_SPEED`, then per pass `WAYPOINT entry` → `WAYPOINT photoStart` → `CAM_TRIGG_DIST(d, shoot now)` → `WAYPOINT photoEnd` → `CAM_TRIGG_DIST(0)` → `WAYPOINT exit`, then `[RETURN_TO_LAUNCH]`.
- A DO_ command runs when the NAV item before it is reached, so the camera runs from edge to edge and is off in the turns.
- For the 300 × 200 m example: 2 + 7 × 6 + 1 = 45 items (`copterSurveyItems`).

### Engineering learnings
- **Groups are the document; items are a view of it.** A survey stores its settings, not its waypoints, so changing the overlap regenerates every line. Upload, export, the map route and the preview all call the one `flatten()`, so they can't disagree about seq numbers.
- **Undo on an immutable state.** The whole plan is one immutable value (`List<MissionGroup>`), so an undo step is a reference to the old list, not a diff to replay. Two decisions make it feel right:
  - Coalescing: a key joins repeats of the same edit into one step. That's the one drag, or typing "12.5" into one field.
  - Drag end: the map reports when a drag ends. That ends the step, so two separate drags of the same corner are two undos. The first test run caught this: selecting a row after each move had been resetting the key, so every mouse-move was its own step.
- **Shortcuts at the window, not in the UI tree.** My first version listened for keys on a focused box in `App()`. In SITL, Ctrl+Z stopped working after pressing Read: the focused button was disabled during the transfer and Compose moved focus to the window root, outside the box. The desktop `Window`'s own `onPreviewKeyEvent` sees every key whatever has focus, so `KeyShortcuts` hands it the Plan handler. The tablet has the Undo/Redo buttons.
- **"Not uploaded" is a comparison, not a flag.** A flag would need clearing on every code path that edits. Instead the screen compares the flat items with what the vehicle is known to hold.
  - `sameMission` compares in the wire's precision: positions in 1e-7° steps, altitudes and params as Float, and the frame only for items with a position (ArduPilot reads DO_ items back as AMSL).
  - That's why an undo back to the uploaded plan shows "On vehicle" again, and why a Read of our own upload compares equal. SITL confirmed both.
  - It also catches MAVProxy changing the mission behind our back, through MISSION_CURRENT's total.
- **`DO_SET_CAM_TRIGG_DIST` param2 = 0 on purpose.** ArduPilot stores only param1, param3 and param4 (`AP_Mission.cpp`). Sending anything in param2 would make every read-back "differ".
- **Files are told apart by content.** A `.plan` renamed to `.txt` still opens, and a QGC Survey (a "ComplexItem" only QGC can expand) is counted and reported rather than silently dropped.
- **A platform dialog behind a suspend function.**
  - Desktop: AWT's `FileDialog` is modal and runs its own event loop, so calling it on the UI thread (`viewModelScope`) is correct.
  - Android: the document picker is an activity result, which only composition can launch. `RegisterPlanFiles` wires the launchers, and `save()` / `open()` just await a `CompletableDeferred`.
- **Layout (ideas from ArduDeck's screenshots, nothing copied):** map-first, a floating toolbar with file and vehicle actions visibly apart, a right-hand panel whose group headers carry distance, time and GSD, and a sync label that turns orange on edit.

**Ponytail review:** one cut. `MissionSyncState.uploaded` only fed tests; the screens use `sameMission` / `differsFrom` (−2 lines). Kept:
- `PlanActions`: it keeps `PlanScreen` stateless and previewable (§3).
- `PlanFiles` and `SettingsStore`: platform seams.
- `KeyShortcuts`: 5 lines, and the only way to reach the window's keys.
- The file DTOs: the format stays separate from editor types.

### Decisions you should know about
- **`core:geo-io` → `core:vehicle`** (CLAUDE.md §2 table updated). A mission file *is* a list of `MissionItem`s, and `geo-io` is where §2 puts `.plan` parsing. The alternative, parsing in `feature:plan`, would have left the file formats untestable without Compose.
  - The cost: `geo-io` pulls mavlink-kotlin in transitively (the public API is still plain values).
  - To undo it later, move `MissionItem` into a pure module.
- **Takeoff:** only the group that starts the mission gets an automatic copter NAV_TAKEOFF. A second waypoint group or a later survey doesn't.
- **Ctrl+Z also undoes plan edits while a text field has focus.** It's the plan undo, not the field's own. That matches QGC, and the field value is part of the plan anyway.

### What to look at
1. `feature/plan/src/commonMain/kotlin/com/kft/gcs/feature/plan/MissionGroups.kt:123`: `flatten`, and `:157` `surveyItems`.
2. `feature/plan/src/commonMain/kotlin/com/kft/gcs/feature/plan/PlanViewModel.kt:382`: `changed()`, the whole undo mechanism in 10 lines.
3. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/MissionSync.kt:55`: `sameMission`, and `:42` `differsFrom`.

### Tests
- `MissionGroupsTest` (4):
  - The copter survey items for the worked example: 45 items, first line (15, −10) → (15, 0) → on(20 m, now) → (15, 200) → off → (15, 210), all at 100 m, RTL last, 77 planned photos.
  - Plane: no takeoff, 29 items.
  - Seq numbers across groups: the survey starts at 4, with no second takeoff.
  - An unfinished survey says why.
- `PlanDocumentTest` (3):
  - The own format round-trips every row kind (passthrough with NaN included) and a GSD-first survey.
  - Other files are refused.
  - Corner insertion goes into the nearest edge (hand example).
- `MissionFilesTest` (5):
  - `.plan` and `.waypoints` round trips, NaN yaw included.
  - A hand-written QGC plan in QGC's documented layout: home, null yaw → NaN, 1 ComplexItem skipped.
  - A hand-written MP file: line 0 = home, frame 2 → relative.
  - Wrong files name the format.
- `MissionSyncTest` (3):
  - Unknown → uploaded → edited.
  - A read-back in wire form equals what was sent, and a waypoint's frame still matters.
  - A different count reported by the vehicle is caught.
- `PlanViewModelTest` (16):
  - The Pass 8 cases, carried over to groups.
  - Upload preview → confirm → progress.
  - The armed warning in the preview.
  - Plan vs vehicle ("Not uploaded" / "On vehicle" / "changed elsewhere").
  - Read → "On vehicle".
  - Drawing a survey (polygon, corners, route, stats, live re-plan on drag).
  - Undo/redo (one drag = one step, two drags = two).
  - GSD-first keeps the altitude.
  - Opening a QGC plan.
  - Save then open.
- `FlyViewModelTest.hudWarnsWhenTheVehicleMissionIsNotThePlan` and `GeoJsonTest.polygonsAreClosedRingsAndTwoCornersAreALine`.
- **Desktop against ArduCopter 4.8.0-dev SITL (TCP 5762)**, screenshot `docs/decisions/assets/pass15-survey-plan.png`:
  - Plan → + Survey → 4 clicks → a 9-line grid with 10 m run-ins drawn live.
  - Panel: GSD 1.4 cm/px, 2.9 ha, 22.5 m spacing, a photo every 10 m (1.2 s), 144 photos, 1.7 km, 3 min 34 s, 1.2 GB.
  - Warning: "photos would be 1.2 s apart, but the camera needs 2.0 s. Fly at most 5.0 m/s…" (P4P preset).
  - Dragging a corner: 11 lines and 170 photos, live. Ctrl+Z reverts the whole drag, Ctrl+Shift+Z redoes it.
  - Upload → preview "Upload 57 items?" (0 Home, 1 Takeoff 50 m, 2 speed 8 m/s, then waypoint / camera every 10 m / … / stop) → Upload → **"On vehicle"**.
  - Read → a "From vehicle" group with the speed folded back into row 3, still **"On vehicle"**: the read-back equals the upload.
  - Edit → Fly shows **"Vehicle mission ≠ plan"** and "Mission 0 / 57".
  - Save → the Windows dialog → `survey1.kftplan` (readable JSON with the survey parameters). Delete the group → Open → the survey is back.
- `./gradlew check` and `:app:android:assembleDebug` pass, with no warnings.

### Open questions / next
- **Android:** built, but not clicked through on the emulator in this pass. The Pass 16 exit check covers the Copter survey there.
- The start-corner chips (↙ ↘ ↖ ↗) are small on desktop. They could become a little diagram if they confuse.
- Group reordering isn't built: add a group in the order you want to fly it, or delete and re-add. Up/down buttons are a small addition when needed.
- `ponytail:` `PLANE_BANK_DEG` is a constant 30°. Make it a setting if a KFT plane flies with a lower roll limit.
- Autosave (ArduDeck, spec P1) isn't built: Save is explicit.
- **Next: Pass 16**, photos in SITL and the rapid-prototype exit check.

---

## Pass 16 — Photos in SITL: the rapid-prototype exit check (2026-09-26)

### What changed
- **`core/vehicle/VehicleState.kt`**: `photos`, the position of every CAMERA_FEEDBACK (ardupilotmega.xml #180) since the link came up, capped at `MAX_PHOTOS`.
- **`feature/fly`**:
  - "Photos taken / planned" in the HUD (`photosItem`; planned from `MissionSync`).
  - Photo dots on the map (`MapOverlay.Photos`, added in Pass 15).
  - Clear track also starts the photo count again from 0.
- **`core/planning`**, from the exit check:
  - `Pass.cameraOff()`: the camera switches off half a trigger distance after the last planned photo, not at the edge.
  - `planSurvey` makes the run-out / lead-out at least d/2.
  - No interval warning at exactly the camera's limit.
  - Corrected KDoc: ArduPilot *holds back* a too-early photo; it doesn't skip it. The parameter is `CAM1_INTRVAL_MIN`. (The Pass 14 log line is corrected too.)
- **`feature/plan`**:
  - The survey items use the camera-off point.
  - The Plane default lead-in is 120 m, the lead-out is automatic (d/2), and the panel says "Lead-in (m)" for Plane.
- **`tools/sitl`**:
  - `camera.parm` (new): CAM1_TYPE 1, SERVO9_FUNCTION 10, and the caution below.
  - `start-sitl.ps1` loads `camera.parm`.
  - `sitl_pilot.py --photos` logs CAMERA_FEEDBACK to CSV until the mission ends.
  - `photo_check.py` (new): photos vs planned, and each photo's distance from its camera-on line, using the GCS's own `.waypoints` export.
- **Tests**:
  - `VehicleStateTest.cameraFeedbackAddsAPhoto`, `FlyViewModelTest.photosTakenOutOfPlanned`.
  - `SurveyGridTest.cameraSwitchesOffHalfATriggerDistanceAfterTheLastPhoto`.
  - `SurveyTest.exactlyAtTheCameraLimitIsFine` and `runOutIsAtLeastHalfATriggerDistance`.
  - Updated hand values in `SurveyTest` and `MissionGroupsTest`.
- **Screenshots**: `docs/decisions/assets/pass16-*.png`.

### How it works
```
Plan: survey ─▶ items: … WP photoStart ─▶ CAM_TRIGG_DIST(d, shoot now) ─▶ WP camera-off = start + (⌊L/d⌋ + ½)·d ─▶ CAM_TRIGG_DIST(0) …
                              │                                           (d/2 after the last planned photo)
SITL (CAM1_TYPE 1): each photo ─▶ AP_Camera_Backend::log_picture ─▶ CAMERA_FEEDBACK(lat, lng, img_idx) on every link
GCS: VehicleRepository ─▶ VehicleState.photos ─▶ Fly: dots on the map + "Photos n / planned" (planned via MissionSync)
Check: sitl_pilot.py --photos (SITL port 5763) ─▶ photos.csv ─▶ photo_check.py + the GCS's .waypoints export
```

### The exit check, run by run
Pilot sequence each time: `sitl_pilot.py` (the MAVProxy stand-in, as in Pass 10) arms, then GUIDED + takeoff 20 m (Plane: TAKEOFF mode), then AUTO. The GCS only drew, uploaded, exported and watched (S9).

| Run | Build | Survey | Photos (planned) | Off a line > 10 m | Verdict |
|---|---|---|---|---|---|
| Copter, desktop #1 | before the fix | 8 lines, P4P 50 m, 5 m/s | **104 (104)** | 0 (max 0.2 m) | pass; superseded by #2 |
| Plane, desktop #1 | before the fix | 7 lines, every 3rd, RX1R II 100 m, 18 m/s, 50 m lead-in | **121 (126)**, −5 | 22 | **fail**, found both bugs |
| Copter, Android emulator #1 | before the fix (stale APK) | 7 lines, 5 m/s | **98 (105)**, −7 | 0 | **fail**, the same bug on Copter |
| Plane, desktop #2 | final | same field, 120 m lead-in | **126 (126)** | 11 (max 34 m, see below) | **pass on count** |
| Copter, Android emulator #2 | final | the identical field | **105 (105)** | 0 (max 0.3 m) | **pass** |
| Copter, desktop #2 | final | the identical plan file | **104 (104)** | 0 (max 0.2 m) | **pass** |

Screenshots:
- `pass15-survey-plan.png`: the Plan screen.
- `pass16-copter-desktop-done.png`: 104 / 104, dots on the lines only.
- `pass16-plane-desktop-run1.png` (121 / 126) and `pass16-plane-desktop-done.png` (126 / 126).
- `pass16-copter-android-old-apk.png` (98 / 105, the far-edge dots missing) and `pass16-copter-android-done.png` (105 / 105).

### What the failing runs taught (the two fixes)
1. **The last photo of a line was lost when it fell close to the switch-off.** On Plane #1 the lines were 408.9 m with a 24 m trigger (17.04 spacings), so the 18th photo was due 1 m before the camera-off waypoint. Six of seven lines stopped at 17. Two ArduPilot behaviours add up to that:
   - The distance check runs at 50 Hz, so every photo is taken a little past its mark: the logged spacing was 24.1 m, not 24.0 m.
   - A waypoint counts as reached slightly before the vehicle is on it.

   The emulator's first Copter run hit exactly the same case (140.1 m lines, 10 m trigger: the 15th photo was due 0.1 m before the switch-off) and lost one photo per line, 98 of 105.
   **Fix:** switch the camera off at (⌊L/d⌋ + ½)·d. The last planned photo then has d/2 of margin, and so does the unplanned one after it, so ⌊L/d⌋ + 1 is exactly what ArduPilot does. The same two fields on the final build: 126 / 126 and 105 / 105.
2. **Plane: a 50 m lead-in is too short to finish the turn.** In run 1 the first photos of every line were taken at 22–25° of bank and 10–14 m off the line. From that data, roll is back to 0° about 110 m after the lead-in starts (ArduPlane SITL, `NAVL1_PERIOD` 15, 18 m/s). **Fix:** 120 m default lead-in; the lead-out needs only the camera's d/2.

### Engineering learnings
- **Test the prediction, not just the code.** Every unit test passed with the old camera-off point, because the tests checked the formula we chose. Only the real autopilot showed that the formula sat on a knife-edge. The planner's job is to predict what ArduPilot *will* do, so the switch-off now sits in the middle of the safe zone rather than on its edge.
- **Measure before fixing.** `photo_check.py` turned "some photos are missing" into "the 18th photo on lines 1–6, due 1 m before switch-off", and the emulator's −7 into "an old APK" (its camera-off waypoint was exactly at the edge). Without the per-line numbers, both would have looked like the same random loss.
- **CAMERA_FEEDBACK is the right count** (S10, `AP_Camera_Backend.cpp`, master 2026-09). Without a feedback pin, ArduPilot sends it for every photo it takes, with the AHRS position at that moment. It is `ardupilotmega.xml` #180, and it's parsed only from the autopilot's own system/component id, like all telemetry.
- **SITL parameters:** ~~`CAM1_TYPE` in a defaults file is ignored, even on a fresh `eeprom.bin`~~ *Corrected in Pass 17:* a value saved in the vehicle's `eeprom.bin` wins over the defaults files. Neither eeprom was fresh here: they dated from Passes 10–11. `start-sitl.ps1 -Wipe` starts with fresh parameters, and then `camera.parm` applies (Pass 17 control test).
- **The exact-limit warning:** at 5 m/s with a 10 m trigger and a 2.0 s camera, the maths gives 1.999… s. That produced the warning "2.0 s apart, but the camera needs 2.0 s", which is noise. Found in the first desktop run; fixed with a 1e-9 s tolerance and a test.

**Ponytail review:** lean already. `photo_check.py` (≈ 70 lines) is what makes "no photos in the turns" a number. `log_photos` is ≈ 20 lines in the existing pilot script. The camera-off point is one pure function plus a two-line minimum in `planSurvey`. `MAX_PHOTOS` carries a `ponytail:` note. Deleted the `__pycache__` my analysis left behind.

### Safety
- **No allowlist change.** `DO_SET_CAM_TRIGG_DIST` goes only inside `MISSION_ITEM_INT` (MISSION_CHANGE), like every mission item. The GCS never sends it as an immediate command. It's still UNLISTED as COMMAND_LONG, and `DO_SET_SERVO` / `DO_SET_RELAY` stay NEVER.
- **S9 held in every run:** the GCS drew, uploaded, exported and watched. Arming, takeoff and AUTO came from `sitl_pilot.py` on SITL port 5763 (system id 254).
- **S11:** every preview and every export shows home as seq 0. The emulator's exported `.waypoints` (pulled off the device) starts `0 1 0 16 … -35.363261 149.1652299 584.09`.
- The one `COMMAND_LONG DO_SET_CAM_TRIGG_DIST` in this pass was a SITL setup check sent by a Python snippet on port 5763, never by the GCS.

### What to look at
1. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/SurveyGrid.kt:268`: `cameraOff`, and its KDoc with the SITL numbers.
2. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/Survey.kt:87`: the minimum run-out.
3. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/VehicleState.kt:134`: CAMERA_FEEDBACK.
4. `tools/sitl/photo_check.py`: how "on the line" is measured.

### Tests
- **Unit:**
  - `cameraFeedbackAddsAPhoto`: E7 → degrees, and 0,0 ignored.
  - `photosTakenOutOfPlanned`: "2 / 11", two dots, Clear track → "1 / 11".
  - `cameraSwitchesOffHalfATriggerDistanceAfterTheLastPhoto`: 200 m → off at 210; 205 m → still 210; run-out 4 m → capped at 204.
  - `runOutIsAtLeastHalfATriggerDistance`: Copter 25 kept; Plane lead-in 120 kept, lead-out 10.
  - `exactlyAtTheCameraLimitIsFine`: 5.0 m/s no warning, 5.1 m/s warning.
  - Updated: `altitudeFirstWithStatsAndBothWarnings` (1810 m, 181 s, 4 batteries), `exactlyTwoBatteries` (90.5 s), `copterSurveyItems` (38 items, off at (15, 210)).
- **SITL (ArduCopter / ArduPlane 4.8.0-dev):** the table above, with screenshots.
- **Android extras**, on the Medium_Tablet emulator:
  - Drawing by tap. A finger drag moved a corner without panning the map, and the Undo button restored it.
  - The upload preview, then "On vehicle".
  - Export through the system document picker (`mission.waypoints`, then `mission (1).waypoints`), pulled with adb and read by `photo_check.py`.
- `./gradlew check` and `:app:android:assembleDebug` pass, with no warnings.

### Your checklist: run the exit check yourself
1. Once per vehicle: `start-sitl.ps1 -Vehicle copter -Wipe` (fresh parameters, so `camera.parm` applies; corrected in Pass 17).
2. `gradlew.bat :app:desktop:run` → Links → your SITL profile → Plan → + Survey → click 4 corners → set the speed so there's no interval warning → Upload → check the preview → Upload → "On vehicle".
3. Plan → Export → Mission Planner .waypoints (for step 5). Then Fly → Clear track.
4. In MAVProxy: `mode guided`, `arm throttle`, `takeoff 20`, `mode auto`. Watch "Photos n / planned" and the dots. Or run `py -3.9 tools/sitl/sitl_pilot.py tcp:127.0.0.1:5763 --photos photos.csv` for the same sequence plus the log.
5. `py -3.9 tools/sitl/photo_check.py mission.waypoints photos.csv --planned <n>`. Expect a difference within ±2 and 0 photos off the lines for Copter. For Plane, see the open item below.

### Open questions / next
- **Plane line settling (not fixed, measured):** on the final Plane run, 11 of 126 photos were 10–18 m to the side of their line, at roll ≈ 0°: the first one to three photos of a line, while ArduPlane's L1 was still converging. The worst was the first line's first photo, 34 m off, after the long diagonal approach from home. With a 102 m footprint and 41 m spacing the ground is still covered (side overlap drops locally from 60 % to about 42 %). Options, cheapest first:
  - Tune `NAVL1_PERIOD` on the real aircraft (a vehicle setting, not the GCS).
  - Add a longer lead-in only for the first line.
  - Plan the turn with the aircraft's real turn radius (from `NAVL1_PERIOD` and speed) rather than 30° bank.

  Your call which.
- Photo count on a lossy radio link: the count is CAMERA_FEEDBACK messages received, so a lost packet undercounts. `img_idx` / `completed_captures` could be used to fill gaps. `ponytail:` `MAX_PHOTOS` caps the stored list at 10 000.
- ~~`CAM1_TYPE` from a defaults file not applying on Windows SITL may be a SITL-build quirk~~ Resolved in Pass 17: it was the saved `eeprom.bin`, and `-Wipe` fixes it.
- **The rapid prototype (spec §4, weeks 4–5) is met in SITL:** draw polygon → grid → upload → SITL flies it → photos triggered and counted, on desktop (Copter, Plane) and on Android (Copter). A real FC with a camera is the next proof.
- **Suggested next pass:** a real-hardware check with a KFT FC and a camera on the servo trigger (needs `KFT_APP_SECRET`, Pass 12), or the spec §4 weeks 6–8 list (crosshatch, corridor, KML import).

---

## Pass 17 — Clean-up after the rapid prototype (2026-09-26)

### What changed
- **`core/planning/SurveyGrid.kt`**: `Turnaround.Plane.firstLeadInM` = max(lead-in, 2 × turn diameter = 4r), used for the first survey line only.
- **`core/mission`** (new module): `MissionItem`, `AltitudeFrame`, `MissionCommand`, `Mission` and `Home`, moved unchanged from `core:vehicle` (package `com.kft.gcs.core.mission`).
  - `core:vehicle` keeps the MAVLink wire conversion and exposes the module with `api`.
  - `core:geo-io` now depends on `core:mission` instead of `core:vehicle`.
  - `encodeQgcPlan` takes `plane: Boolean`, so `geo-io` no longer needs `VehicleKind` either.
  - CLAUDE.md §2 table updated: a new `core:mission` row, and the `vehicle` and `geo-io` rows.
- **`tools/sitl/start-sitl.ps1`**: `-Wipe` passes SITL's `--wipe` (fresh parameters).
  - `camera.parm` and the script header now say why: a value saved in `eeprom.bin` wins over the defaults files.
  - The Pass 16 log lines that blamed "Windows SITL" are corrected in place.
- **`docs/checklists/bench-session-1.md`** (new): the bench checklist.
- Imports updated in 22 files for the moved types (no behaviour change).
- **Tests**:
  - `SurveyGridTest.firstLineLeadInIsTwoTurnDiameters` (new).
  - Updated hand values: `planeFliesEveryThirdLineSoNoTurnNeedsALoop` (3222.4778 m) and `planeSurveyHasNoTakeoff` (30 items).

### How it works
- **First line:** Pass 16's worst photo (34 m off the line) was on the first line, after the approach from home at an angle. The other lines' worst was 18 m. The plane comes to line 1 from anywhere, and to lines 2… from a planned U-turn, so only line 1 gets the long run-up.
  - Hand example (`firstLineLeadInIsTwoTurnDiameters`): r = 50 m, lead-in 30 m → max(30, 200) = 200 m. The first pass starts at y = −200; the second keeps 30 m (entry at y = 230, southbound). With r = 5 m: max(30, 20) = 30, unchanged. A copter has no such rule.
  - The worked example's Plane distance grows by exactly the extra 170 m (3052.4778 → 3222.4778 m). The legs between passes don't change, because they start at each pass's exit.
- **Parameters in SITL:** SITL reads the defaults files at boot but keeps any value already saved in `eeprom.bin`. `-Wipe` removes the saved values, so the defaults (including `camera.parm`) apply.

### Engineering learnings
- **You were right about the cause, and the control test shows it.** Plane SITL, same files, three starts:
  1. `CAM1_TYPE` saved as 0 → read 0.
  2. Restart without `-Wipe` → 0: the saved value won over `camera.parm`.
  3. Restart with `-Wipe` → 1: the defaults applied.

  Copter with `-Wipe` → 1, and one test photo produced a CAMERA_FEEDBACK with a real position.
  My Pass 16 claim, "even on a fresh eeprom.bin", was wrong. Neither eeprom was fresh: they came from Passes 10–11. I only knew that from the folder dates after you questioned it, and I hadn't run a control. The lesson: when a parameter "doesn't apply", test "saved beats default" before blaming the platform.
- **A model module is the dependency fix, not a re-export.** `geo-io` needed four data classes, but depending on `core:vehicle` pulled mavlink-kotlin and the whole protocol layer in with them. Now plain values live in the smallest module that can hold them (`core:geo` only). Both the protocol code and the file formats depend on that, and neither depends on the other.
- **`encodeQgcPlan(plane: Boolean)`:** the file format needs one bit ("show plane options in QGC"), so it takes one bit. A shared enum would have dragged `core:mavlink` back in.
- **NAVL1 tuning is left to the real aircraft.** `NAVL1_PERIOD` (and `NAVL1_DAMPING`) set how fast ArduPlane converges onto a line: lower is tighter but can oscillate. It depends on the airframe, so it belongs to the aircraft's tuning, not the GCS. The planner's job is lead-in lengths that give the aircraft room to settle.
  - What Pass 16 measured in SITL (`NAVL1_PERIOD 15`, 18 m/s): roll back to 0° about 110 m after a U-turn, and up to 18 m sideways for the first 1–3 photos of a line.
  - When the real plane is tuned, re-run `photo_check.py` on a field survey. If the sideways offset is still large, the lead-in default (120 m) and this first-line rule are the knobs.

**Ponytail review:** lean already.
- `firstLeadInM` is a one-line named property. It keeps the rule and its reason in one KDoc instead of repeating them at each use.
- `core:mission` is five small types with no functions.
- `-Wipe` is 3 lines.

### Safety
- No allowlist or transmission change. The mission types moved unchanged, and `missionToWire` / `missionFromWire` (seq 0 = home, S11) are untouched, as are their tests (`MissionTest`).
- The bench checklist asks for nothing the GCS would transmit beyond Pass 16. Arming and the camera switch are on the RC, props off. Parameters are set in Mission Planner (the GCS still has no parameter writes).
- The only SITL-side transmissions in this pass were test snippets on the tool port (5763, system id 254): PARAM_SET of `CAM1_TYPE`, and one `DO_SET_CAM_TRIGG_DIST` to confirm CAMERA_FEEDBACK. None came from the GCS.

### What to look at
1. `core/planning/src/commonMain/kotlin/com/kft/gcs/core/planning/SurveyGrid.kt:51`: the rule, and `:154` where the grid applies it.
2. `core/mission/src/commonMain/kotlin/com/kft/gcs/core/mission/MissionModel.kt`: the whole shared model.
3. `docs/checklists/bench-session-1.md`: the bench session.

### Tests
- `SurveyGridTest.firstLineLeadInIsTwoTurnDiameters`: 200 m first lead-in (r = 50), 30 m on the second pass, unchanged for r = 5 and for a copter.
- `planeFliesEveryThirdLineSoNoTurnNeedsALoop`: pass 0 entry at y = −200, total 3222.4778 m (by hand: 3052.4778 + 170).
- `MissionGroupsTest.planeSurveyHasNoTakeoff`: 30 items (+1 first-line lead-in waypoint; r = 70.6 m at 20 m/s gives 282.6 m).
- `./gradlew check` and `:app:android:assembleDebug` pass with no warnings. `geo-io` builds without `core:vehicle` or `core:mavlink` on its classpath.
- SITL: the `-Wipe` control test above (Plane: 0 / 0 / 1; Copter with `-Wipe`: 1, plus a CAMERA_FEEDBACK).

### Open questions / next
- **Bench session 1** (the checklist): the KFT login on a real FC, radios on both platforms, mission sync, and the camera trigger. It needs your hardware and key.
- Not re-flown in SITL: the first-line lead-in adds straight flight only, and the camera items are unchanged. Your next Plane SITL or field survey will show the first line's offset with it (use `photo_check.py`).
- Still open from before: GS-5 (a verified camera for KFT's payload), and whether to plan Plane turns with the aircraft's real turn radius instead of 30° bank.

---

## Pass 18 — Basic parameter editor (P1 item pulled forward) + Plane first-line re-check (2026-09-26)

Why now: KFT firmware drops Mission Planner and QGC until the HMAC login, so this GCS is the only tool that can set
parameters on our flight controllers. Two items, one commit each. This entry covers item 1; item 2 is appended below.

### Item 1 — Parameter editor

#### What changed
- **`core/mavlink/TxPolicy.kt`**: PARAM_SET moves from `OPERATOR` (always allowed) to a new `PARAM_CHANGE_DISARMED`
  category. It's refused while armed, and also when no vehicle is heard (armed state unknown). `OPERATOR` is gone:
  PARAM_SET was its only member. PARAM_REQUEST_LIST / PARAM_REQUEST_READ stay `ALWAYS`.
- **`core/vehicle`**:
  - `Params.kt` (new): `Param`, `ParamType` (ArduPilot's four storage types), `valueText`, `parseParamInput` (whole
    numbers only for integer types, range, no NaN), and `ParamSetResult` (Applied / NotApplied).
  - `ParamProtocol.kt` (new): download all (list, then per-index reads for the gaps), and set with a check on the echo.
  - `ParamRepository.kt` (new): the interface plus `DefaultParamRepository`, gated on the KFT login.
  - `MissionRepository.kt`: the login gate is now two small shared functions (`loggedInTarget`, `notReadyReason`),
    used by missions and parameters alike. Missions behave the same.
  - `di/VehicleModule.kt`: binds `ParamRepository`.
- **`core/geo-io/ParamFiles.kt`** (new): Mission Planner `.param` read/write (`NAME,VALUE`).
- **`feature/params`** (new module): `ParamsUiState`, `ParamsViewModel` (with the `ParamFiles` dialog interface),
  `ParamsScreen` + `ParamsRoute`, and `di/ParamsModule`.
- **`app/shared`**:
  - The "Params" tab in the rail.
  - `paramsModule` in the graph.
  - A 6-line `ParamFiles` adapter over the existing `PlanFiles` dialogs.
  - The desktop dialog title is now "Open" instead of "Open a plan (…)".
- **`settings.gradle.kts`**, **`CLAUDE.md` §2**: the new module.
- **`tools/sitl/sitl_pilot.py`**: `--arm-only`, for the SITL armed-refusal check. This is the pilot's role; the GCS
  never sends it.
- **`docs/checklists/bench-session-1.md`**:
  - New section 1b, the Params screen, including the locked-parameter check.
  - Section 5 sets the camera through the GCS (one by one, or Load file…) and power-cycles, instead of using Mission
    Planner.
- **Tests**:
  - New: `ParamProtocolTest` (17), `ParamsViewModelTest` (7), `ParamFilesTest` (3).
  - `MavTxGatewayTest`: `paramSetOnlyWhileDisarmed`, `gatewayRejectsParamSetWhileArmedWithoutWriting`, and
    PARAM_REQUEST_READ in the always-allowed list.
  - `SitlCheck.paramsDownloadSetReadBackAndArmedRefusal`.

#### How it works
```
Params screen ──onDownloadClicked──▶ ParamsViewModel ──downloadAll──▶ DefaultParamRepository
                                                                        │ login gate (AUTHENTICATED / LEGACY / NO_KEY)
                                                                        ▼
                                             ParamProtocol ──PARAM_REQUEST_LIST──▶ gateway (ALWAYS) ──▶ FC
   received[index] ◀── PARAM_VALUE(name, value, type, count, index) streamed ◀──────────────────────────┘
   quiet 1.5 s ─▶ PARAM_REQUEST_READ(index) for ≤10 missing indices, ≤3 tries each ─▶ done or "k of N never arrived"

Edit: row ─▶ dialog: type ─▶ Set (checks type/range, builds the question) ─▶ Confirm ─▶ repository.set
   PARAM_SET(name, value) ─▶ gateway: armed? ─▶ refused, nothing written ("Not sent: … disarmed")
                                      disarmed ─▶ FC ─▶ PARAM_VALUE(name, stored value, index 65535)
   echo == value ─▶ Applied: the row takes it, bold + "modified"
   echo != value ─▶ NotApplied "Locked or rejected by the vehicle: it kept X" (row note, no retry)
   PARAM_ERROR  ─▶ NotApplied "Rejected by the vehicle (PERMISSION_DENIED)"
   silence ─▶ resend, 3 tries, then "not confirmed. Download to check."

Files: Save ─▶ encodeParamFile(name → valueText) ─▶ PlanFiles dialog (via the ParamFiles adapter)
       Load ─▶ parseParamFile ─▶ compare with the downloaded list ─▶ "Write N parameters?" (only the differences,
              plus what was skipped) ─▶ one set at a time, each checked as above
```

#### Engineering learnings
- **S10, what ArduPilot actually does** (`libraries/GCS_MAVLink/GCS_Param.cpp`, `libraries/AP_Param/AP_Param.cpp`,
  master 2026-09, read not copied):
  - **Encoding is C cast, not bytewise.** `cast_to_float(type)` goes out; `set_float(value, type)` comes in, and adds
    0.01 then truncates for integer types. So `3.0f` means 3, and an integer parameter never needs its bits
    preserved.
  - **The Pass 12 raw-bits lesson doesn't apply here, and I checked why rather than copying the workaround.** The NaN
    problem came from the library canonicalising NaN payloads in COMMAND_LONG, where the KFT login packs raw bytes
    into floats. Parameters carry real numbers, and ArduPilot refuses a NaN/inf PARAM_SET (PARAM_ERROR
    VALUE_OUT_OF_RANGE on master). So PARAM_SET stays on the library encoder, and `parseParamInput` never produces
    NaN. The Pass 12 `ponytail:` note ("extend BitExact… if another message carries bit patterns") still holds, and
    PARAM_SET doesn't.
  - **INT32 above 2^24 can't be sent exactly** as a float. Every GCS shares this limit. The input check stops at
    ±16 777 216.
  - **The list stream:** at most 30 % of the link's bandwidth, and 5 per update without flow control. It's ignored
    until parameters are loaded at boot (`params_ready`), hence up to 5 list requests.
  - **Reads by index:** they go into a **20-entry queue**, and the overflow is dropped silently. That's why the gaps
    are asked for 10 at a time.
  - **Sets:** a successful set is saved through the save queue, and the save sends PARAM_VALUE with the stored value
    to every link, with index −1 (65535).
  - **Read-only / not settable:** "Param write denied" STATUSTEXT + PARAM_ERROR PERMISSION_DENIED + PARAM_VALUE
    with the **old** value.
  - **Unknown name:** PARAM_ERROR DOES_NOT_EXIST on master, silence on older firmware.
  - **`param_type` in PARAM_SET is ignored:** ArduPilot uses the type it has stored. We send the type it reported
    anyway.
- **Confirm by echo, and never retry an echo.**
  - A set is "applied" only when a PARAM_VALUE for that name comes back holding exactly the value we sent.
  - An echo with any other value is the vehicle's answer. That covers ArduPilot's read-only rule, and presumably
    KFT's parameter locking. It's reported as "locked or rejected by the vehicle", and the set is not repeated.
  - Only *silence* is retried (3 tries), because a lost packet and a lost echo look the same, and setting the same
    value twice is harmless.
  - That's how a locked parameter can't loop forever. `lockedParameterIsReportedNotRetried` pins it: one PARAM_SET,
    0 ms.
- **Why the list lives in the ViewModel, not a repository StateFlow.** The downloaded list is a snapshot the operator
  asked for, and only this screen reads it. A shared cache would need rules about invalidating it: another GCS
  changing a value, a reboot, a count change. None of that exists yet, so the screen holds what it downloaded and
  shows what each set reported. If Fly ever needs a parameter (say CAM1_TYPE for a warning), that's when a
  repository-level cache earns its place.
- **A count change fails the download instead of merging.** Enabling a feature (CAM1_TYPE is an `AP_PARAM_FLAG_ENABLE`
  parameter) adds parameters and shifts every index after them, so indices from before and after don't describe the
  same list. "Download again" is honest; merging would be a guess.
- **Two-step edit.** Set only validates and writes the question ("Change CAM1_TYPE from 0 to 1 on the vehicle?");
  Confirm sends. An unchanged value is refused at Set ("That's the current value"). Part of the reason is that
  ArduPilot's echo for an unchanged value depends on the save path, so we don't rely on it.
- **"Modified" means changed since download.** Without ArduPilot's parameter metadata we don't know the defaults.
  "Changed in this session" is what the operator needs on the bench anyway.
- **Parameter metadata left out.** ArduPilot's `apm.pdef.xml` / `.json` (units, ranges, descriptions) is generated
  from the ArduPilot source by `Tools/autotest/param_metadata`. The source is GPLv3, and I found no separate licence
  for the generated files. That's "unclear" by your rule, and CLAUDE.md §6 forbids copying GPL code, so the screen
  shows name, value and type only. If KFT gets a written answer from ArduPilot (or decides the GCS may be GPL), it
  can be bundled later as a JSON asset in `core:geo-io`.
- **`ParamFiles` adapter instead of moving `PlanFiles`.** Features can't import each other, so Params declares the two
  functions it needs, and `app:shared`, which sees both features, adapts one to the other in 6 lines. Moving
  `PlanFiles` into a shared module would have been a refactor of the Plan feature, which this pass has no reason to
  touch.
- **Shared login gate.** The Pass 12 gate was private to `DefaultMissionRepository`. It's now two internal functions,
  so parameters wait for the same login states with the same wording ("… Parameter transfers wait for the login").
- **Build environment note (not code):** in this session Gradle's launcher failed with "Unable to establish loopback
  connection". It came from Windows AF_UNIX sockets under the default temp path. The fix was
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Hrushikesh\uds`. If you ever see that error, that's the
  fix; nothing in the repo changed.

**Ponytail review** (`/ponytail-review` on the diff), two shrinks applied:
- `canWrite()` built the whole UiState to read one flag. It's now a shared `writable(m, v)` predicate.
- The test fake's type table went MAV → domain → MAV. It's now one mapping.

Kept:
- All tests (§7).
- The `ParamRepository` interface (swap/test seam).
- The two-step confirm (requested).
- The `ParamFiles` adapter (required by the feature-isolation rule).

#### Safety
- **Allowlist change:** PARAM_SET went from always allowed to **disarmed only**, failing closed when no vehicle is
  heard. That's stricter than pod contract §3 ("operator commands … param set ✅ when no pod is active").
  - Tests: `MavTxGatewayTest.paramSetOnlyWhileDisarmed` (armed → rejected, unknown → rejected, disarmed → allowed,
    PARAM_REQUEST_READ allowed while armed).
  - Test: `gatewayRejectsParamSetWhileArmedWithoutWriting` (the armed-time set never reaches the link).
- **Not changed:** PARAM_SET is not tied to the pod lock state. The contract's rule is "when no pod is active", and
  disarmed-only is already stricter in every state a pod can reach in flight. When the pod link exists, decide
  whether a LOCKED pod on the ground should also block it (open item).
- **Transmission order:** parameter traffic waits for the KFT login exactly like missions (S12).
  `ParamProtocolTest.repositoryWaitsForTheKftLogin`: nothing is sent while LOGGING_IN / DENIED / FAILED.
- **S9:** no flight action was added. Arming in the SITL check comes from `sitl_pilot.py --arm-only` on port 5763
  (system id 254), the pilot's role, like every earlier SITL run. The GCS doesn't reboot the FC either:
  MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN stays unlisted, and the checklist says to power-cycle.
- A parameter write can still change how the aircraft flies once it's back in the air (gains, failsafes). The
  disarmed rule, the confirm step and the "only the differences" file load are the guards. Values are applied at
  once by ArduPilot and saved.

#### What to look at
1. `core/vehicle/src/commonMain/kotlin/com/kft/gcs/core/vehicle/ParamProtocol.kt:114`: `set`, the echo rule. Also the
   class KDoc for the S10 facts, and `:71` for the download loop.
2. `core/mavlink/src/commonMain/kotlin/com/kft/gcs/core/mavlink/TxPolicy.kt:87` and `:126`: the disarmed-only rule.
3. `feature/params/src/commonMain/kotlin/com/kft/gcs/feature/params/ParamsViewModel.kt:175`: `write`, how the list
   follows what the vehicle reported. `:129` is the file compare.

#### Tests
- **`ParamProtocolTest`** (17, virtual time, a fake that answers like GCS_Param.cpp):
  - The whole list in index order, 0 ms waiting.
  - Lost stream items re-read by index after one 1.5 s quiet period.
  - Reads in batches of 10 (25 missing → 10 + 10 + 5, 4.5 s).
  - An index that never arrives fails after 3 reads with "1 of 6 parameters never arrived".
  - The list request repeated while the vehicle ignores it.
  - A silent vehicle fails after 5 list requests.
  - A count change fails with "Download again".
  - Set confirmed by the echo, sent as INT8 for CAM1_TYPE.
  - **Locked parameter: one PARAM_SET, "Locked or rejected … kept 0", at 0 ms.**
  - PARAM_ERROR → "PERMISSION_DENIED".
  - Silence → 3 sends then "not confirmed" at 4.5 s.
  - A lost echo is recovered by the resend.
  - An unrelated PARAM_VALUE doesn't confirm.
  - An armed set is not sent.
  - Login gate.
  - Input checks: 2.5 for INT8 is refused, 128 for INT8, 2^24 + 1 for INT32, NaN.
  - Value text: "3", "-1", "0.15", "100".
- **`ParamsViewModelTest`** (7, Turbine):
  - Download, search and status.
  - Set → confirm question → nothing sent before Confirm → row bold/modified.
  - Unchanged value refused.
  - Locked row note, value unchanged, one set.
  - No editing while armed or before the login.
  - Save writes `CAM1_TYPE,0\nSERVO9_FUNCTION,0\nWPNAV_SPEED,1000\n`.
  - Load file lists only the 2 real changes, reports "1 not on this vehicle; unreadable lines 6", writes on confirm,
    and reports "1 of 2 written, 1 not applied".
- **`ParamFilesTest`** (3):
  - A Mission Planner file with a `#NOTE` header, CRLF, space/tab/`=` separators and `@READONLY`.
  - Bad lines reported (lower case, a 17+ character name, a non-number, 3 fields, nan), and the later line wins.
  - Sorted output that reads back.
- **SITL, ArduCopter 4.8.0-dev**, started with `start-sitl.ps1 -Vehicle copter -Wipe`, then
  `KFT_SITL=127.0.0.1:5762 gradlew.bat :core:vehicle:jvmTest --tests "*SitlCheck*"`. 3 of 3 passed:
  - `paramsDownloadSetReadBackAndArmedRefusal` (110 s: three full downloads plus arming and auto-disarm):
    - **1439 parameters**, every index once.
    - `CAM1_TYPE = 1 (INT8)` from camera.parm.
    - Set 1 → 2: `Applied(CAM1_TYPE = 2.0, INT8, index 46)`. A fresh download reads 2. Restored to 1.
    - `sitl_pilot.py --arm-only` armed it (GUIDED → ARMED). Set while armed → `NotApplied("Not sent: parameters can
      only be changed while the vehicle is disarmed.")`.
    - After the auto-disarm, a fresh download still reads 1.
  - The Pass 7 / Pass 12 checks still pass (LEGACY_FIRMWARE login, mission 6/6 upload + read-back + clear).
- `gradlew.bat check` and `:app:android:assembleDebug` pass. One compiler warning is printed, and it's not from this
  pass: `PlanViewModel.kt:398` "Unnecessary non-null assertion" (file untouched here). I left it and suggest a
  one-character fix in a clean-up pass.
- **Not done by me: clicking through the screen.** The desktop app started fine against SITL (Koin 18 definitions,
  MapLibre up), but the computer-use tool couldn't attach to the Java window ("KFT GCS"), so I have no screenshot.
  The screen's logic is covered by the ViewModel tests, and the layout by `check`. Please do checklist section 1b
  once on SITL (below).

#### Your checklist: the Params screen on SITL (5 minutes)
1. `start-sitl.ps1 -Vehicle copter -Wipe`, then `gradlew.bat :app:desktop:run` → Links → your SITL profile → Connect.
2. Params → Download.
   - Expected: "Downloading k / 1439", then "1439 parameters · 0 modified".
3. Search `cam1`, click CAM1_TYPE, type 2 → Set → "Change CAM1_TYPE from 1 to 2 on the vehicle?" → Confirm.
   - Expected: "CAM1_TYPE set to 2", and the row bold with "modified". Put it back to 1.
4. Save file… → `sitl.param`. Open it in Notepad: `NAME,VALUE` lines, sorted.
5. In MAVProxy: `mode guided`, `arm throttle`.
   - Expected: the rows can't be clicked, and "Disarm to change parameters" shows. It disarms by itself after about
     10 s.

#### Open questions / next (item 1)
- **KFT parameter locking:** I built it on the assumption that a locked parameter answers like ArduPilot's read-only
  parameters: an echo of the old value, and maybe PARAM_ERROR or a STATUSTEXT. The UI then says "locked or rejected
  by the vehicle" once.
  - If KFT's lock is silent instead, the operator sees "No answer from the vehicle after 3 tries: … not confirmed"
    after 4.5 s. That's still bounded, but less clear.
  - Bench step 1b.4 tells us which. Send me the locking details if it's the silent kind, and I'll make that message
    specific.
- Parameter metadata (units/range/description): not bundled, licence unclear (above).
- PARAM_SET vs the pod lock state: decide when the pod link exists (Safety, above).
- A value changed by another GCS or by the FC itself isn't picked up until the next Download. PARAM_VALUEs that
  arrive outside a transfer are ignored.
- `PlanViewModel.kt:398` warning (pre-existing).

### Item 2 — Plane first-line re-check (Pass 17's first-line lead-in, flown)

#### What changed
- **`feature/plan/src/jvmTest/.../PlaneSurveySitlRun.kt`** (new): plans the Pass 16 Plane field with the Plan screen's
  own `flatten`, uploads it to a running SITL, and writes the `.waypoints` export for `photo_check.py`. It's skipped
  unless `KFT_SITL` is set, like `SitlCheck`, so the re-check is one command instead of clicking the field in again.
- **`tools/sitl/photo_check.py`**: a per-line breakdown, in flight order (photo count, the first three photos'
  offsets, and the max). The question was about *first-line* photos, and the old totals couldn't show which line a
  photo belonged to.

#### How it was run
```
start-sitl.ps1 -Vehicle plane -Wipe                                  (fresh parameters, camera.parm applies)
KFT_SITL=127.0.0.1:5762 KFT_SITL_OUT=<dir> gradlew :feature:plan:jvmTest --tests "*PlaneSurveySitlRun*"
   ─▶ "lines 7, spacing 41.03, trigger 24.0, planned photos 126, items 44" ─▶ uploaded, mission.waypoints written
py -3.9 tools/sitl/sitl_pilot.py tcp:127.0.0.1:5763 --photos <dir>/photos.csv   (pilot: arm, TAKEOFF, AUTO; S9)
   ─▶ "126 photos written (mission over: RTL)"
py -3.9 tools/sitl/photo_check.py mission.waypoints photos.csv --planned 126
```
**The field is a reconstruction.** Pass 16's plan file wasn't kept, so I measured the corners from
`pass16-plane-desktop-done.png`, scaled by the logged 408.9 m line length (0.721 m/px): 453.6–175.2 m west, and 177.4 m
north to 231.5 m south, of the CMAC home. Settings as logged:
- Sony RX1R II, 100 m, 60 % side / 65 % front overlap.
- 18 m/s, grid 0°, entry bottom-left, default 120 m lead-in, RTL.

The plan came out identical on every logged number (7 lines, 41.03 m spacing, 24.0 m trigger, 126 photos, 44
items), so any difference from the real field is a few metres of corner position. The approach from home to the first
line is the same diagonal.

What differs from Pass 16 is the Pass 17 rule: line 1's lead-in is max(120, 4r) = **229 m** (r = 57.2 m at 18 m/s and
30° bank), instead of 120 m.

#### Result
| | Pass 16 (120 m lead-in on every line) | Pass 18 (229 m on line 1, 120 m after) |
|---|---|---|
| Photos / planned | 126 / 126 | **126 / 126** |
| **Line 1, worst photo** | **34 m** (the first photo) | **5.4 m** (first three: 5.4, 5.4, 4.2 m) |
| Lines 2–7, first photo | 10–18 m | 16.8–18.0 m |
| Lines 2–7, second / third photo | (not broken down) | 9.6–10.8 m / 3.9–4.9 m |
| Photos > 10 m off a line | 11 | 10 (all the first two photos of lines 2–7) |
| Mean offset, all photos | — | 2.4 m |

- **The first line is fixed.** Its first photos went from 34 m off to about 5 m, which is the same residual as the
  middle of the other lines.
- **The U-turn lines didn't change, as expected**, because the Pass 17 rule only touches line 1. After each U-turn the
  first photo is still about 17–18 m to the side, the second about 10 m, and from the third on under 5 m. ArduPlane's
  L1 is still converging for about 50 m past the camera start (2 × 24 m trigger).

#### Engineering learnings
- **Measure per line, not in total.** The totals barely moved (11 → 10 photos over 10 m), and on their own they
  would have read as "no real change". Split by line, they show exactly what the Pass 17 rule did (line 1: 34 → 5.4 m)
  and what it was never meant to do (lines 2–7).
- **One command beats clicking a field in again.** The harness uses the same `flatten` the Plan screen uses, so the
  uploaded items are what the operator would upload. A future "is Plane better now?" is `PlaneSurveySitlRun` +
  `sitl_pilot.py` + `photo_check.py`.

**Ponytail review:**
- The harness is one test (about 60 lines) with no new production code.
- The `photo_check.py` addition is 7 lines.

#### Safety
- No GCS code changed in item 2 and no allowlist change. The upload went through the gateway as usual.
- S9: arming, TAKEOFF and AUTO came from `sitl_pilot.py` on port 5763 (system id 254), the pilot's role. The GCS side
  (the harness) only uploaded.
- S11: the exported `.waypoints` starts with home as seq 0 (`0 1 0 16 … -35.363261 149.1652299 584.09`).

#### What to look at
1. `feature/plan/src/jvmTest/kotlin/com/kft/gcs/feature/plan/PlaneSurveySitlRun.kt`: the field and how it's planned.
2. `tools/sitl/photo_check.py`, the end: the per-line breakdown.

#### Tests
- `PlaneSurveySitlRun` (SITL only; skipped in `check`). The ArduPlane 4.8.0-dev run is in the table above.
- `gradlew.bat check` passes.

#### Open questions / next (item 2)
- **The first 1–2 photos after each U-turn are still 10–18 m off the line.** With a 102 m footprint and 41 m spacing
  that still leaves about 42 % side overlap locally, so the ground is covered. Options, cheapest first:
  1. **Tune `NAVL1_PERIOD` on the real aircraft**, as Pass 17 said. SITL's 15 isn't the KFT airframe's.
  2. **Use the 4r lead-in on every line**, not only the first: 229 m instead of 120 m at 18 m/s. The line-1 result
     (5.4 m) suggests it would fix them. It costs about 6 × 109 m ≈ 650 m, or about 36 s per survey here. It's a
     one-line change in `SurveyGrid.kt`, plus the hand values in three tests.
  3. Plan turns with the aircraft's real turn radius.

  Your call. Option 2 needs your OK because it lengthens every Plane survey.
- The field was reconstructed from a screenshot (above). From now on, `PlaneSurveySitlRun` pins it in code.

---
