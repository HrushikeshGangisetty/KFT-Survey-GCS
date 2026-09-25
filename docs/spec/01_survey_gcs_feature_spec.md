# KFT Survey GCS — Feature Spec, MVP Cut, Stack and Timeline

**Status:** v3, scope agreed · **Date:** 2026-09-25 · **Owner:** Hrushikesh (solo)
**v3 changes:** The GCS never commands flight: arming, takeoff, mode changes, RTL and landing belong to the pilot on the RC (S9). Every MAVLink message is checked against the dialect XML and ArduPilot's own handling (S10). Mission upload/download always treats seq 0 as home (S11). New open item GS-8 (numbered GS-7 in the first v3 draft). S12 (KFT login) and GS-9 added in Pass 12. The §2.2 and §2.5 rows and the week 2–3 exit check follow from S9.
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
| S9 | **No flight actions from the GCS.** The GCS never arms, disarms, takes off, changes mode, commands RTL or lands. The pilot does all of these on the RC (in SITL, MAVProxy stands in for the RC). There are no flight-action buttons. `MavTxGateway` rejects these as immediate commands (`COMMAND_LONG`/`COMMAND_INT` ARM_DISARM, NAV_TAKEOFF, DO_SET_MODE, NAV_RETURN_TO_LAUNCH, NAV_LAND, and `SET_MODE`). The same commands may still appear as **mission items** inside `MISSION_ITEM_INT`. `MISSION_SET_CURRENT` is allowed only while the vehicle is disarmed (it picks where AUTO starts; in flight it would redirect the aircraft). Mission upload or clear while armed is allowed after a confirmation that names the current mode | One authority for flight: the pilot's hands. Keeps the GCS out of the pod's guidance path by construction, and makes the GCS a planning and monitoring tool, which is what the survey product needs |
| S10 | **Every MAVLink message and command is checked against the source.** Before use: its definition in `common.xml` / `ardupilotmega.xml` (or `minimal.xml` / `standard.xml`), and ArduPilot's own handling (the `GCS_MAVLink` source and the ArduPilot docs). The KDoc of the code that sends or parses it names the dialect and notes any ArduPilot-specific behaviour | The MAVLink spec and ArduPilot differ in places (for example, ArduPilot requests mission items with the deprecated `MISSION_REQUEST`, ignores seq 0 on upload, and ignores `MISSION_ACK` from a GCS). Code written from the spec alone breaks on the real autopilot |
| S11 | **Mission seq 0 is home.** Upload always sends the vehicle's current home (from `HOME_POSITION`) as seq 0, and the first real item is seq 1. On download, seq 0 becomes home and is never shown as a waypoint. A unit test fails if seq 0 isn't home | ArduPilot stores home at index 0 and numbers the mission from 1. Getting the offset wrong shifts every item by one, which is a silent and dangerous bug |
| S12 | **KFT login: same fleet key and APP_ID as the Android GCS, key from local.properties KFT_APP_SECRET, never in git.** After the first heartbeat the GCS runs the ardupilotKFT HMAC challenge-response (MAV_CMD_USER_1/USER_2, APP_ID 1, HMAC-SHA256 truncated to 24 bytes, sent as 6 raw little-endian floats). The connect-time requests and mission traffic wait for it. Stock ArduPilot answers UNSUPPORTED and is treated as legacy firmware. With no valid key the GCS shows "KFT login: no key configured" and doesn't try. Design: ADR-003 | KFT flight controllers drop everything but HEARTBEAT and the login until a GCS authenticates, so without it the survey GCS can't talk to the fleet. Reusing the Android GCS's identity means no firmware change |

---

## 1. Reference feature survey

What the reference products offer, so that we pick deliberately.

| Capability | DJI Pilot 2 | DJI Terra | UgCS | QGC / Mission Planner (ArduPilot baseline) | ArduDeck (ArduPilot, GPL-3.0) |
|---|---|---|---|---|---|
| Waypoint route (speed, heading, gimbal, actions) | ✅ | ✅ (3D preview) | ✅ | ✅ | ✅ grouped waypoints |
| Area / polygon mapping grid | ✅ | ✅ | ✅ Photogrammetry | ✅ Survey | ✅ grid |
| GSD ↔ altitude, front/side overlap, camera DB | ✅ | ✅ | ✅ + custom profiles | ✅ known/custom/manual camera | ✅ plan by altitude or GSD, live stats |
| Double grid / crosshatch | — | — | ✅ | ✅ refly at 90° | ✅ |
| Oblique (multi-pass, gimbal angle) | ✅ oblique, smart oblique | ✅ | ✅ | partial | ? |
| Linear / corridor (buffer, zigzag/single, centre line) | ✅ | ✅ | ✅ | ✅ Corridor Scan | ✅ corridor |
| Facade / vertical / structure scan | slope route | detailed inspection from 3D model | ✅ vertical scan | ✅ Structure Scan | ? |
| Orbit / circlegrammetry / POI | ✅ POI | — | ✅ | orbit | circular / spiral grids, panorama |
| Terrain following | ✅ built-in model, DSM import, real-time on some aircraft | — | ✅ Smart AGL, custom DEM (GeoTIFF), rangefinder | ✅ terrain frame, tolerance, climb/descent limits | ✅ DEM sampling, auto-inserted clearance waypoints |
| Adaptive (curved) turns, turnaround distance | — | — | ✅ adaptive bank turn | ✅ turnaround, capture in turns | ? |
| Distance-based camera trigger | ✅ | ✅ | ✅ | ✅ | ? |
| Large-area splitting + battery-swap resume | — | — | ✅ | resume (MP) | ? |
| KML / CSV / SHP import | polygon sync (FlightHub) | ✅ | ✅ KML/CSV, KML/KMZ export | ✅ KML/SHP | ✅ KML/KMZ/GeoJSON/SHP, pasted ground points |
| No-fly zones / geofence | ✅ | — | ✅ | ✅ geofence, rally | ? |
| Offline maps | ✅ mbtiles import | — | ✅ offline | ✅ tile cache | ? |
| 3D view, elevation profile | — | ✅ | ✅ full 3D + elevation profile | 2D (+ profile) | altitude profile, flight preview playback |
| Real-time 2D map while flying | — | ✅ | — | — | ? |
| LiDAR / magnetometer / GPR tools | L-series only | — | ✅ calibration patterns, geophysics | — | — |
| Multi-drone | FlightHub 2 | — | ✅ Commander | limited | ? |

