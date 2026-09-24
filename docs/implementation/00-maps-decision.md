# 00 — Maps: decision, recommendation and week-1 spike

**Status:** Draft for review · **Date:** 2026-09-24 · **Checked against sources on:** 2026-09-24
**Decides:** GS-2 (desktop map viability) for P0 and the P0 tile sources. **Informs:** GS-1 (satellite licence), GS-3 (3D engine).
**Rule:** every source below sits behind `TileSourceConfig`, and the engine sits behind `MapView` (section 04). Nothing in `feature/*` may import a map library directly.

> "Verify" means: Claude Code must check the current value on the linked page or Maven Central at the start of the spike, and write the checked value into `gradle/libs.versions.toml` or `docs/decisions/`. Library versions move quickly in this area.

---

## 1. What changed since the baseline was written

Three findings change the plan. None of them overturn it.

1. **maplibre-compose desktop is now native, not a WebView.** Version 0.17.0 renders on desktop with MapLibre Native through per-OS runtime artifacts (Windows x64: Vulkan). It **requires Java 25** and the JVM flag `--enable-native-access=ALL-UNNAMED`. Desktop is still labelled **alpha**, Android **beta**. A PR merged on 2026-09-18 adds OpenGL (via ANGLE) and Direct3D 12 runtimes for Windows. That matters for laptops with weak Vulkan drivers, but it will probably only ship in the next release after 0.17.0.
2. **There is a better fallback than a WebView for 2D.** MapComposeMP (Apache-2.0, 1.1.1, marked stable, June 2026) is pure Compose Multiplatform: raster tiles only, with markers, drag, paths and gestures on Android and desktop. It needs no native library and no Chromium. Satellite imagery is raster anyway, so it can fully replace the map on desktop if MapLibre desktop fails.
3. **The Cesium ion free tier doesn't fit KFT.** The Community plan is for non-commercial use, and it excludes organisations with more than $50K annual revenue or funding. Commercial plans start at $149/month. CesiumJS itself (Apache-2.0) is still usable if we host the terrain ourselves. This affects the P1 3D choice, not P0.

---

## 2. Decision tables

### 2.1 Map engine (2D, P0)

| Option | Android | Desktop (Windows) | Licence | Offline / mbtiles | Integration cost | Verdict |
|---|---|---|---|---|---|---|
| **A. maplibre-compose 0.17.x** | Beta. MapLibre Native Android 13.x. Choose OpenGL or Vulkan runtime | **Alpha.** Native MapLibre, Vulkan runtime, Java 25. OpenGL/D3D12 runtimes are on `main` | BSD-3 | Offline region download on all non-web platforms. MapLibre Native reads `mbtiles://` and `pmtiles://` sources (verify on desktop) | Lowest: one Compose API, vector and raster, GeoJSON sources, layer click handling | ✅ **P0 primary** |
| B. MapComposeMP 1.1.x | Stable | Stable (pure Compose, no native code) | Apache-2.0 | We write the `TileStreamProvider`. Reading mbtiles (SQLite) is easy | Medium: raster only, so we write the Web-Mercator maths and draw polygons ourselves | ✅ **Fallback 1** (desktop-only swap) |
| C. MapLibre GL JS in WebView (compose-webview-multiplatform 2.0.x, KCEF on desktop) | Android WebView | KCEF/Chromium, roughly 100+ MB extra, JS bridge | BSD-3 + CEF (BSD) | Service-worker or intercepted requests. Request interception is not supported on desktop in the WebView lib | Highest: two languages, JS bridge for every edit gesture, harder tests | ⚠️ **Fallback 2**, and the likely P1 3D host |
| D. Platform-specific (MapLibre Android + something else on desktop without Compose) | — | — | — | — | Two UIs | ❌ Breaks "one codebase" |

**Why A over C for P0:** planning is a drawing-heavy UI (drag vertices, insert midpoints, live grid preview). In A, those gestures stay in Kotlin, inside the ViewModel test boundary. In C, every gesture crosses a JS bridge. **Why B is fallback 1 and not C:** B has no native or Chromium risk, and everything P0 needs (satellite raster, polygon, grid lines, vehicle, trail) can be drawn in pure Compose.

### 2.2 Tile and data sources

