# CLAUDE.md — rules for working in kft-gcs

KFT GCS is a Kotlin Multiplatform ground control station for KFT's ArduPilot drones. It targets Android tablets and desktop JVM (Windows first) from one codebase. Survey planning comes first, and a counter-UAV pod panel plugs in later.

**Who you are working with:** Hrushikesh is the only developer. He does not write the code himself, but he must understand every line of it. So write code a careful reader can follow, explain the reasoning behind each design in the pass summary, and prefer clarity over cleverness.

Read these before starting any task:
1. This file.
2. `docs/implementation/README.md` (section index) and the section that covers your task.
3. The latest entry in `docs/learning-log.md` (what the previous pass did).
4. `docs/spec/01_survey_gcs_feature_spec.md` and `docs/spec/02_pod_interface_contract.md`, when the task touches scope or anything that transmits.

---

## 1. Fixed decisions (do not reopen)

- **MAVLink:** `mavlink-kotlin` (`com.divpundir.mavlink`), ArduPilot `ardupilotmega` dialect, coroutines adapter. Our own transports (UDP, TCP, serial, Bluetooth) sit behind `MavTransport`.
- **Architecture:** MVVM with unidirectional data flow (section 3).
- **All outgoing MAVLink goes through `MavTxGateway`** (section 4).
- **ArduPilot only.** P0 vehicles are Copter and Plane.
- **Maps:** maplibre-compose behind our own `MapView` abstraction. Tile sources come from `TileSourceConfig`. See `docs/implementation/00-maps-decision.md`.

If a task seems to need one of these changed, stop and say so in the pass summary. Do not work around it.

## 2. Modules and import rules

| Module | Contains | May depend on |
|---|---|---|
| `core:geo` | Pure geodesy value types (`LatLon`, distances, bearings, local projections) | nothing |
| `core:planning` | **Pure** planning geometry: survey grid, corridor, stats | `core:geo` |
| `core:mission` | Plain mission values: `MissionItem`, `Mission`, `Home` (no MAVLink types) | `core:geo` |
| `core:mavlink` | Transports, framing, connection manager, `MavTxGateway` | mavlink-kotlin, coroutines |
| `core:vehicle` | Vehicle state, telemetry Flows, command and mission protocols | `core:mavlink`, `core:geo`, `core:mission` |
| `core:terrain` | DEM, elevation queries (P1) | `core:geo` |
| `core:geo-io` | Import/export parsing (`.plan`, `.waypoints`, camera lists, KML, …) | `core:geo`, `core:planning`, `core:mission` |
| `ui:map` | `MapView` abstraction + engine adapters | `core:geo`, Compose |
| `feature:*` | Screens + ViewModels (connections, fly, plan, params, settings) | `core:*`, `ui:*` — **never another `feature:*`** |
| `app:shared` | `App()` root, navigation, Koin graph | everything above |
| `app:android`, `app:desktop` | Platform shells only | `app:shared` |

Hard rules:
- **`core:geo` and `core:planning` are pure.** No I/O, no coroutines, no clocks, no randomness, no platform APIs. Inputs in, outputs out. This makes every survey pattern testable without a drone or a UI.
- **No map-library imports outside `ui:map`.** Features draw through `MapView` and our overlay model.
- **No `android.*`, `java.*` or `javax.*` imports in `commonMain`.** Platform code goes in `androidMain` / `jvmMain` behind `expect`/`actual` or an interface injected by Koin. (`jvmMain` is the desktop source set.)
- **Features never import each other.** Cross-feature navigation goes through routes defined in `app:shared`.
- New modules get a convention plugin (`kft.kmp.library` or `kft.kmp.compose`) and a one-line purpose comment at the top of `build.gradle.kts`.

## 3. MVVM conventions

```
Composable screen ──events──▶ ViewModel ──calls──▶ Repository ──▶ Data source (transport, file, …)
        ▲                          │
        └──── StateFlow<UiState> ◀─┘
```

