# KFT Survey GCS — Feature Spec, MVP Cut, Stack and Timeline

**Status:** v2, scope agreed · **Date:** 2026-09-24 · **Owner:** Hrushikesh (solo)
**v2 changes:** MAVLink library fixed to mavlink-kotlin (S6). Architecture fixed to MVVM (S7). Map baseline chosen for zero cost (S8). Separate Claude project and repo confirmed.
**Context:** Team reduced to one developer; pod hardware not accessible. The first deliverable is a Kotlin Multiplatform (Android tablet + desktop JVM) GCS for ArduPilot. Survey comes first; the counter-UAV pod panel plugs in later.
**Repo note (2026-09-24):** P0 vehicles extended to Copter **and Plane** (see `docs/implementation/README.md`).

---

## 0. Scope decisions

| # | Decision | Why |
|---|---|---|
| S1 | **Separate git repo** (`kft-gcs`) from `counter-uav` | Different language, build system, CI and release cadence. The GCS is a platform product for every KFT drone, not a pod component |
| S2 | **Pod wire schema stays owned by `counter-uav/schemas/`** and is consumed by the GCS repo (git submodule, or a copied copy with a CI diff check) | PRD §5.5 says to keep the Python and Kotlin schema definitions adjacent. A separate repo breaks that, so a mechanical sync check replaces adjacency. **Needs a decision-log entry in counter-uav.** |
| S3 | **ArduPilot only** for v1. DJI and UgCS are feature references, not integration targets | The fleet is KFT custom ArduPilot |
| S4 | **TX allowed through one gateway with a message allowlist** (see CF-12 discussion). Guidance setpoints are never sent from the GCS | Keeps hard invariant 7 intact when the pod panel arrives |
| S5 | AI survey report (text goal → analytics) is **out of scope for the GCS v1**. The GCS captures the geotagged imagery; analytics is a separate offline pipeline | Keeps the GCS shippable |
| S6 | **MAVLink: `mavlink-kotlin`** (divpundir), ArduPilot `ardupilotmega` dialect, coroutines adapter. Our own transports sit behind a `MavTransport` interface | Kotlin Multiplatform, coroutine-native, maintained |
| S7 | **MVVM**: Composable screens → ViewModel (`StateFlow<UiState>` + event functions) → repositories → data sources. Unidirectional data flow | Testable ViewModels, clear layering, familiar from Android |
| S8 | **Maps, zero-cost baseline (temporary):** maplibre-compose engine; OpenFreeMap vector street tiles; Esri World Imagery for satellite via a free-tier key; AWS open Terrain Tiles for elevation. All sources behind a `TileSourceConfig` so each can be swapped without code changes. Licences re-checked before any commercial release. 3D engine chosen at P1 | See §3 map table and `docs/implementation/00-maps-decision.md` |

---

## 1. Reference feature survey

What the reference products offer, so that we pick deliberately.

| Capability | DJI Pilot 2 | DJI Terra | UgCS | QGC / Mission Planner (ArduPilot baseline) |
|---|---|---|---|---|
| Waypoint route (speed, heading, gimbal, actions) | ✅ | ✅ (3D preview) | ✅ | ✅ |
| Area / polygon mapping grid | ✅ | ✅ | ✅ Photogrammetry | ✅ Survey |
| GSD ↔ altitude, front/side overlap, camera DB | ✅ | ✅ | ✅ + custom profiles | ✅ known/custom/manual camera |
| Double grid / crosshatch | — | — | ✅ | ✅ refly at 90° |
| Oblique (multi-pass, gimbal angle) | ✅ oblique, smart oblique | ✅ | ✅ | partial |
| Linear / corridor (buffer, zigzag/single, centre line) | ✅ | ✅ | ✅ | ✅ Corridor Scan |
| Facade / vertical / structure scan | slope route | detailed inspection from 3D model | ✅ vertical scan | ✅ Structure Scan |
| Orbit / circlegrammetry / POI | ✅ POI | — | ✅ | orbit |
| Terrain following | ✅ built-in model, DSM import, real-time on some aircraft | — | ✅ Smart AGL, custom DEM (GeoTIFF), rangefinder | ✅ terrain frame, tolerance, climb/descent limits |
| Adaptive (curved) turns, turnaround distance | — | — | ✅ adaptive bank turn | ✅ turnaround, capture in turns |
| Distance-based camera trigger | ✅ | ✅ | ✅ | ✅ |
| Large-area splitting + battery-swap resume | — | — | ✅ | resume (MP) |
| KML / CSV / SHP import | polygon sync (FlightHub) | ✅ | ✅ KML/CSV, KML/KMZ export | ✅ KML/SHP |
| No-fly zones / geofence | ✅ | — | ✅ | ✅ geofence, rally |
| Offline maps | ✅ mbtiles import | — | ✅ offline | ✅ tile cache |
| 3D view, elevation profile | — | ✅ | ✅ full 3D + elevation profile | 2D (+ profile) |
| Real-time 2D map while flying | — | ✅ | — | — |
| LiDAR / magnetometer / GPR tools | L-series only | — | ✅ calibration patterns, geophysics | — |
| Multi-drone | FlightHub 2 | — | ✅ Commander | limited |