| Layer | P0 choice | Free limits | Commercial use | Offline | Attribution (must show) | Swap candidates |
|---|---|---|---|---|---|---|
| **Street (vector)** | **OpenFreeMap** "Liberty" style | No key, no request limits | ✅ Allowed (FAQ) | ✅ Weekly planet MBTiles/Btrfs downloads, self-hostable | "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" | Self-hosted OpenFreeMap, MapTiler, Protomaps PMTiles |
| **Satellite (raster)** | **Esri imagery via ArcGIS Location Platform** Static Basemap Tiles (`arcgis/imagery` style, verify exact name; `arcgis/imagery-labels` confirmed) | 2M basemap tiles/month free, then $0.15 per 1,000 | Allowed with a Location Platform account (paid beyond the free tier). Confirm the exact terms before release (GS-1) | ⚠️ Bulk pre-download in a non-Esri SDK is **not documented**, so treat it as **not allowed** until confirmed. The ordinary HTTP cache is fine | "Powered by Esri" + imagery providers | **Mapbox Satellite** (Raster Tiles API: 750k tiles/month free; use with MapLibre is officially documented). Google 2D Map Tiles (100k/month, 15k/day cap, strict caching rules), rejected for now |
| **Elevation (display, P1)** | AWS Open Terrain Tiles, terrarium PNG (`elevation-tiles-prod`) | Free (AWS Open Data) | ✅ With the Joerd attribution list | ✅ Bucket is public, tiles can be pre-fetched | Joerd attribution list | **Mapterhorn** (terrarium PMTiles: global 30 m plus high-res regions, active project, 2025–26) |
| **Elevation (terrain-follow maths, P1)** | Copernicus DEM GLO-30 (COG on AWS `copernicus-dem-30m`) | Free | ✅ With attribution | ✅ Download tiles per area | "Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA" | SRTM 1″, customer GeoTIFF DSM/DTM |