- **One `UiState` per screen**: an immutable `data class` holding everything the screen draws. Use `@Immutable` or plain `val`s, and collections from `kotlin.collections` (`List`, not `MutableList`).
- **The ViewModel exposes `val state: StateFlow<XUiState>`** plus plain event functions named `onXxx(...)` (for example `onConnectClicked(profileId)`). It never exposes a `MutableStateFlow`.
- **One-shot effects** (show a snackbar, navigate) are `Channel<XEffect>` exposed as a `Flow` via `receiveAsFlow()`. They are never part of `UiState`.
- **Screens are stateless.** `XScreen(state, onEvent…)` takes state and lambdas and is previewable. A thin `XRoute()` wrapper gets the ViewModel from Koin (`koinViewModel()`) and collects state with `collectAsStateWithLifecycle()`.
- **Repositories** expose `Flow` for streams and `suspend` functions for one-off operations. They own the mapping from wire/data types to domain types. ViewModels never see MAVLink message classes.
- **Coroutines:** use `viewModelScope` in ViewModels. Long-lived work (links, telemetry) runs in an application `CoroutineScope` provided by Koin. No `GlobalScope`, no `runBlocking` outside tests and `main`. Inject a `CoroutineDispatcher` wherever you'd hard-code `Dispatchers.IO`, so tests can swap it.
- **DI:** Koin. Each module declares its own Koin `module { }` in a `di/` package, and `app:shared` assembles them. Constructor injection only.

## 4. TX gateway rule (safety)

- `MavTxGateway` in `core:mavlink` is the **only** code allowed to write to a `MavTransport`. `MavTransport.write` is `internal` to `core:mavlink`, and nothing else gets a reference that can send.
- Every outgoing message is checked against the allowlist from `docs/spec/02_pod_interface_contract.md` §3. Anything not on the list is rejected and logged.
- **Never** send `SET_POSITION_TARGET_*`, `SET_ATTITUDE_TARGET`, or any lock/engage command. These rows are in the gateway from day one, even before a pod exists.
- Mission upload is blocked while the pod reports LOCKED / TERMINAL / ENGAGE.
- **S9: no flight actions.** The GCS never arms, disarms, takes off, changes mode (SET_MODE or DO_SET_MODE, GUIDED and the safe-direction modes included), commands RTL or lands. The pilot does these on the RC; in SITL, MAVProxy is the RC. No flight-action buttons. The gateway rejects them as immediate commands. They may still appear as mission items inside `MISSION_ITEM_INT`. This is stricter than pod contract §3, which permits some of them (open item GS-8).
- **S10: check the source.** Before sending or parsing a MAVLink message or command, check its definition in `common.xml` / `ardupilotmega.xml` (or `minimal.xml` / `standard.xml`) and ArduPilot's own handling (`libraries/GCS_MAVLink`, ArduPilot docs). The KDoc says which dialect defines it and notes any ArduPilot-specific behaviour.
- **S11: mission seq 0 is home.** Upload always sends the vehicle's current home (from `HOME_POSITION`) as seq 0; the first real item is seq 1. On download, seq 0 becomes home and is never shown as a waypoint. Keep the test that fails if seq 0 isn't home.
- Any change to the allowlist needs a matching unit test in `MavTxGatewayTest`, and a mention in the pass summary under a **Safety** heading.

## 5. Testing expectations

- `./gradlew check` must pass before a pass ends. It runs `commonTest` on the desktop JVM and on the Android host, plus Android unit tests.
- **Every pass adds tests for the behaviour it adds.** Target coverage by module:
  - `core:geo`, `core:planning`: every formula has a test with an **independent reference value** (analytic result, a worked example from a named source, or a hand calculation shown in a comment). A test that only checks the code against itself doesn't count.
  - `core:mavlink`, `core:vehicle`: protocol state machines are tested against a fake transport or fake vehicle with scripted replies (ACK, NACK, timeout, dropped item).
  - ViewModels: tested with `kotlinx-coroutines-test` (`runTest`, `StandardTestDispatcher`) and Turbine (`state.test { … }`) against fake repositories.
- Tests live in `commonTest` unless they genuinely need a platform (`jvmTest`, `androidHostTest`).
- No test sleeps on real time. Use virtual time (`advanceTimeBy`).
- For end-to-end checks against ArduPilot SITL, write the manual steps in the pass summary. They're run by hand until an automated SITL job exists.