**What sets the leaders apart:** UgCS competes on **terrain accuracy** (custom DEM, Smart AGL, low AGL), **mission splitting / resume**, and **specialised patterns**. DJI competes on **simplicity** and tight hardware integration. For a survey customer on ArduPilot, the terrain-following quality and the planning UX are where we can win.

---

## 2. Feature list with priorities

**P0 = rapid prototype · P1 = "good enough" v1 · P2 = later**

### 2.1 Connections

| Feature | Pri | Notes |
|---|---|---|
| UDP (client + listen) | P0 | SITL, network radios, companion computers |
| TCP client | P0 | SITL, bridges |
| Serial — desktop | P0 | jSerialComm |
| Serial — Android USB-OTG | P0 | usb-serial-for-android (FTDI/CP210x/CH34x/CDC) |
| Bluetooth Classic SPP — Android | P1 | RFCOMM socket. The existing KFT GCS already talks MAVLink over BT, so reuse that knowledge |
| Bluetooth — desktop | P1 | **Handled as serial.** Windows exposes a paired SPP device as a virtual COM port, so no separate JVM Bluetooth stack is needed (JVM BT libraries are poor) |
| Connection manager: auto-reconnect, baud select, link-quality stats (loss %, rate), saved profiles | P0 | |
| Multiple simultaneous links / multi-vehicle | P2 | |

### 2.2 MAVLink core

| Feature | Pri |
|---|---|
| Heartbeat, vehicle detection, sysid/compid handling | P0 |
| Telemetry model (attitude, position, GPS, battery, EKF, RC, status text) as Kotlin Flows | P0 |
| **TX gateway with allowlist**, one place in the code that can write, unit-tested | P0 |
| Command protocol with ACK/retry (arm, mode, takeoff, RTL, land) | P0 |
| Mission protocol upload/download (`MISSION_ITEM_INT`, partial-list resend) | P0 |
| Parameter download + basic editor | P1 |
| Geofence + rally protocol | P1 |
| Terrain server (`TERRAIN_REQUEST` → `TERRAIN_DATA`) so the FC can terrain-follow without an SD terrain cache | P1 |
| RTK correction injection (NTRIP client → `GPS_RTCM_DATA`) | P1 — survey accuracy depends on it |
| tlog recording + replay | P1 |
| Calibration flows | P2 (existing KFT GCS covers these) |

### 2.3 Maps

| Feature | Pri |
|---|---|
| 2D map: satellite + street layers, vehicle, trail, home, waypoints, polygons | P0 |
| Draw/edit polygon, polyline, points on the map | P0 |
| Offline tiles (download region + mbtiles import) | P1 |
| 3D view: terrain, mission path at true altitude, vehicle | P1 |
| Elevation profile of a mission against the terrain | P1 |
| Custom DEM (GeoTIFF) and orthophoto overlay | P1 |
| ⚠️ Tile licensing: Google/ESRI/Mapbox imagery all carry terms for use inside a custom app. Pick a legally usable imagery source before shipping | P0 decision |

### 2.4 Mission and survey planning

| Feature | Pri |
|---|---|
| Waypoint editor (alt, speed, hold, yaw, gimbal, camera actions) | P0 |
| **Area survey grid:** camera DB + custom camera, GSD ↔ altitude, front/side overlap, grid angle, turnaround distance, distance trigger, stats (area, photo count, flight time, battery estimate) | P0 |
| Altitude frames: relative, AMSL, terrain (AGL) | P0 relative · P1 AMSL/terrain |
| Double grid / crosshatch | P1 |
| **Corridor scan:** polyline + left/right buffer, zigzag or single pass, include centre line | P1 |
| **Structure / facade scan:** layers around a polygon or face, gimbal angle, standoff distance | P1 |
| Orbit / circlegrammetry | P1 |
| Oblique multi-pass (gimbal angles per pass) | P1 |
| **Terrain following:** DEM (Copernicus GLO-30 / SRTM + custom GeoTIFF) → AGL-constant waypoints with tolerance and max climb/descent checks | P1 |
| Large-area splitting by battery + resume-from-point | P1 |
| Mission validation before upload (altitude limits, fence, climb rates, battery margin, terrain clearance) | P1 |
| Import: KML/KMZ, GeoJSON, SHP, CSV · Export: QGC `.plan`, MP `.waypoints`, KML | P1 |
| Adaptive/curved turns | P2 |
| 3D-model-based inspection planning (DJI Terra style) | P2 |
| LiDAR calibration patterns, geophysics tools | P2 |

### 2.5 Fly view and operations

| Feature | Pri |
|---|---|
| HUD / instrument panel, mode, arm, battery, GPS/EKF status, messages | P0 |
| Actions: arm/disarm, takeoff, mode change, RTL, land, start mission, pause | P0 |
| Pre-flight checklist | P1 |
| Mission progress, current waypoint, photos taken | P1 |
| Large-screen layout (desktop, tablet) + phone-safe fallback | P0 |
| Video pane (RTSP) | P2 — shared with the pod panel (OD-13) |
| Pod panel (telemetry, lock/unlock) | P2 — counter-UAV phase |

