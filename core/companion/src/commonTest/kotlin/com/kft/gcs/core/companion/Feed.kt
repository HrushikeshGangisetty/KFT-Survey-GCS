package com.kft.gcs.core.companion

import kotlin.math.roundToLong

/**
 * Messages shaped like the visionnav P4 synthetic feed (tools/synthetic_feed/feed.py): the Pi clock starts at
 * [T0_NS], odometry is published 5 ms after its tick, and a map fix describes an image 0.5 s older than its tick.
 * Times are in seconds since the scenario started, as the feed's README describes each scenario.
 */
internal object Feed {
    const val T0_NS = 1_000_000_000_000L
    private const val PUBLISH_LATENCY_NS = 5_000_000L

    fun ns(seconds: Double) = T0_NS + (seconds * 1e9).roundToLong()

    fun odometry(
        tickS: Double,
        measurementS: Double = tickS,
        x: Double = 0.0,
        state: String = "TRACKING",
        reset: Int = 0,
        runId: String = "synthetic-test-seed0",
        source: String = "synthetic",
        version: String = "0.2",
    ): String {
        val known = state == "TRACKING" || state == "DEGRADED"
        fun vec(vararg v: Double) = if (known) v.joinToString(",", "[", "]") else List(v.size) { "null" }.joinToString(",", "[", "]")
        val cov = if (known) List(36) { if (it % 7 == 0) "0.01" else "0.0" } else List(36) { "null" }
        return """{"type":"local_odometry","scenario":"test","msg":{"schema_version":"$version","source":"$source","run_id":"$runId",
            "measurement_time_pi_ns":${ns(measurementS)},"publish_time_pi_ns":${ns(tickS) + PUBLISH_LATENCY_NS},
            "frame_id":"odom","child_frame_id":"base_link_frd","position_m":${vec(x, 0.0, 0.0)},
            "orientation_xyzw":${vec(0.0, 0.0, 0.0, 1.0)},"velocity_m_s":${vec(1.0, 0.0, 0.0)},
            "pose_covariance_6x6":${cov.joinToString(",", "[", "]")},"pose_covariance_known":$known,"state":"$state",
            "reset_counter":$reset,"last_visual_update_pi_ns":${ns(measurementS)}}}"""
    }

    /** [measurementS] is the Pi time the status was evaluated (default: the tick); [version] 0.3 or 0.4 are readable. */
    fun health(
        status: String = "OK",
        reason: String? = null,
        runId: String = "synthetic-test-seed0",
        tickS: Double = 0.0,
        measurementS: Double = tickS,
        version: String = "0.4",
        extra: String = "",
    ) =
        """{"type":"health","msg":{"schema_version":"$version","source":"synthetic","run_id":"$runId","component":"vio",
            "status":"$status","reason":${reason?.let { "\"$it\"" }},"last_input_age_ms":50.0,"cpu_pct":40.0,"queue_len":0,
            "measurement_time_pi_ns":${ns(measurementS)}$extra}}"""

    fun mapFix(tickS: Double, lat: Double, lon: Double, accepted: Boolean = true, reason: String? = null) =
        """{"type":"map_fix","msg":{"schema_version":"0.2","source":"synthetic","run_id":"synthetic-test-seed0",
            "query_exposure_time_pi_ns":${ns(tickS - 0.5)},"lat_deg":$lat,"lon_deg":$lon,"yaw_rad":0.0,
            "horizontal_sigma_m":3.0,"accepted":$accepted,"reject_reason":${reason?.let { "\"$it\"" }},"map_id":"synthetic_map"}}"""
}
