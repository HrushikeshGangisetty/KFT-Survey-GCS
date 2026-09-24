# Pod ↔ GCS Interface Contract

**Purpose:** The rules the KFT GCS must honour so that the counter-UAV / surveillance pod can plug into it later without breaking the pod's safety case. The GCS team doesn't need the full pod PRD, only this.
**Source of truth:** `counter-uav` repo — `cuav_pod_prd.docx` v1.1 §1.3, §2.4, §5.5 and `schemas/`. If this doc and that repo disagree, the repo wins.
**Date:** 2026-09-24

---

## 1. Two independent links (do not merge them)

| Link | Carries | Direction |
|---|---|---|
| **FC telemetry radio** (MAVLink) | Attitude, position, mode, armed state, RC, missions | GCS ↔ FC, through the TX gateway (§3) |
| **Pod WiFi** | Pod telemetry (WebSocket JSON, ~10 Hz) + annotated video (RTSP H.264) | Pod → GCS, plus a narrow command surface (§4) |

FC telemetry must **never** be relayed through the pod. If the pod browns out, the operator must still see the aircraft. The GCS keeps both connections as separate objects with separate health indicators.

## 2. Pod hard invariants that constrain the GCS

1. The FC is the sole authority. The pod advises, it never commands.
2. Pod commands are honoured only in GUIDED. Pilot RC input revokes pod influence instantly.
3. Losing the pod never loses the vehicle.
4. Engagement requires **both** AI-enable RC high **and** target-lock RC triggered. **Never over the GCS.**
5. Mission mode (surveillance / counter-UAV) is set before takeoff, latched at arming, and never changed in flight.
6. Break-off radius and velocity envelopes are boot-loaded config and not editable from the GCS.
7. Exactly one software module commands the FC's guidance input: the pod's `pod_mavlink`.

## 3. GCS → FC transmit rules (TX gateway)

All outgoing MAVLink goes through **one** class, `MavTxGateway`, with an explicit allowlist, unit-tested. No other code may write to a transport.

| Category | Examples | Rule |
|---|---|---|
| Always allowed | Heartbeat, param read, mission download, request data streams | ✅ |
| Operator commands | Arm/disarm, takeoff, AUTO, mission upload, param set | ✅ When no pod is active. Mission upload and changes are **blocked while the pod reports LOCKED / TERMINAL / ENGAGE** |
| Safe-direction modes | RTL, LOITER, LAND, BRAKE | ✅ Always. Leaving GUIDED is a revocation, which is always safe |
| Enter GUIDED | `SET_MODE` → GUIDED | ❌ While pod AI-enable is high. Only the pilot hands the pod authority, via RC |
| Guidance setpoints | `SET_POSITION_TARGET_*`, `SET_ATTITUDE_TARGET` | ❌ **Never** |
| Engagement | Anything enabling lock/engage | ❌ **Never** — RC only |

Until a pod is connected, only the first three rows matter. Build the gateway with the full table from day one anyway, so the pod integration is a data change and not a redesign.

## 4. GCS → Pod command surface (WebSocket)

Only these events exist: `lock`, `unlock`, and pre-takeoff `mode_set`.
The GCS must not be able to change break-off radius, velocity envelopes or mission mode in flight, or bypass the pod's safety governor.
Open question on the pod side: whether mission mode comes from RC only (recommended) or also from the GCS `mode_set`. Until decided, the GCS displays mode and only sends `mode_set` while disarmed.

## 5. Pod telemetry schema

- Defined in `counter-uav/schemas/telemetry.schema.json` (the Python `TelemetryFrame` dataclass).
- The GCS mirrors it with `kotlinx.serialization`. JSON keys equal the Python attribute names.
- Sync method: git submodule or a vendored copy with a CI check that fails on any diff. **Any schema change touches both repos in the same change set.**
- Timestamps are integer nanoseconds, `CLOCK_MONOTONIC`, stamped at capture. Every message carries a capture timestamp and a frame sequence number. Sequence gaps are normal (the pod drops frames rather than queueing them).

## 6. Video

- RTSP H.264 from the pod, 720p default, capped at 30 fps.
- Glass-to-glass target is ~300 ms, tracked **separately** from the pod's 200 ms control budget.
- Android: Media3/ExoPlayer, with buffering tuned down. Desktop: gst-java or VLCJ (open). Behind an `expect`/`actual` boundary.
- The video pane is the largest unknown in the GCS. Prototype it before building the pod panel UI around it.

## 7. When this matters

Not for the survey prototype. It matters from the moment the GCS gains a pod panel. The one thing to build now is the TX gateway in §3.
