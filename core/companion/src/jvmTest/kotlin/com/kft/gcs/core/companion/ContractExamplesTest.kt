package com.kft.gcs.core.companion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The visionnav contract's own examples (copied in resources/contract-0.2, see its README): each valid one must parse,
 * each invalid one must be rejected. jvmTest because it reads files; the working directory is the module folder.
 */
class ContractExamplesTest {
    private val root = File("src/jvmTest/resources/contract-0.2")

    @Test
    fun everyExampleIsReadAsTheContractSays() {
        val files = listOf("local_odometry", "health", "map_fix").flatMap { type -> File(root, type).listFiles()!!.map { type to it } }
        assertEquals(42, files.size, "12 odometry + 23 health (v0.5) + 7 map_fix examples")
        for ((type, file) in files) {
            val parsed = parseRecord("""{"type":"$type","msg":${file.readText()}}""")
            if (file.name.startsWith("valid")) assertIs<Parsed.Ok>(parsed, "$type/${file.name}: $parsed")
            else assertIs<Parsed.Rejected>(parsed, "$type/${file.name} must be rejected")
        }
    }

    /** The rules that JSON decoding alone can't catch are rejected with their own reason, not a generic one. */
    @Test
    fun conditionalRulesGiveTheirReason() {
        fun reason(type: String, name: String) =
            (parseRecord("""{"type":"$type","msg":${File(root, "$type/$name").readText()}}""") as Parsed.Rejected).reason
        assertEquals("TRACKING without a full pose", reason("local_odometry", "invalid_nan_position_tracking.json"))
        assertEquals("negative variance", reason("local_odometry", "invalid_negative_variance.json"))
        assertEquals("unknown covariance with values", reason("local_odometry", "invalid_unknown_covariance_as_zero.json"))
        assertEquals("health FAILED without a reason", reason("health", "invalid_failed_without_reason.json"))
        assertTrue(reason("health", "invalid_missing_measurement_time.json").startsWith("malformed"), "measurement_time is required")
        assertEquals("fc_bridge OK while FC UNKNOWN", reason("health", "invalid_fc_bridge_ok_acceptance_unknown.json"))
        assertEquals("fc_bridge OK while FC NOT_FOLLOWING", reason("health", "invalid_fc_bridge_ok_not_following.json"))
        assertEquals("fc_bridge OK with auth DENIED", reason("health", "invalid_fc_bridge_ok_auth_denied.json"))
        assertEquals("fc_bridge OK with auth PENDING", reason("health", "invalid_fc_bridge_ok_auth_pending.json"))
        assertEquals("fc_bridge health 0.5 without auth/fc_acceptance", reason("health", "invalid_fc_bridge_missing_auth.json"))
        assertTrue(reason("health", "invalid_fc_bridge_bad_fc_acceptance_value.json").startsWith("malformed"), "unknown enum value")
        assertEquals("rejected fix without a reason", reason("map_fix", "invalid_rejected_without_reason.json"))
        assertTrue(reason("local_odometry", "invalid_wrong_unit_name.json").startsWith("malformed"), "an unknown field is an error")
    }
}