---

## 3. Architecture

```
kft-gcs/
  core/mavlink      transports (expect/actual), framing, TX gateway + allowlist
  core/vehicle      vehicle state model, command & mission protocols (commonMain)
  core/planning     PURE geometry: grid, corridor, structure, orbit, terrain-follow,
                    splitting, validation — no I/O, heavy unit tests
  core/terrain      DEM tiles, GeoTIFF reader, elevation queries, FC terrain server
  core/geo-io       KML/KMZ/GeoJSON/SHP/CSV import, .plan/.waypoints/KML export
  ui/map            MapView interface; 2D + 3D engine adapters behind it
  feature/fly, feature/plan, feature/connections, feature/settings
  app/android, app/desktop
```

The same principle as the pod applies: **planning geometry is pure** (commonMain, no I/O), so every survey pattern is tested without a drone or a UI.

### Stack choices

| Area | Choice | Risk / fallback |
|---|---|---|
| UI | Compose Multiplatform (Android + desktop JVM) | — |
| Architecture | MVVM with KMP `androidx.lifecycle` ViewModel, Koin for DI, Compose Multiplatform navigation | — |
| MAVLink | ✅ `mavlink-kotlin` (Kotlin Multiplatform, coroutines adapter, ArduPilot dialect) | Our own transports behind `MavTransport` |
| Serial | jSerialComm (desktop), usb-serial-for-android | — |
| 2D map | `maplibre-compose` — native MapLibre; **Android beta, desktop alpha** | **Week-1 spike.** Fallback: MapComposeMP (desktop), then MapLibre GL JS in a WebView |
| 3D map | CesiumJS in a WebView (Android WebView; KCEF/Chromium on desktop via compose-webview-multiplatform) | KCEF adds roughly 100+ MB of Chromium to the desktop bundle. Cesium ion terrain needs a token/licence; otherwise self-host quantized-mesh terrain built from Copernicus DEM |
| Tiles — street | OpenFreeMap: free, no API key, no request limits, commercial use allowed | Self-host the same tiles later if needed |
| Tiles — satellite | Esri World Imagery through an ArcGIS free-tier key (prototype) | Licence must be confirmed before release; alternative is a paid provider (MapTiler/Mapbox) |
| Elevation | AWS open Terrain Tiles (terrarium) for display; Copernicus GLO-30 for terrain-follow maths | — |
| Persistence | SQLDelight or plain JSON files for missions/cameras | — |
| Test vehicle | ArduPilot SITL (copter + plane) | — |

Reading QGC/Mission Planner source to check survey maths is fine. Copying code is not: QGC is Apache-2.0/GPLv3 dual-licensed and Mission Planner is GPLv3.

---

## 4. Timeline (solo developer, AI-assisted)

| Week | Output | Exit check |
|---|---|---|
| 1 | Repo, KMP skeleton, CI. **Spikes:** maplibre-compose on desktop, Cesium in KCEF, serial on both platforms. UDP to SITL, vehicle on the map | Vehicle moves on the 2D map on both desktop and Android |
| 2–3 | Telemetry model, fly view, command protocol, TCP/serial, TX gateway, mission upload/download, waypoint editor | Arm → takeoff → waypoint mission → RTL in SITL from both platforms |
| 4–5 | Survey grid + camera DB + stats + distance trigger; save/load `.plan` | **🎯 Rapid prototype:** draw polygon → grid → upload → SITL flies it, photos triggered |
| 6–8 | Corridor, structure, orbit, crosshatch, import/export, offline tiles, Bluetooth, params | |
| 9–11 | Terrain following + DEM, 3D view, elevation profile, validation, fence/rally, splitting/resume, RTK injection, terrain server | |
| 12–14 | Hardening + **field testing on a real FC/radio**, UX polish | **🎯 "Good enough" v1** |

- **Rapid prototype: ~5 weeks.**
- **Field-usable v1: ~3–3.5 months.** Plan for 4 months: terrain following and the 3D map are where estimates usually slip.
- Serial/Bluetooth work needs only a spare flight controller and telemetry radio, not the pod hardware.
- Treat this as a scoped subset. QGC and UgCS are the product of many engineer-years. v1 wins on survey planning and terrain quality, not breadth.

---

## 5. Open items

- GS-1 Satellite imagery licence for commercial release (prototype baseline set in S8)
- GS-2 maplibre-compose desktop viability (week-1 spike). Fallback: MapComposeMP on desktop, then MapLibre GL JS in a WebView
- GS-3 3D engine (P1): CesiumJS in a WebView vs MapLibre GL JS 3D terrain in a WebView
- GS-4 ✅ Closed: mavlink-kotlin (S6)
- GS-5 Camera(s) for survey — pod's IMX296 fisheye is not a survey camera; the camera DB needs the actual payload
- GS-6 Decision-log entry in counter-uav for the separate repo and schema sync (S2)
