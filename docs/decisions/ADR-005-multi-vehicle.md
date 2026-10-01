# ADR-005 — Multi-vehicle: several links, several vehicles per link

**Status:** Accepted for Level 1 (monitor and plan for many vehicles). Levels 2 and 3 are described, not built.
**Date:** 2026-10-01 · **Pass:** 29 (foundation), 30–32 build on it · **Spec:** S4, S9, S11, S12

## Context

Up to Pass 28 the GCS had one link and one vehicle: one `ConnectionManager`, one `MavTxGateway`, one
`VehicleRepository`, one mission and parameter repository. KFT wants to survey with several drones at once
(a few copters covering one job), each with its own pilot on its own RC.

Three levels of "multi-vehicle" exist, and they are very different in risk:

| Level | What the GCS does | In this batch |
|---|---|---|
| 1 | Shows every vehicle, uploads a mission to each, tracks each one's progress. Each pilot starts and stops their own drone on the RC | **Yes** (Passes 29–32) |
| 2 | The GCS starts, pauses or recalls missions (one button for "all start") | No. See "Level 2" below |
| 3 | Formation or swarm behaviour: the GCS steers vehicles relative to each other in flight | No. See "Level 3" below |

## Decision

### 1. A vehicle is (link, system id)

```kotlin
data class VehicleId(val link: Int, val systemId: UByte)
```

- `link` is a small number the app gives each link when it opens (1, 2, 3…), never reused while the app runs.
- `systemId` is the MAVLink system id the autopilot sends (ArduPilot `MAV_SYSID`, formerly `SYSID_THISMAV`).
- The same system id on two **different** links is two vehicles (two radios, both drones still on the factory id 1).
  That works, because every send goes to one link only.
- The same system id twice on the **same** link cannot work: MAVLink has nothing else to tell the two apart. See 4.

Alternatives considered:
- *System id alone.* Simpler, but two drones with the factory id on two radios would be merged into one, with the
  position jumping between them. That is exactly the mistake this ADR must make impossible.
- *The link's `LinkConfig` as the key.* It is unique, but long, and the tlog file name and the labels want a short number.

### 2. One of everything per vehicle

```
Links ──▶ ConnectionManager (one per open link: reconnect loop, heartbeats, the list of vehicles heard)
              │ frames, state, gateway
              ▼
Fleet ──▶ Vehicle (one per VehicleId, created the first time its heartbeat is heard)
              ├─ VehicleRepository   state, KFT login, startup requests      (the Pass 6/12 class, unchanged)
              ├─ MissionRepository   mission protocol                        (unchanged)
              ├─ ParamRepository     parameter protocol                      (unchanged)
              ├─ MissionSync entry   what this vehicle holds vs the plan
              └─ tlog file           every frame received from it
```

The per-vehicle classes are the single-vehicle classes from before. Each gets a **view** of its link that contains
only its own vehicle: frames filtered to its system id, a link state whose `vehicle` is this vehicle, and a sender
bound to it. So the code that was tested for one vehicle runs unchanged, once per vehicle, and its tests still
describe it.

Alternative considered: rewrite `VehicleRepository` and the protocols to hold a map of vehicles. More code changed,
every protocol test rewritten, and one shared object where a bug can leak one vehicle's data into another's.

### 3. Every send names its vehicle

`MavTxGateway` no longer has a plain `send`. The only ways to transmit are:

- `gateway.forVehicle(systemId)` → a `MavSender` bound to that vehicle. Before the allowlist it checks:
  1. the message is addressed (`target_system`) to **that** vehicle. A message addressed to anyone else, or to
     0 (broadcast), is refused and logged;
  2. the vehicle is not a duplicate system id (see 4);
  3. the allowlist rule for the message, using **that vehicle's** armed flag (and the pod state).
- the link's own GCS heartbeat (internal to `core:mavlink`), which has no target.

The gateway belongs to one link, so a message can only be written to the link its vehicle is on. Together: a command
built for vehicle 2 cannot reach vehicle 1, whether the mistake is a wrong target field, a wrong sender object or a
wrong link. `MavTxGatewayTest` has a test for each.

A message type with no `target_system` field (other than the heartbeat) cannot be sent through a vehicle sender at
all. That is deliberate: default-deny, like the allowlist itself.

### 4. Duplicate system id on one link is an error

Two autopilots with the same system id on one link look like one vehicle sending twice as often. The link counts
that system's heartbeats over the last 10 s. ArduPilot sends 1 per second, so 16 or more means two senders. The
vehicle is then marked `duplicateId`:

- the UI shows "Two vehicles are using system id N on this link. Give each its own MAV_SYSID.";
- the gateway refuses **every** message to that id (a command would be obeyed by both aircraft);
- its telemetry is not shown as a position (it would jump between the two).

It clears by itself once the rate is back to one sender for 10 s.

Limits, accepted: a long stall that delivers many buffered heartbeats at once can trip it for 10 s (it fails safe:
nothing is sent). On UDP the sender's address could tell the two apart, but then the same drones would still be
merged on a radio, so the rule is the same on every transport.

### 5. The selected vehicle

Fly's HUD, Plan's transfers and Params act on **one selected vehicle**. `Fleet.selected` holds it; the first vehicle
heard is selected automatically. The map shows all vehicles, each in its own colour with its label.

A transfer belongs to the vehicle that was selected when it started and stays with it if the selection changes.
Params keeps one parameter list per vehicle and never shows one vehicle's list while another is selected.

### 6. UDP: answer every sender

A UDP listen link used to answer "whoever sent last". With three vehicles behind three senders that would send a
command to the wrong one's address. It now sends each outgoing frame to every address it has heard from. MAVLink
frames carry their target, so the others ignore it (QGroundControl does the same).

