# Pass 2 — W1-2: Map engine spike (prove or kill maplibre-compose)

Paste into Claude Code, from the repo root: `Do the task in docs/prompts/pass-02-map-spike.md`

## Task
Run the week-1 map spike from `docs/implementation/00-maps-decision.md` §4 (checks M1–M8) and record the verdict in `docs/decisions/ADR-001-map-engine.md`.

## Before you start
1. Follow `CLAUDE.md` (including §7 plugins: read `graphify-out/GRAPH_REPORT.md`, Ponytail in `lite` mode).
2. Read `docs/implementation/00-maps-decision.md` in full and the Pass 1 entry in `docs/learning-log.md`.
3. Check the current maplibre-compose version and its Windows runtime artifacts on Maven Central. If a release newer than 0.17.0 ships the OpenGL/D3D12 Windows runtimes, use it and note that in the ADR.

## Scope
- Code lives in a new throwaway module `spikes:map-spike` (desktop + Android entry points). Nothing in `core/`, `ui/`, `feature/` or `app/` changes, except `settings.gradle.kts` (include the spike) and `gradle/libs.versions.toml` (maplibre-compose runtime artifacts).
- Use OpenFreeMap Liberty for the street style. For satellite, read the Esri key from `local.properties` (`esri.apiKey=`) or the `ESRI_API_KEY` environment variable. **Never commit a key.** If there's no key, skip M4 and say so.
- Android: the OpenGL runtime. Windows: the Vulkan runtime (and OpenGL/D3D12 if available, to compare).
- M3 (drag a polygon vertex) is the most important check. Build it properly: screen↔geo conversion, a hit test on vertex handles, and a GeoJSON source updated during the drag.
- M7: run `createDistributable` and launch the packaged app from `app/desktop/build/...` outside Gradle.
- M8: at most one hour.

## What I (Hrushikesh) will do by hand
Stop and ask me to do these, then wait for my results before writing the ADR:
- Run the desktop spike on my laptop. I'll report the GPU name and how smooth pan/zoom/drag feels.
- Run it on the Android emulator and on my tablet.
- Run the packaged app on a second Windows machine or a clean VM, if I have one.

## Done when
- `docs/decisions/ADR-001-map-engine.md` records: the verdict (adopt A / A on Android + MapComposeMP on desktop / B everywhere), each M-check with pass/fail and evidence, pinned versions, and the GPU/driver tested.
- `./gradlew check` passes.
- The pass summary is appended to `docs/learning-log.md`, and the commit is pushed.
