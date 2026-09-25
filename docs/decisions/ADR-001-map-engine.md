# ADR-001 — Map engine for P0

**Status:** Accepted (one check still open: M5 on the physical tablet, see below)
**Date:** 2026-09-24 · **Pass:** 2 (W1-2) · **Spike code:** `spikes/map-spike`, `spikes/map-spike-android` (throwaway)
**Context:** `docs/implementation/00-maps-decision.md` §2–§4

## Verdict

> **Adopt A: maplibre-compose 0.17.0 on both platforms.** OpenGL runtime on Android, Vulkan runtime on Windows x64.

Every check that could kill option A passed on the machines we have: desktop render (M1), live overlays (M2), vertex editing (M3), Esri satellite on desktop (M4), Android on the emulator (M5, first half) and the packaged app on a second Windows PC (M7). The tablet run (the second half of M5) is still open. Per the outcome rules, a tablet failure would be the only thing that could still change this verdict. Emulator results make that unlikely: it uses the same OpenGL runtime.

We don't need MapComposeMP (option B) or the WebView (option C) for P0.

## Pinned versions

| Artifact | Version | Notes |
|---|---|---|
| `org.maplibre.compose:maplibre-compose` | **0.17.0** | Latest on Maven Central on 2026-09-24 |
| `…:maplibre-compose-runtime-opengl-android` | 0.17.0 | Android |
| `…:maplibre-compose-runtime-vulkan-windows-x64` | 0.17.0 | Desktop. **No newer release ships the OpenGL/ANGLE or D3D12 Windows runtimes yet** (PR #1439 is merged but unreleased). Vulkan is the only Windows option today. |
| `org.maplibre.nativeffi:maplibre-native-ffi` (transitive) | 0.202609.2 | The native MapLibre core |
| Compose Multiplatform / Kotlin / AGP | 1.12.1 / 2.4.20 / 9.4.1 | Unchanged from Pass 1 |
| Desktop JVM | JDK 25 (Temurin, via Gradle toolchain) | `--enable-native-access=ALL-UNNAMED` is set in `compose.desktop.application.jvmArgs` and is carried into the packaged app's `.cfg` |

## Hardware tested

| Where | GPU / driver | Backend reported by maplibre-compose |
|---|---|---|
| Hrushikesh's laptop (Intel Core 7 240H, Windows 11 26200) | NVIDIA GeForce RTX 4050 Laptop GPU, driver 32.0.15.6100 · Intel Graphics, driver 32.0.101.7076. Task Manager shows `java.exe` on **"GPU 1 – 3D"**. On this laptop that is most likely the RTX 4050; the Performance tab gives the exact mapping. | `Rendered the first map frame with VULKAN on maplibre-windows-vulkan-renderer` |
| Second Windows PC (Hrushikesh) | not recorded | Packaged app ran fine |
| Android emulator `Medium_Tablet`, Android 15, x86_64 | "Android Emulator OpenGL ES Translator (NVIDIA GeForce RTX 4050 Laptop GPU)" | `Rendered the first map frame with OPENGL` |
| Physical tablet | **not run yet** | — |

## Checks

| # | Check | Result | Evidence |
|---|---|---|---|
| M1 | Desktop renders | ✅ **Pass** | OpenFreeMap Liberty renders on Vulkan at 1583×864 physical px (125 % scaling). Hrushikesh ran it maximised and reported pan/zoom "very smooth, no issues", with resize, minimise/restore and idle all fine. |
| M2 | Live overlays at 10 Hz | ✅ **Pass** | `FakeVehicle` publishes through a `StateFlow` every 100 ms into a GeoJSON source. The in-app heap readout stayed between 19 and 31 MB on desktop and between 7 and 15 MB on the emulator while it ran, with no upward trend. Hrushikesh saw no issues in his run. *Caveat:* nobody wrote down heap numbers across a full 10 minutes. |
| M3 | Editing gestures | ✅ **Pass on desktop and emulator** | Click → lat/lon in the status line. Long-press (Android) or right-click (desktop) inserts a vertex on the **nearest edge**. Dragging a handle moves that vertex, redraws the polygon every frame, and doesn't pan the map. Screen↔geo uses `MapState.positionFromScreenLocation` / `screenLocationFromPosition` (in dp). Hrushikesh reported no visible lag on desktop. Screenshot: `assets/adr-001-m3-android-drag.png`. |
| M4 | Satellite + key | ✅ **Pass on desktop** (re-run 2026-09-24 with Hrushikesh's ArcGIS Location Platform key) | Esri World Imagery renders sharp at z16 over CMAC under the overlays, and the "Powered by Esri" attribution shows. **Two corrections to the plan:** (1) the Static Basemap Tiles service has **no imagery style**. Its `/self` style list has only `arcgis/imagery/labels`, so `arcgis/imagery` returns 404. Satellite comes from the keyed `ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` (256 px JPEG). (2) That endpoint **rejects the `X-Esri-Authorization` header**: it replies HTTP 200 with a `499 Token Required` body. It only accepts `?token=`. The spike adds the token in `MapRequestInterceptor.rewriteUrl`, so the style and source URLs stay key-free. Mapbox alternate: not wired (no key). Android: not run, because no key is shipped in the APK (F7). |
| M5 | Android | 🟡 **Emulator pass, tablet pending** | Same `SpikeApp()` composable, OpenGL runtime. M1, M2, M3 and M6 checks pass on the emulator. Two setup bugs found and fixed (F2, F3 below). |
| M6 | Offline `.mbtiles` | ✅ **Pass on both** | A synthetic raster MBTiles (z0–4, 258 KB, `spikes/map-spike/tools/make_offline_mbtiles.py`) bundled as a Compose resource and loaded through `rememberMbtilesUrl` → `mbtiles://`. Renders on desktop (from Gradle **and** from inside the packaged jar) and on the emulator **in airplane mode**. Screenshot: `assets/adr-001-m6-android-offline.png`. |
| M7 | Packaging | ✅ **Pass** | `:spikes:map-spike:createDistributable` → `spikes/map-spike/build/compose/binaries/main/app/KFT-MapSpike/` (**148 MB**, bundled JDK 25 runtime). `KFT-MapSpike.exe` launched outside Gradle with no `JAVA_HOME` renders, drags and loads offline tiles on the dev laptop. Hrushikesh also ran it on a second Windows PC: "working well". The native DLL comes in `maplibre-native-ffi-runtime-vulkan-jvm-…-natives-windows-x64.jar`, and jpackage picked it up with no extra config. `packageMsi` was not run (needs WiX; not a spike question). |
| M8 | KCEF smoke test | ⏭️ **Deferred** | Needs a new repository (`jogamp.org`) and a ~150 MB CEF download on first run, and KCEF's last release (2025.03.23) predates our Compose 1.12 / JDK 25 stack. It only informs GS-3 (P1 3D), so it moves to the start of the P1 3D work. |

## Findings to carry into section 04 (`MapView`)

- **F1: Swapping the base style drops circle layers (maplibre-compose 0.17.0, seen on desktop).** Switching the base style `BaseStyle.Empty` → Liberty brings back fill and line layers but **not** our `CircleLayer`s (vertex handles, vehicle). It happens every time and doesn't recover when the source data updates. **Decision:** `MapView` keeps **one permanent base style** and draws Satellite / Offline / other basemaps as raster layers above it. That's also what a "satellite + labels" hybrid will need. We should report it upstream with a minimal repro before relying on style swaps anywhere.
- **F2: AGP 9's KMP library plugin turns Android resources off.** Without `kotlin { android { androidResources { enable = true } } }`, our `composeResources` are silently missing from the APK and `Res.getUri(...)` crashes at startup. Any module with Compose resources (icons, bundled mbtiles) needs this. It probably belongs in the `kft.kmp.compose` convention plugin.
- **F3: Android 15 is edge-to-edge.** Top-level UI needs `windowInsetsPadding(WindowInsets.safeDrawing)`, or controls sit under the status bar and can't be tapped.
- **F4: Vertex dragging pattern.** A `pointerInput` on the **map's own modifier** (the parent of the map surface and the overlay) listens in `PointerEventPass.Initial`. On press it hit-tests the handles in screen space (24 dp radius). On a hit it consumes the whole gesture. maplibre-compose's gesture code runs in the Main pass and ignores consumed events, so the map doesn't pan underneath. The same code works for mouse and touch.
- **F5: The map API is in dp, pointer events are in px.** Convert with `Density` before calling `positionFromScreenLocation`.
- **F6: GeoJSON updates.** `GeoJsonOptions(synchronousUpdate = true)` is available and trades frame rate for same-frame polygon updates. With the default async preparation, drag already felt immediate on desktop, so start with async.
- **F7: The Esri token goes on at request time, never in the style.** World_Imagery only accepts `?token=`, so `MapRequestInterceptor.rewriteUrl` appends it to Esri requests as they're fetched. The style JSON and source definitions stay key-free, but the token does travel in the request URL, so any HTTP logging must redact it. For Android, putting a key in an APK needs its own decision (per-user keys vs. a KFT key proxy). That's GS-1.
- **F9: Esri source.** Use `World_Imagery` (256 px JPEG) from `ibasemaps-api.arcgis.com`, not Static Basemap Tiles, which only has street/reference styles and an imagery *labels* overlay (`arcgis/imagery/labels`, usable later for a hybrid view). `00-maps-decision.md` §2.2 assumed a 512 px static imagery style, and this finding supersedes that.
- **F8: Attribution.** The default overlay shows the attributions of every source in the style. With the permanent-Liberty approach (F1), the OpenFreeMap credit stays visible under satellite. That's acceptable, but section 04's attribution control should read `TileSourceConfig.attribution` for the visible sources only.

## GS-2 follow-up: crash when the map leaves composition (Pass 5.1, 2026-09-25)

- **Finding (F10):** on desktop (Vulkan, maplibre-compose 0.17.0), disposing a `MaplibreMap` and creating a new one crashes the JVM with `0xC0000374` (native heap corruption). In the app, every Fly→Links switch disposed the map: the log shows `Host surface lost; closing the render session and waiting for a new one`. Reproduced on the dev laptop: the first Fly→Links→Fly survived (a second map session was created), and the second Fly→Links killed the process. Resize and minimise don't dispose the map, so they were never affected. The spike missed this because it had a single screen.
- **Fix:** `App()` composes the Fly screen (and so the one `MapView`) once, **below** the `NavHost`, and never removes it. The Fly route in the `NavHost` is empty, and other tabs cover the map with an opaque M3 `Surface`, which also blocks clicks. The map session now lives as long as the window (one session per run in the log).
- **Verified:** 24 Fly↔Links switches with no crash and no `Host surface lost`. Then, connected to ArduCopter SITL (TCP 5760): Fly→Links→connect→Fly, plus 10 more switches while telemetry streamed, again with no crash. The vehicle and HUD were live on return.
- **Done for Plan (Pass 8, 2026-09-25):** `App()` now owns the one `MapView`; Fly and Plan pass overlays and callbacks to it and draw only their panels. Verified with SITL: Plan→Fly→Links→Fly→Links→Fly with an uploaded mission, no crash.
- **Rule for later screens (Plan):** don't give a screen its own `MapView`. Share the one hoisted map, or accept this crash. Not fixed: the underlying bug in maplibre-compose, which should be reported upstream with a minimal repro (dispose and recreate a `MaplibreMap` twice). Clean disposal was not attempted, because the hoist worked.

## Dev-environment note (not a map finding)

On this laptop, Java NIO fails with `Unable to establish loopback connection`: it can't create its AF_UNIX pipe in the `%TEMP%` path, which uses the `HRUSHI~1` short name. Gradle's launcher is affected, so every build fails. Workaround: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Hrushikesh`. Android Studio isn't affected. The packaged app didn't hit it.

## Consequences

- Section 04 builds `MapView` on maplibre-compose, with the rules above (F1 permanent base style, F4 drag pattern, F2 resources).
- The Windows runtime stays Vulkan until a maplibre-compose release ships OpenGL/ANGLE. Revisit if a field laptop has bad Vulkan drivers.
- Spike modules are deleted once section 04's `MapView` covers the same checks.

## Still open

1. **M5 on the physical tablet:** run `:spikes:map-spike-android:installDebug`, then check pan/zoom, drag a handle, long-press an edge, and Offline in airplane mode. Kill signal: the map is black or crashes on the tablet.
2. **M4 on Android:** blocked on the key-distribution decision (F7, GS-1). Desktop passed.
3. **M8:** deferred to the P1 3D decision (GS-3).