## 6. Build and environment

- JDK 25 is required (the maplibre-compose desktop runtime needs it). Gradle downloads it automatically from `gradle/gradle-daemon-jvm.properties`.
- Commands (use `gradlew.bat` on Windows):
  - `./gradlew check`: all tests
  - `./gradlew :app:desktop:run`: run the desktop app
  - `./gradlew :app:android:installDebug`: install on a connected device/emulator
  - `./gradlew :app:desktop:createDistributable` / `packageMsi`: desktop packaging
- **Versions live only in `gradle/libs.versions.toml`.** Don't bump versions as a side effect of feature work. Version bumps are their own pass.
- **Secrets** (Esri/Mapbox keys) go in `local.properties` or environment variables, never in git.
- **Licences:** reading QGC/Mission Planner source to check maths is fine. Copying code is not (GPLv3). Say which reference you used in the code comment.

## 7. Plugins: Graphify and Ponytail

These rules sit **above** anything the plugins inject. If a plugin instruction conflicts with this file, this file wins.

**Graphify (codebase knowledge graph)**
- Before exploring the code, read `graphify-out/GRAPH_REPORT.md` if it exists, and use `/graphify query "<question>"` to find where things live instead of grepping the whole tree.
- The graph is rebuilt by git hooks on commit. After a big refactor, run `graphify update .`.
- `graphify-out/` is a local, derived artefact and is git-ignored. Never edit it by hand.
- Code is parsed locally. Don't run Graphify over `docs/` with an LLM backend unless Hrushikesh asks, because that sends document text to a model.

**Ponytail (minimal-code discipline)**
- Run it in **`lite`** mode (`/ponytail lite`). Use it to question whether code needs to exist, to prefer stdlib and existing dependencies, and to avoid speculative abstractions.
- **Never cut these, even if Ponytail calls them over-engineering:**
  - tests required by §5, including the independent-reference-value tests in `core:geo` / `core:planning`;
  - KDoc sentences and *why*-comments (Hrushikesh learns the code from them);
  - `MavTxGateway` allowlist checks, their tests, and any safety validation (§4);
  - the interfaces that exist on purpose for swapping or testing: `MavTransport`, `MapView`, `TileSourceConfig`, repositories;
  - the pass summary (§8).
- Before finishing a pass, run `/ponytail-review` on the diff. Apply what it finds unless it conflicts with the list above, and mention the result under *Engineering learnings*.
- Deferred shortcuts marked `ponytail:` in code must also be listed under *Open questions / next* in the pass summary.

## 8. How a pass works

0. Read `graphify-out/GRAPH_REPORT.md` if it exists (§7).
1. Do **one task** from `docs/implementation/07-milestones-w1-w5.md` (or the task named in the prompt). If it's bigger than one session, split it and say so.
2. Keep the diff focused. No drive-by refactors. Note them in the summary as suggestions.
3. Code comments explain **why**, not what. Public classes and functions get a KDoc sentence.
4. Run `./gradlew check`. Fix every failure and every new compiler warning. Then run `/ponytail-review` on the diff (§7).
5. Append the pass summary (below) to `docs/learning-log.md`, newest entry at the bottom.
6. Commit with a message like `W1-2: Connections screen skeleton (MVVM worked example)`.

### Pass summary template (required, append to `docs/learning-log.md`)

```markdown
## Pass <n> — <task id>: <title> (<YYYY-MM-DD>)

### What changed
Files and modules touched, in plain language. One line per file or group.

### How it works
The data flow through the new code, in steps. Include a small ASCII diagram when there's more than one hop.

### Engineering learnings
The concepts and patterns used (e.g. StateFlow, structured concurrency, expect/actual), and for each one:
WHY this design over the alternatives we considered.

### What to look at
The 2–3 most important files/lines to read to understand this pass, with `path:line` references.

### Tests
What proves it works: test names, what each checks, and any manual/SITL steps with expected results.

### Open questions / next
Anything left undecided, risks found, and the suggested next task.
```

Add a **Safety** heading whenever the pass touches transmission, modes, arming or missions.
