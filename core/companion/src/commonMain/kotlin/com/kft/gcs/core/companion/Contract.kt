package com.kft.gcs.core.companion

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The VisionNav companion contracts, schema_version 0.2 for odometry and map_fix, 0.3/0.4/0.5 for health (visionnav repo, contracts/schemas/<name>.schema.json, approved
 * 2026-10-17). Field names are the wire names, so each class reads side by side with its schema. Only the three
 * records the panel shows are here: local_odometry, health and map_fix.
 *
 * The JSON Schema rules are re-checked by hand in [validate]: kotlinx.serialization alone catches missing fields,
 * unknown fields (`additionalProperties: false`), wrong types and unknown enum values, but not the conditional rules
 * ("TRACKING must have a finite pose") or the consumer-only checks listed in contracts/README.md.
 */

/**
 * The schema versions this GCS reads, per record type (they moved separately: contracts/README.md "Changes").
 * Anything else is rejected, never guessed at. Health 0.2 is dropped on purpose (WE-7): it has no measurement time,
 * so its age could only come from arrival, which a delayed or replayed message fakes. 0.3 and 0.4 differ only by
 * the optional `signing` field; 0.5 (visionnav NH-H3) adds `auth` and `fc_acceptance`. 0.3/0.4 stay readable so a Pi
 * still running the older bridge is shown rather than dropped.
 */
private val READ_VERSIONS = mapOf(
    "local_odometry" to setOf("0.2"),
    "health" to setOf("0.3", "0.4", "0.5"),
    "map_fix" to setOf("0.2"),
)

/** Where a record came from. Shown on the panel, so synthetic or replayed data never looks live. */
@Serializable
enum class Source {
    @SerialName("live") LIVE,
    @SerialName("replay") REPLAY,
    @SerialName("synthetic") SYNTHETIC,
}

@Serializable
enum class VioState { INITIALIZING, TRACKING, DEGRADED, LOST }

@Serializable
enum class HealthStatus { STARTING, OK, DEGRADED, FAILED }

/** One contract record, already checked. */
sealed interface CompanionRecord {
    val source: Source
    val runId: String
}

/**
 * Continuous local VIO estimate (local_odometry.schema.json). Times are the Pi's CLOCK_MONOTONIC in ns: comparable
 * with each other, never with the GCS clock. Unknown values are null (the contract's "NaN on the wire").
 */
@Serializable
data class LocalOdometry(
    @SerialName("schema_version") val schemaVersion: String,
    override val source: Source,
    @SerialName("run_id") override val runId: String,
    @SerialName("measurement_time_pi_ns") val measurementTimePiNs: Long,
    @SerialName("publish_time_pi_ns") val publishTimePiNs: Long,
    @SerialName("frame_id") val frameId: String,
    @SerialName("child_frame_id") val childFrameId: String,
    /** Body origin in odom, metres; odom x/y/z (frames.md). */
    @SerialName("position_m") val positionM: List<Double?>,
    @SerialName("orientation_xyzw") val orientationXyzw: List<Double?>,
    @SerialName("velocity_m_s") val velocityMS: List<Double?>,
    @SerialName("pose_covariance_6x6") val poseCovariance6x6: List<Double?>,
    @SerialName("pose_covariance_known") val poseCovarianceKnown: Boolean,
    val state: VioState,
    /** Increments on every odom discontinuity: poses are never joined or differenced across a change. */
    @SerialName("reset_counter") val resetCounter: Int,
    @SerialName("last_visual_update_pi_ns") val lastVisualUpdatePiNs: Long?,
) : CompanionRecord

/** Signing state a component reports (health 0.4, optional). ON means configured locally, not accepted by the FC. */
@Serializable
enum class SigningState { OFF, ON, KEY_MISSING }

/**
 * The KFT login (handshake) result as the Pi bridge infers it (health 0.5, fc_bridge only). PENDING = not tried yet;
 * NOT_CONFIGURED = no handshake set up (SITL); UNSUPPORTED = the FC has no KFT gate (stock ArduPilot). Inferred from
 * the FC's replies: it does not prove the FC uses the odometry.
 */
@Serializable
enum class AuthState { PENDING, NOT_CONFIGURED, AUTHENTICATED, UNSUPPORTED, DENIED, NO_ACK, FAILED }

