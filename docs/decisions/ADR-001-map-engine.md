# ADR-001 — Map engine for P0

**Status:** Accepted (one check still open: M5 on the physical tablet, see below)
**Date:** 2026-09-24 · **Pass:** 2 (W1-2) · **Spike code:** `spikes/map-spike`, `spikes/map-spike-android` (throwaway)
**Context:** `docs/implementation/00-maps-decision.md` §2–§4

## Verdict

> **Adopt A: maplibre-compose 0.17.0 on both platforms.** OpenGL runtime on Android, Vulkan runtime on Windows x64.

Every check that could kill option A passed on the machines we have: desktop render (M1), live overlays (M2), vertex editing (M3), Android on the emulator (M5, first half) and the packaged app on a second Windows PC (M7). The tablet run (the second half of M5) is still open. Per the outcome rules, a tablet failure would be the only thing that could still change this verdict. Emulator results make that unlikely: it uses the same OpenGL runtime.

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
| M4 | Satellite + key | ⏭️ **Skipped: no Esri key** | Wiring is in place: `MapRequestInterceptor` adds `X-Esri-Authorization: Bearer <key>` only for `static-map-tiles-api.arcgis.com`, and the raster source uses 512 px tiles. Unauthenticated probes return `499 Token Required` for any style name, so the style name `arcgis/imagery` is **still unverified**. Mapbox alternate: not wired (no key either). |
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
- **F7: Esri token goes in a header, never the URL.** The tile URL (and the tile cache key) stays key-free. For Android, putting a key in an APK needs its own decision (per-user keys vs. a KFT key proxy). That's GS-1.
- **F8: Attribution.** The default overlay shows the attributions of every source in the style. With the permanent-Liberty approach (F1), the OpenFreeMap credit stays visible under satellite. That's acceptable, but section 04's attribution control should read `TileSourceConfig.attribution` for the visible sources only.

## Dev-environment note (not a map finding)

On this laptop, Java NIO fails with `Unable to establish loopback connection`: it can't create its AF_UNIX pipe in the `%TEMP%` path, which uses the `HRUSHI~1` short name. Gradle's launcher is affected, so every build fails. Workaround: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Hrushikesh`. Android Studio isn't affected. The packaged app didn't hit it.

## Consequences

- Section 04 builds `MapView` on maplibre-compose, with the rules above (F1 permanent base style, F4 drag pattern, F2 resources).
- The Windows runtime stays Vulkan until a maplibre-compose release ships OpenGL/ANGLE. Revisit if a field laptop has bad Vulkan drivers.
- Spike modules are deleted once section 04's `MapView` covers the same checks.

## Still open

1. **M5 on the physical tablet:** run `:spikes:map-spike-android:installDebug`, then check pan/zoom, drag a handle, long-press an edge, and Offline in airplane mode. Kill signal: the map is black or crashes on the tablet.
2. **M4:** needs an ArcGIS Location Platform key with the `premium:user:staticbasemaptiles` privilege. Put it in `local.properties` as `esri.apiKey=` and run the desktop spike.
3. **M8:** deferred to the P1 3D decision (GS-3).