**Planning-UX rows (from ArduDeck, not in the table above):** mission items in named groups whose headers show distance, time and GSD; distinct icons for file actions (disk) and vehicle actions (arrows); a "NOT UPLOADED" state after every edit; full undo/redo and autosave. ArduDeck is GPL-3.0: its docs and screenshots are used for ideas only, and no code, icons or assets are copied (the same rule as QGC and Mission Planner, §3). Source: ardudeck.com/docs/Mission-Planning, read 2026-09-25. A "?" in its column means the docs didn't say.

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
| Command protocol with ACK/retry for non-flight commands (`SET_MESSAGE_INTERVAL`, `REQUEST_MESSAGE`). No flight commands (S9) | P0 |
| Mission protocol upload/download/clear (`MISSION_ITEM_INT`, per-item resend, timeouts, cancel), home at seq 0 (S11), progress from `MISSION_CURRENT` / `MISSION_ITEM_REACHED` | P0 |
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
| Mission as ordered, renamable groups (waypoints, survey…), header stats per group; survey groups keep their parameters and generate items; flat list only for upload/export | P0 |
| Undo/redo of plan edits (Ctrl+Z / Ctrl+Shift+Z, buttons on tablet) | P0 |
| Upload preview (flattened items + warnings, then confirm) and plan-vs-vehicle state ("Not uploaded", "Vehicle mission ≠ plan" in Fly) | P0 |
| Save/load plans in our own JSON (groups + survey parameters) | P0 |
| Camera presets (bundled JSON, "unverified" until checked) + custom cameras; GSD-first or altitude-first; batteries and data-size estimates; interval and GSD warnings | P0 |
| Plane: fly every k-th line when spacing < 2 × turn radius, so turns need no loop | P0 |
| Autosave of the plan being edited (ArduDeck) | P1 |
| Circular / spiral grids, panorama capture (ArduDeck) | P2 |
| Flight preview: play the planned flight on a timeline (ArduDeck) | P2 |
| Paste surveyed ground points as text (ArduDeck) | P2 |
| Mission validation before upload (altitude limits, fence, climb rates, battery margin, terrain clearance) | P1 |
| Import/export QGC `.plan` and MP `.waypoints` as plain items (no survey parameters) | P0 |
| Import: KML/KMZ, GeoJSON, SHP, CSV · Export: KML | P1 |
| Adaptive/curved turns | P2 |
| 3D-model-based inspection planning (DJI Terra style) | P2 |
| LiDAR calibration patterns, geophysics tools | P2 |

### 2.5 Fly view and operations

| Feature | Pri |
|---|---|
| HUD / instrument panel, mode, arm, battery, GPS/EKF status, messages | P0 |
| ~~Actions: arm/disarm, takeoff, mode change, RTL, land, start mission, pause~~ **Removed (S9).** The GCS shows mode and armed state; the pilot acts on the RC | — |
| Pre-flight checklist | P1 |
| Mission progress, current waypoint | P0 (needed for the week 2–3 exit check) |
| Photos taken / planned, photo markers on the map (from CAMERA_FEEDBACK) | P0 (rapid-prototype exit check) |
| Large-screen layout (desktop, tablet): map-first, floating toolbar, right-hand panel | P0 |
| Phone-sized layout fallback | P1 (later, per the Pass 14–16 brief) |
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
| 2–3 | Telemetry model, fly view, command protocol (non-flight), TCP/serial, TX gateway, mission upload/download, waypoint editor | The GCS uploads a waypoint mission; the pilot (MAVProxy in SITL) switches to GUIDED, arms, takes off and switches to AUTO; the mission flies and its progress shows in the GCS. Copter and Plane SITL, from desktop and the Android emulator |
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
- GS-7 ✅ Closed: app id com.kft.survey (debug com.kft.survey.dev); com.kft.gcs stays with the existing GCS
- GS-8 The pod contract §3 table still lists operator commands (arm, takeoff, AUTO) and safe-direction modes (RTL, LOITER, LAND, BRAKE) as allowed from the GCS. S9 is stricter: the GCS sends none of them. The two don't conflict (the contract permits, it doesn't require), but the table should be brought in line in `counter-uav` so the pod team doesn't assume the GCS can revoke GUIDED. Owner: Hrushikesh with the pod side
- GS-9 Key is extractable from the APK/JAR, and one fleet key means one extraction unlocks all drones. Move to one key per drone before wider release. (Also found in Pass 12: the ardupilotKFT source commits the app keys in `KFT_GCSAuth.h`; once any GCS logs in, the firmware is unlocked on every link, and any heartbeat keeps it unlocked.) Owner: Hrushikesh with the firmware side