/**
 * Whether the FC's own position follows the odometry the bridge sends (health 0.5, fc_bridge only). FOLLOWING needs a
 * fresh FC position close to what was sent, so it is an inference: a stationary FC cannot prove it fuses the odometry.
 * UNKNOWN is never "fine": it means the bridge has no evidence either way.
 */
@Serializable
enum class FcAcceptance { UNKNOWN, FOLLOWING, NOT_FOLLOWING }

/** Login results with which the bridge sends odometry at all; the only ones a fc_bridge OK record may carry. */
private val SENDING_AUTH = setOf(AuthState.AUTHENTICATED, AuthState.UNSUPPORTED, AuthState.NOT_CONFIGURED)

/**
 * Periodic status of one Pi component (health.schema.json, 0.3/0.4/0.5). [measurementTimePiNs] is the Pi CLOCK_MONOTONIC
 * time the status was evaluated; the panel judges staleness from it, never from arrival.
 */
@Serializable
data class Health(
    @SerialName("schema_version") val schemaVersion: String,
    override val source: Source,
    @SerialName("run_id") override val runId: String,
    val component: String,
    val status: HealthStatus,
    val reason: String?,
    @SerialName("last_input_age_ms") val lastInputAgeMs: Double?,
    @SerialName("cpu_pct") val cpuPct: Double,
    @SerialName("queue_len") val queueLen: Int,
    @SerialName("measurement_time_pi_ns") val measurementTimePiNs: Long,
    val signing: SigningState? = null,
    /** Health 0.5, required for component fc_bridge, absent for the others. */
    val auth: AuthState? = null,
    @SerialName("fc_acceptance") val fcAcceptance: FcAcceptance? = null,
) : CompanionRecord

/** One map-matching attempt, accepted or not (map_fix.schema.json). It describes the query image's instant, not now. */
@Serializable
data class MapFix(
    @SerialName("schema_version") val schemaVersion: String,
    override val source: Source,
    @SerialName("run_id") override val runId: String,
    @SerialName("query_exposure_time_pi_ns") val queryExposureTimePiNs: Long,
    @SerialName("lat_deg") val latDeg: Double?,
    @SerialName("lon_deg") val lonDeg: Double?,
    @SerialName("yaw_rad") val yawRad: Double?,
    @SerialName("horizontal_sigma_m") val horizontalSigmaM: Double?,
    val accepted: Boolean,
    @SerialName("reject_reason") val rejectReason: String?,
    @SerialName("map_id") val mapId: String,
) : CompanionRecord

/**
 * What arrives on the WebSocket: `{"type": "<schema name>", "msg": {...}}`, the envelope the P4 synthetic feed
 * introduced (visionnav LEARNINGS P4) because the schemas forbid extra fields. `scenario` is the feed's label.
 */
@Serializable
private data class Envelope(val type: String, val msg: JsonObject, val scenario: String? = null)

/** The result of reading one WebSocket text message. */
sealed interface Parsed {
    data class Ok(val record: CompanionRecord) : Parsed
    /** Not shown as data. [reason] is for the operator ("schema_version 0.1, expected 0.2"). */
    data class Rejected(val reason: String) : Parsed
}

// Strict on purpose (all defaults): unknown keys, NaN/Infinity and quoted numbers are errors, not guesses.
private val json = Json

/** Reads one message: envelope, then the record by its type, then the rules JSON decoding can't check. */
fun parseRecord(text: String): Parsed {
    val record = try {
        val envelope = json.decodeFromString<Envelope>(text)
        // Checked before decoding, so a producer on another version gets this reason instead of a field error.
        val version = (envelope.msg["schema_version"] as? JsonPrimitive)?.content
        val readable = READ_VERSIONS[envelope.type] ?: return Parsed.Rejected("unknown type ${envelope.type}")
        if (version !in readable) return Parsed.Rejected("schema_version $version, expected ${readable.joinToString(" or ")}")
        val serializer: KSerializer<out CompanionRecord> = when (envelope.type) {
            "local_odometry" -> LocalOdometry.serializer()
            "health" -> Health.serializer()
            "map_fix" -> MapFix.serializer()
            else -> return Parsed.Rejected("unknown type ${envelope.type}")
        }
        json.decodeFromJsonElement(serializer, envelope.msg)
    } catch (e: IllegalArgumentException) { // SerializationException is one; so are some type mismatches
        return Parsed.Rejected("malformed: ${e.message?.lineSequence()?.first()}")
    }
    return validate(record)?.let { Parsed.Rejected(it) } ?: Parsed.Ok(record)
}