Notes you should know:
- **Tile formats:** Esri static tiles are **512 px PNG** in `{z}/{y}/{x}` order (y before x). Put this in `TileSourceConfig` (`tileSize`, URL template), not in code.
- **GLO-30 is a surface model (DSM).** It includes tree canopy and buildings. For terrain following, this gives conservative clearance but makes altitude "bump" over forests. We'll record this as a P1 design choice, with the option of adding a DTM later.
- **Esri token:** the ArcGIS access token needs the `premium:user:staticbasemaptiles` privilege. Pass it in a header through maplibre-compose's `MapRequestInterceptor` where possible, not in the URL. Keep it out of git (see section 01, `local.properties`).
- **Don't use the keyless legacy `server.arcgisonline.com/.../World_Imagery` URL** in the product, even though many apps do. Use the keyed, metered service so our usage is covered by a real account.
- **OpenFreeMap has no SLA.** That's fine for P0. The field-usable v1 needs offline regions anyway (P1), which removes the runtime dependency.
- **India map-boundary rule (release blocker, not a P0 blocker):** maps shown to users in India are expected to show national boundaries as officially published. OSM-derived tiles, Esri, and Mapbox may not do this by default. Check with KFT's regulatory contact before any commercial release. It's the same class of issue as the DGCA work. Logged as R-M5 in section 10. (I'm not a lawyer, so treat this as a flag to check, not advice.)

### 2.3 3D (P1, decide at the end of week 5, not now)

| Option | Terrain source at zero cost | Pros | Cons |
|---|---|---|---|
| MapLibre GL JS 5.x 3D terrain in WebView | Terrarium tiles (AWS / Mapterhorn) directly | Same style JSON and tiles as 2D. Lighter. Good for "mission path over terrain" | 2.5D camera. No 3D Tiles |
| CesiumJS in WebView | Self-hosted quantized-mesh built from GLO-30, or terrarium through a custom provider. Cesium ion only on a paid plan | True 3D globe, 3D Tiles (photogrammetry models), better for structure-scan previews | Heavier. Ion licence cost. More JS |
| MapLibre Native 3D terrain (in maplibre-compose) | — | Would be ideal | **Doesn't exist yet.** It's a roadmap item in MapLibre Native, with no release date |

Current leaning: **MapLibre GL JS 3D terrain** for P1 (it's also fallback C), and Cesium only if structure-scan planning needs real 3D models. The week-1 spike includes a 1-hour KCEF smoke test, so both the P1 host and fallback C are de-risked early.

---

## 3. Recommendation for P0

> **Engine:** maplibre-compose **0.17.x** on both platforms, **OpenGL runtime on Android** (the most compatible choice across cheap tablets and the emulator), **Vulkan runtime on Windows**. Move to the OpenGL/ANGLE desktop runtime when it ships, if the spike shows Vulkan driver problems. Desktop JVM target on **JDK 25**.
> **Street:** OpenFreeMap Liberty. **Satellite:** Esri Static Basemap Tiles with an ArcGIS Location Platform key. Mapbox Satellite is pre-configured as a second `TileSourceConfig` entry.
> **Elevation / 3D:** not in P0. Config entries exist, disabled.
> **Fallback if desktop fails the spike:** keep maplibre-compose on Android and use **MapComposeMP on desktop** behind the same `MapView` interface (`expect`/`actual` or DI-selected adapter). Only use the WebView route if both A and B fail.

Consequences for the other sections:
- Section 01: the desktop app uses a JDK 25 toolchain. The Kotlin, Compose Multiplatform and AGP versions must be ones that support JVM 25 targets (verify). jSerialComm, Koin and mavlink-kotlin must run on JDK 25 (verify in the spike).
- Section 04: `MapView` must not expose MapLibre types. Overlays are passed as our own `MapOverlay` model (polygon, polyline, markers, vehicle). Each adapter turns them into GeoJSON layers (A) or Compose paths (B).
- Section 04: every active source's attribution is shown by an attribution control that reads `TileSourceConfig.attribution`.

---

## 4. Week-1 map spike: prove or kill

**Timebox:** 2 Claude Code sessions (about 1.5 days), inside week 1 (section 07, tasks W1-3 and W1-4). Code lives in `spikes/map-spike/`, which is thrown away. What we learn goes into `docs/decisions/ADR-001-map-engine.md`.

| # | Check | How | Pass | Kill signal |
|---|---|---|---|---|
| M1 | Desktop renders | Compose Desktop window, maplibre-compose 0.17.x + `runtime-vulkan-windows-x64`, JDK 25, OpenFreeMap Liberty | Map shows. Pan/zoom smooth (no visible stutter at 1080p). Resize, minimise/restore and 10 min idle cause no crash | Black map or native crash on your laptop, with no working backend |
| M2 | Live overlays | GeoJSON source fed from a `StateFlow` at 10 Hz (fake vehicle circling) + polygon fill + line + circle layers | Vehicle moves smoothly, no memory growth over 10 min (check with the JVM heap graph in the IDE) | Source updates stall or leak |
| M3 | Editing gestures | Click → lat/lon. Long-press adds a vertex. Drag a vertex handle (pointer events on top of the map, converting screen↔geo), redraw the polygon each frame | Drag follows the finger/mouse with no obvious lag. Feels like QGC | Can't get screen↔geo conversion, or lag >100 ms |
| M4 | Satellite + key | Esri raster source, token added via `MapRequestInterceptor` header (or URL parameter if headers fail). Mapbox satellite as an alternate. Attribution text shown | Tiles load. 512 px tiles look sharp. Attribution visible | Can't authenticate from MapLibre Native on either platform |
| M5 | Android | Same composable, OpenGL runtime, on the **emulator** and the **physical tablet** | M1–M4 pass on both | Emulator fails but tablet works: note it and keep going. Tablet fails: kill |
| M6 | Offline source | A small `.mbtiles` (region extract) loaded as a local source on both platforms | Renders with networking off | Not supported on desktop: note it for P1 (not a P0 kill) |
| M7 | **Packaging** | `./gradlew :spikes:map-spike:createDistributable` (and `packageMsi`), run on a **clean Windows machine or VM** with no JDK installed | Map renders from the packaged app (native DLLs load, JVM flag applied) | Native library fails to load when packaged |
| M8 | KCEF smoke test (1 h) | compose-webview-multiplatform on desktop showing a local HTML page with MapLibre GL JS + terrarium 3D terrain | Page renders, JS→Kotlin bridge message received | Only noted. Affects GS-3, not P0 |

**Outcome rules**
- M1–M5 and M7 pass → **Adopt A.** Write ADR-001 with the pinned versions.
- Desktop fails M1, M3 or M7, Android passes → **A on Android, B (MapComposeMP) on desktop.** Adds about 2 sessions to week 1. `MapView` shields everything else.
- Android fails too → re-run M1–M5 with B on both platforms. The abstraction design doesn't change.
- Evidence to keep: screenshots/GIF of M3 on both platforms, the tested GPU/driver name, and the exact artifact versions, all recorded in ADR-001.

---

## 5. Sources checked (2026-09-24)

- maplibre-compose: [repo](https://github.com/maplibre/maplibre-compose) · [getting started (0.17.0, Java 25, runtimes)](https://maplibre.org/maplibre-compose/getting-started/) · [PR #1439 desktop backends](https://github.com/maplibre/maplibre-compose/pull/1439) · [interaction](https://maplibre.org/maplibre-compose/interaction/) · [request rewriting](https://maplibre.org/maplibre-compose/requests/)
- [MapComposeMP](https://github.com/p-lr/MapComposeMP) · [compose-webview-multiplatform](https://github.com/KevinnZou/compose-webview-multiplatform)
- [OpenFreeMap](https://openfreemap.org/)
- Esri: [Location Platform pricing](https://location.arcgis.com/pricing/) · [Static Basemap Tiles](https://developers.arcgis.com/documentation/mapping-and-location-services/mapping/basemaps/introduction-static-basemap-tiles-service/)
- Mapbox: [pricing](https://www.mapbox.com/pricing) · [Mapbox APIs in MapLibre](https://docs.mapbox.com/help/dive-deeper/mapbox-in-maplibre/)
- Google: [Map Tiles usage and billing](https://developers.google.com/maps/documentation/tile/usage-and-billing) · [pricing](https://mapsplatform.google.com/pricing/)
- Elevation: [AWS Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) · [Mapterhorn](https://mapterhorn.com/) · [Copernicus DEM on AWS](https://registry.opendata.aws/copernicus-dem/)
- 3D: [Cesium ion pricing](https://cesium.com/platform/cesium-ion/pricing/) · [MapLibre Native Terrain3D roadmap](https://maplibre.org/roadmap/maplibre-native/terrain3d/) · [MapLibre newsletter Apr 2026](https://maplibre.org/news/2026-05-02-maplibre-newsletter-april-2026/)
