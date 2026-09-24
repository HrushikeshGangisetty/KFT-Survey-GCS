# KFT GCS — Implementation Document

The build guide for `kft-gcs`, for Hrushikesh and Claude Code. Read it together with `docs/spec/01_survey_gcs_feature_spec.md` and `docs/spec/02_pod_interface_contract.md`. The root `CLAUDE.md` holds the rules. This folder holds the reasoning and the plan.

## Sections (reviewed one at a time)

| # | File | Status |
|---|---|---|
| 00 | `00-maps-decision.md`: map engine, tile sources, week-1 spike | **Draft, in review** |
| 01 | `01-project-structure.md`: Gradle/KMP modules, source sets, version catalog, libraries to verify | Not started |
| 02 | `02-mvvm-conventions.md`: conventions + worked Connections screen (UiState, ViewModel, repository, Koin, navigation, tests) | Not started |
| 03 | `03-mavlink-layer.md`: transports, connection manager, heartbeat/vehicle detection, telemetry Flows, commands with ACK/retry, mission state machine, TX gateway | Not started |
| 04 | `04-map-layer.md`: `MapView` abstraction, `TileSourceConfig`, drawing/editing, 3D slot | Not started |
| 05 | `05-survey-maths.md`: GSD, footprint, overlap, spacing, trigger, turnaround (copter **and plane**), clipping, stats, unit tests | Not started |
| 06 | `06-sitl-wsl2.md`: ArduPilot SITL in WSL2 (copter + plane), connecting desktop, emulator and tablet | Not started |
| 07 | `07-milestones-w1-w5.md`: tasks sized to one Claude Code session each, with acceptance criteria and tests | Not started |
| 08 | `/CLAUDE.md` (repo root): architecture rules, import rules, TX gateway rule, tests, pass summary | Not started |
| 09 | `09-learning-notes.md`: concepts per milestone, what to read, references | Not started |
| 10 | `10-risks-open-items.md` | Not started |

## Inputs fixed for this document

- Fixed decisions S1–S8 in the feature spec (mavlink-kotlin, MVVM, TX gateway, survey first, separate repo). These are not reopened here.
- SITL runs in **WSL2 (Ubuntu)** on the Windows laptop.
- Android testing uses **both** the emulator (fast loops) and a physical tablet on the same Wi-Fi (maps, USB-OTG serial).
- **P0 vehicles: Copter and Plane.** The survey grid and SITL setup must cover fixed-wing turnarounds (lead-in/lead-out, turn radius) as well as multirotor. This is more than the spec's original P0 and is tracked as schedule risk R-S1.

## Assumptions (tell me if any are wrong)

- Git host is GitHub, and CI runs on GitHub Actions (Windows and Linux runners).
- GS-5 (survey camera) is still open. The P0 camera DB ships with a custom-camera form plus a few common presets. The real payload gets added when it's known.
- Desktop target is Windows x64 first. macOS/Linux builds are allowed but not tested in P0.