private val RUN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$") // the schemas' run_id pattern
private val COMPONENT = Regex("^[a-z][a-z0-9_]*$")

/** The schema's conditional rules plus contracts/README.md "consumers must check". Returns the first problem, or null. */
internal fun validate(r: CompanionRecord): String? {
    if (!RUN_ID.matches(r.runId)) return "bad run_id"
    return when (r) {
        is LocalOdometry -> when {
            r.frameId != "odom" || r.childFrameId != "base_link_frd" -> "frames ${r.frameId}<-${r.childFrameId}, expected odom<-base_link_frd"
            r.positionM.size != 3 || r.orientationXyzw.size != 4 || r.velocityMS.size != 3 || r.poseCovariance6x6.size != 36 -> "wrong array length"
            r.measurementTimePiNs < 0 || r.resetCounter < 0 -> "negative time or reset_counter"
            r.publishTimePiNs < r.measurementTimePiNs -> "publish_time before measurement_time"
            r.state == VioState.TRACKING || r.state == VioState.DEGRADED -> when {
                (r.positionM + r.orientationXyzw + r.velocityMS).any { it == null } -> "${r.state} without a full pose"
                r.lastVisualUpdatePiNs == null -> "${r.state} without last_visual_update"
                // ponytail: 1e-3 is an assumed tolerance for a float32 quaternion; the contract gives none.
                abs(sqrt(r.orientationXyzw.sumOf { it!! * it }) - 1.0) > 1e-3 -> "quaternion not unit length"
                else -> covarianceProblem(r)
            }
            else -> covarianceProblem(r)
        }
        is Health -> when {
            !COMPONENT.matches(r.component) -> "bad component name"
            r.status != HealthStatus.OK && r.reason.isNullOrEmpty() -> "health ${r.status} without a reason"
            r.cpuPct < 0 || r.queueLen < 0 || (r.lastInputAgeMs ?: 0.0) < 0 || r.measurementTimePiNs < 0 -> "negative health value"
            r.schemaVersion != "0.5" -> if (r.auth != null || r.fcAcceptance != null) "auth/fc_acceptance need health 0.5" else null
            r.component != "fc_bridge" -> null
            // The 0.5 schema's fc_bridge rules: unknown FC acceptance must never arrive as OK.
            r.auth == null || r.fcAcceptance == null -> "fc_bridge health 0.5 without auth/fc_acceptance"
            r.status == HealthStatus.OK && r.fcAcceptance != FcAcceptance.FOLLOWING -> "fc_bridge OK while FC ${r.fcAcceptance}"
            r.status == HealthStatus.OK && r.auth !in SENDING_AUTH -> "fc_bridge OK with auth ${r.auth}"
            else -> null
        }
        is MapFix -> when {
            r.queryExposureTimePiNs < 0 -> "negative query time"
            r.mapId.isEmpty() -> "empty map_id"
            r.accepted && (r.latDeg == null || r.lonDeg == null || r.yawRad == null || r.horizontalSigmaM == null) -> "accepted fix without a position"
            r.accepted && r.rejectReason != null -> "accepted fix with a reject_reason"
            !r.accepted && r.rejectReason.isNullOrEmpty() -> "rejected fix without a reason"
            r.latDeg != null && abs(r.latDeg) > 90 || r.lonDeg != null && abs(r.lonDeg) > 180 -> "lat/lon out of range"
            (r.horizontalSigmaM ?: 1.0) <= 0 -> "sigma not positive"
            else -> null
        }
    }
}

/** Known covariance: every entry a number and the variances not negative. Unknown: every entry null, never zeros. */
private fun covarianceProblem(r: LocalOdometry): String? = when {
    r.poseCovarianceKnown && r.poseCovariance6x6.any { it == null } -> "known covariance with null entries"
    r.poseCovarianceKnown && (0 until 6).any { r.poseCovariance6x6[it * 7]!! < 0 } -> "negative variance"
    !r.poseCovarianceKnown && r.poseCovariance6x6.any { it != null } -> "unknown covariance with values"
    else -> null
}