### 7. One tlog per vehicle

`<data folder>/tlogs/<date>_<time>_link<L>_sys<N>.tlog`, MAVProxy format: 8-byte big-endian microseconds since 1970,
then the MAVLink v2 frame. Received frames only (see "Not done").

### 8. Jobs: several missions in one plan (Pass 30)

A job is the plan with a **mission number on every group** and a map "mission → vehicle". There is no Job class.

- Groups with the same number are one vehicle's mission, flown in list order and numbered from seq 1 on their own.
- A KML with several areas can be imported as "a mission per area"; an area can be split into parts, each a mission
  (`splitArea`: cuts along the flight lines, between two lines, parts balanced by line length; an optional altitude
  step per part separates drones in height as well).
- Upload sends each mission to its own vehicle (`Fleet.missionsOf`). A mission without a vehicle is left out and the
  preview says so. A vehicle has at most one mission.
- The preview checks every mission against the operator's limits (altitude, distance from home, one battery) and the
  vehicle's own fence. They are warnings: the pilot decides.

Alternative considered: mission objects that contain groups. It would have changed the editor's state, its undo
history and every reader of the group list, for no behaviour a number doesn't give.

A plan with one mission and no assignment is saved as file version 1, as before. A job is version 2.

## Level 2 — the GCS starts missions (not built)

What it would need:
- `MAV_CMD_MISSION_START` (or `DO_SET_MODE` AUTO) on the allowlist, per vehicle, plus arm and takeoff unless the pilot
  still does those. All of these are **S9 flight actions**; the gateway rejects them today in every state.
- A way to stop: pause (`DO_PAUSE_CONTINUE`) and recall (RTL). Also S9.
- A pre-start check per vehicle (mission on the vehicle = the plan, home known, battery, GPS, fence), and a clear
  rule for a partial start (2 of 3 started, the third refused).
- Link-loss rule: a vehicle the GCS started and can no longer hear must fall back on its own failsafes; the GCS
  cannot be the only one able to stop it.

Conflict with the fixed decisions:
- **S9** says the GCS never arms, takes off, changes mode, commands RTL or lands. Level 2 is exactly that. It cannot
  be added as a detail; S9 itself would have to be changed, in the spec, by KFT.
- **S4 / pod invariant 7**: with a pod on board, guidance belongs to the pod. A GCS that can switch a vehicle to AUTO
  or RTL can pull it out of the pod's control. The pod contract §3 permits some of this (GS-8); S9 forbids it.
- One operator starting three aircraft removes "one pilot, one RC, one aircraft", which today is the safety case.

If KFT wants Level 2: reopen S9 with a narrow exception (for example `MISSION_START` only, only while the vehicle is
armed and already airborne in a pilot-chosen hold mode, never with a pod reporting anything but IDLE), write the
gateway rows and tests first, and keep stop/recall on the RC.

## Level 3 — formation / swarm (not built)

What it would need:
- Position targets sent in flight (`SET_POSITION_TARGET_GLOBAL_INT`, `DO_REPOSITION`) or ArduPilot's own Follow
  mode (`FOLL_*` parameters, each follower listening to the leader's `GLOBAL_POSITION_INT`).
- Vehicle-to-vehicle separation that keeps working when the GCS link drops.
- A shared time base and a much higher telemetry rate than 4 Hz.

Conflict with the fixed decisions:
- **S4** and CLAUDE.md §4: `SET_POSITION_TARGET_*` and `SET_ATTITUDE_TARGET` are **never** sent, in any state. They
  are the pod's guidance channel. Formation flying from the GCS needs exactly these messages.
- **S9**: GUIDED mode and every in-flight redirection are flight actions.
- The only route that doesn't break S4 is ArduPilot's Follow mode set up by **parameters on the ground** (PARAM_SET
  while disarmed is allowed), with the pilot choosing the mode on the RC. The GCS would then configure a formation,
  not fly it. That is a parameter feature, not a guidance feature, and would be its own ADR.

Separation in this batch is by planning only: Pass 30 splits an area into parts and can step the altitude per part.

## Consequences

- `LinkState.Connected` gains `vehicles` (all heard on the link). `vehicle` stays: on a link's own state it is the
  first of them, on a vehicle's view it is that vehicle.
- Koin: `Links` and `Fleet` replace the single `ConnectionManager` and `VehicleRepository` bindings. Features get the
  selected vehicle's state and repositories, so their ViewModels changed little.
- `core:vehicle` sees a link through the small `MavLink` interface (id, state, frames, `senderFor(systemId)`), which
  `ConnectionManager` implements, so the fleet is tested with scripted links and fake flight controllers.
- The Links screen shows one card per open link, each with its own Disconnect; Connect on a profile opens one more
  link instead of replacing the current one.
- Parameters are downloaded once per vehicle, when it is first selected and ready, not for every vehicle at connect
  (three full parameter downloads at once would flood a telemetry radio).
- SITL: `start-sitl.ps1 -Count N` starts N copters with system ids 1..N.

## Not done (and when to do it)

- **Sent frames in the tlog.** The library doesn't hand back the bytes it sends. Add when a log must show what the
  GCS commanded (Level 2 would need it).
- **Per-vehicle pod status.** There is one pod status for the app ("no pod") until the pod link exists. When it
  does, the status has to become per vehicle, like the armed flag: a locked pod on drone 2 must not block an upload
  to drone 1.
- **Parameters of a vehicle that isn't selected** are not refreshed when it reconnects; they are read again the next
  time it becomes ready while selected.
- **Old tlogs are never deleted.** One file per vehicle per link session; clear the folder by hand.
- **Forgetting a vehicle.** A vehicle stays in the list (shown as lost) until its link is disconnected.
