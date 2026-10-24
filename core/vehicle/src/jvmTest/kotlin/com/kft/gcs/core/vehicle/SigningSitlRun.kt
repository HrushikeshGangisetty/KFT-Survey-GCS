package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.definitions.common.ParamRequestRead
import com.divpundir.mavlink.definitions.common.ParamValue
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.keys.BundleImport
import com.kft.gcs.core.keys.FileByteStore
import com.kft.gcs.core.keys.JvmVaultCrypto
import com.kft.gcs.core.keys.TimestampFile
import com.kft.gcs.core.keys.Vault
import com.kft.gcs.core.keys.VaultKeyring
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.MavLink
import com.kft.gcs.core.mavlink.MavTxGateway
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.T12Link
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.mavlink.signing.label
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Signing S9 end-to-end run (PRD "Testing and acceptance"), against `tools/sitl/s9_signing_sitl.py up` in WSL: three
 * KFT-fork SITLs behind the S7 mavlink-router, sysid 1 keyed with drone A, sysid 2 with drone B, sysid 3 unsigned.
 * Keys and bundles come from `tools/sitl/s9_make_keys.py` (the S6 provisioning code).
 *
 * Everything on the GCS side is the production code: a real [Vault] (Argon2id, AES-GCM file) filled by [BundleImport],
 * [VaultKeyring], [Links] with the real UDP transport and gateway, and [Fleet] for the KFT login, parameters and missions.
 * "GCS 2" is a second, fully separate set of those objects with its own vault file (so its own installation link_id),
 * timestamp file and UDP socket; nothing is shared with GCS 1 but the JVM.
 *
 * Each PRD row runs on its own and is reported PASS/FAIL with its evidence, so one failure doesn't hide the rest; the
 * test fails at the end if any row failed. The table is also written to `<work>/results.txt` for S9-results.md.
 *
 * Skipped unless `KFT_S9_RUN=1`. `KFT_S9_WORK` is the harness work folder (default `build/s9-sitl`), holding
 * keys.json, the bundles, ready.json (the WSL address) and taps.log (every frame that reached each SITL).
 */
class SigningSitlRun {
    private val rows = mutableListOf<Triple<String, Boolean, String>>()

    private suspend fun row(id: String, block: suspend () -> String) {
        val result = runCatching { block() }
        rows += Triple(id, result.isSuccess, result.getOrElse { "${it::class.simpleName}: ${it.message}" })
        println("$id ${if (result.isSuccess) "PASS" else "FAIL"}: ${rows.last().third}")
    }

    @Test
    fun s9EndToEnd() {
        if (System.getenv("KFT_S9_RUN") != "1") return println("KFT_S9_RUN not set: skipped")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/sitl").isDirectory }
        val work = File(System.getenv("KFT_S9_WORK") ?: File(root, "build/s9-sitl").path)
        val keys = Json.parseToJsonElement(File(work, "keys.json").readText()).jsonObject
        val ready = Json.parseToJsonElement(File(work, "ready.json").readText()).jsonObject
        val host = ready["host"]!!.jsonPrimitive.content
        val (uidA, uidB) = keys["drones"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content }
        val pin = keys["pin"]!!.jsonPrimitive.content
        val passphrase = keys["bundle_passphrase"]!!.jsonPrimitive.content
        val loginKey = parseKftKey(KFT_APP_SECRET_HEX) ?: ByteArray(Kft.KEY_BYTES)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val crypto = JvmVaultCrypto()

        /** A fresh installation in `<work>/<name>`: vault created with [pin], [bundle] imported, then locked and unlocked. */
        suspend fun installation(name: String, bundle: String, avoidLinkId: UByte? = null): Triple<Vault, TimestampFile, VaultKeyring> {
            val dir = File(work, name).apply { deleteRecursively(); mkdirs() }
            var vault: Vault
            do { // link_id is random 1..254 at create; GCS 2 must differ from GCS 1 for row T10 to mean anything
                File(dir, "signing.vnks").delete()
                vault = Vault(FileByteStore(File(dir, "signing.vnks")), crypto)
                vault.create(pin)
            } while (vault.linkId == avoidLinkId)
            BundleImport(vault, crypto).import(File(work, bundle).readBytes(), passphrase)
            vault.lock()
            vault.unlock(pin) // the "unlock vault" step: keys come back from the file, not from memory
            val timestamps = TimestampFile(FileByteStore(File(dir, "signing_ts.json")), System::currentTimeMillis)
            return Triple(vault, timestamps, VaultKeyring(vault, timestamps))
        }

        fun links(keyring: VaultKeyring) = SerialPorts().let { Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), it, T12Link(scope, Dispatchers.IO, it), keyring) }

        /** Asks [systemId] for SYSID_THISMAV on [link]; the answer proves the FC accepted our signature and timestamp. */
        suspend fun ask(link: MavLink, systemId: UByte): Any {
            val answer = scope.async { withTimeoutOrNull(10.seconds) { link.frames.first { it.systemId == systemId && (it.message as? ParamValue)?.paramId == "SYSID_THISMAV" } } }
            delay(200) // let the collector subscribe before the request goes out
            val sent = link.senderFor(systemId).send(ParamRequestRead(targetSystem = systemId, targetComponent = 1u, paramId = "SYSID_THISMAV", paramIndex = -1))
            if (sent != TxResult.Sent) { answer.cancel(); return sent }
            return (answer.await()?.message as? ParamValue)?.paramValue ?: "no answer"
        }

        fun connected(link: MavLink) = link.state.value as? LinkState.Connected

        /** GCS frames (sysid 255) of [msgId] that reached SITL [tap] since [sinceS], as "sig/link" strings. */
        fun tapped(tap: Int, msgId: Int, sinceS: Double): List<Pair<String, Long>> = File(work, "taps.log").readLines().mapNotNull { line ->
            val f = line.split(" ").associate { it.substringBefore("=") to it.substringAfter("=") }
            if (f["tap"] != "$tap" || f["sys"] != "255" || f["msg"] != "$msgId" || f["t"]!!.toDouble() < sinceS) null
            else "${f["sig"]}/${f["link"]}" to (f["ts"]!!.toLongOrNull() ?: 0L)
        }

        fun nowS() = System.currentTimeMillis() / 1000.0

        try {
            runBlocking {
                val (vault1, timestamps1, keyring1) = installation("gcs1", "bundle_ab.vnkey")
                val l1 = vault1.linkId
                println("GCS 1: vault unlocked, link_id $l1, keys ${vault1.list().map { it.droneId }}")
                val links1 = links(keyring1)
                val fleet1 = Fleet(scope, links1.open, loginKey, MissionSync())
                links1.connect(LinkConfig.UdpClient(host, 14550))
                val link1 = links1.open.value.single()
                withTimeout(120.seconds) {
                    fleet1.vehicles.first { all -> all.size == 3 && all.all { it.state.home != null && it.state.firmwareVersion != null && it.state.login?.allowsTraffic == true } }
                }
                fun v1(systemId: Int) = fleet1.vehicles.value.single { it.id.systemId.toInt() == systemId }

                row("T9 binding") {
                    val got = (1..3).associateWith { v1(it).state.signing }
                    assertEquals(mapOf(1 to SigningStatus.Signed(uidA), 2 to SigningStatus.Signed(uidB), 3 to SigningStatus.Unsigned), got)
                    got.entries.joinToString { (id, s) -> "sysid $id: ${s.label} ($s)" }
                }

                for (sysid in 1..3) row("T1/T8 sysid $sysid") {
                    val v = v1(sysid)
                    fleet1.select(v.id)
                    val params = fleet1.params.downloadAll().getOrThrow()
                    val home = v.state.home!!.position
                    val plan = listOf(MissionItem(MissionCommand.TAKEOFF, altitudeM = 20.0)) +
                        (1..sysid + 1).map { MissionItem(MissionCommand.WAYPOINT, LatLon(home.latitude + 0.001 * it, home.longitude), 30.0 + sysid) } +
                        MissionItem(MissionCommand.RETURN_TO_LAUNCH)
                    fleet1.missions.upload(plan).getOrThrow()
                    val back = fleet1.missions.download().getOrThrow()
                    assertEquals(plan.map { it.position?.latE7 }, back.items.map { it.position?.latE7 }, "mission read back")
                    assertEquals(home, back.home!!.position, "seq 0 is home (S11)")
                    "badge '${v.state.signing.label}', ${v.state.login?.label}, ${params.size} params, mission ${plan.size} items up and down, SIGFAIL ${v.state.fcSignatureFailures}"
                }

                row("T9 heartbeat fan-out") {
                    val since = nowS()
                    delay(3_000)
                    val perTap = (0..2).associateWith { tap -> tapped(tap, 0, since).map { it.first }.toSet() }
                    val expected = setOf("A/$l1", "B/$l1", "unsigned/")
                    perTap.forEach { (tap, seen) -> assertEquals(expected, seen, "GCS heartbeats reaching SITL $tap") }
                    "GCS heartbeats reaching each SITL in 3 s: " + perTap.entries.joinToString { (tap, seen) -> "SITL $tap ${seen.sorted()}" }
                }

                row("T5 spoofed telemetry") {
                    val before = v1(1).state.position
                    val spoof = ProcessBuilder("wsl.exe", "-d", "Ubuntu-22.04", "--", "python3", wsl(File(root, "tools/sitl/s9_signing_sitl.py")),
                        "spoof", "--work", wsl(work), "--sysid", "1", "--seconds", "6").redirectErrorStream(true).start()
                    val positions = mutableListOf<LatLon?>()
                    repeat(8) { positions += v1(1).state.position; delay(1_000) }
                    val out = spoof.inputStream.bufferedReader().readText().trim()
                    assertEquals(0, spoof.waitFor(), out)
                    assertTrue(positions.none { it != null && it.latitude > 5.0 }, "a spoofed 10 N position reached the vehicle: $positions")
                    assertEquals(SigningStatus.Signed(uidA), v1(1).state.signing)
                    assertEquals(3, connected(link1)!!.vehicles.size)
                    "$out; position stayed at ${positions.last()} (was $before), still '${v1(1).state.signing.label}'"
                }

                // GCS 2: its own vault with drone A only, so it also covers T7 (no key for drone B).
                val (vault2, _, keyring2) = installation("gcs2", "bundle_a.vnkey", avoidLinkId = l1)
                val l2 = vault2.linkId
                val links2 = links(keyring2)
                val fleet2 = Fleet(scope, links2.open, loginKey, MissionSync())
                links2.connect(LinkConfig.UdpClient(host, 14551))
                val link2 = links2.open.value.single()
                withTimeout(60.seconds) {
                    fleet2.vehicles.first { all -> all.size == 2 && all.all { it.state.login?.allowsTraffic == true } }
                }

                row("T7 no key") {
                    val c = withTimeoutOrNull(15.seconds) { link2.state.first { (it as? LinkState.Connected)?.signingBlocked?.get(2u) == SigningStatus.NoKey } }
                        as? LinkState.Connected ?: error("sysid 2 never shown as NoKey on GCS 2: ${connected(link2)}")
                    assertEquals(listOf(1, 3), c.vehicles.map { it.systemId.toInt() })
                    val refused = link2.senderFor(2u).send(ParamRequestRead(targetSystem = 2u, paramId = "SYSID_THISMAV", paramIndex = -1))
                    assertEquals(MavTxGateway.NO_KEY, (refused as TxResult.Rejected).reason)
                    "GCS 2 (keys: A only): sysid 2 shown as '${SigningStatus.NoKey.label}' on Links, not a vehicle; send refused \"${refused.reason}\""
                }

                row("T10 two installations") {
                    val since = nowS()
                    val answers = listOf(ask(link1, 1u), ask(link2, 1u), ask(link1, 1u), ask(link2, 1u))
                    assertEquals(List(4) { 1f }, answers, "both GCSs answered by sysid 1")
                    val links = tapped(0, 20, since).map { it.first }.toSet() // PARAM_REQUEST_READ into SITL 0
                    assertEquals(setOf("A/$l1", "A/$l2"), links)
                    "GCS 1 link_id $l1 and GCS 2 link_id $l2 both answered by sysid 1 (SYSID_THISMAV = ${answers.first()}); SITL 0 received ${links.sorted()}"
                }

                row("T6 vault locked") {
                    vault1.lock()
                    // Before the next heartbeat the status still says Signed, so FrameSink is what refuses (no key to sign with).
                    val immediate = link1.senderFor(1u).send(ParamRequestRead(targetSystem = 1u, paramId = "SYSID_THISMAV", paramIndex = -1))
                    val refused: TxResult
                    val stillUnsigned: Any
                    val gcs2: Any
                    try {
                        assertTrue(immediate is TxResult.Failed || immediate is TxResult.Rejected, "sent while locked: $immediate")
                        withTimeout(15.seconds) { link1.state.first { (it as? LinkState.Connected)?.signingBlocked?.get(1u) == SigningStatus.Locked } }
                        refused = link1.senderFor(1u).send(ParamRequestRead(targetSystem = 1u, paramId = "SYSID_THISMAV", paramIndex = -1))
                        assertEquals(MavTxGateway.LOCKED, (refused as TxResult.Rejected).reason)
                        stillUnsigned = ask(link1, 3u)
                        gcs2 = ask(link2, 1u)
                    } finally {
                        vault1.unlock(pin)
                    }
                    withTimeout(30.seconds) { fleet1.vehicles.first { all -> all.any { it.id.systemId.toInt() == 1 && it.state.signing == SigningStatus.Signed(uidA) } } }
                    val after = ask(link1, 1u)
                    assertEquals(listOf<Any>(3f, 1f, 1f), listOf(stillUnsigned, gcs2, after))
                    "locked: first send in the same second $immediate; once the heartbeat showed Locked, refused \"${(refused as TxResult.Rejected).reason}\", sysid 1 shown '${SigningStatus.Locked.label}', unsigned sysid 3 still answered, GCS 2 unaffected; unlocked: sysid 1 Signed again and answered"
                }

                row("T11 clock behind saved timestamp") {
                    links1.disconnect(link1.id)
                    delay(1_000)
                    // Stand-in for "laptop clock set 1 h back": the saved timestamp is 1 h ahead of the wall clock.
                    val saved = (System.currentTimeMillis() + 3_600_000 - 1_420_070_400_000L) * 100
                    timestamps1.save(uidA, saved)
                    timestamps1.flush()
                    val since = nowS()
                    links1.connect(LinkConfig.UdpClient(host, 14550))
                    val again = links1.open.value.single()
                    withTimeout(60.seconds) { again.state.first { s -> (s as? LinkState.Connected)?.vehicles?.any { it.systemId.toInt() == 1 && it.signing == SigningStatus.Signed(uidA) } == true } }
                    delay(5_000) // KFT login runs again on the new link
                    val answer = ask(again, 1u)
                    assertEquals(1f, answer)
                    val sent = tapped(0, 20, since).filter { it.first == "A/$l1" }.map { it.second }
                    assertTrue(sent.isNotEmpty() && sent.all { it > saved }, "timestamps $sent must all be above $saved")
                    "restarted with the saved timestamp 1 h ahead of the wall clock: answered; outgoing timestamps ${sent.min()}.. > saved $saved"
                }

                row("S8 SIGFAIL seen by GCS") {
                    val counts = fleet1.vehicles.value.associate { it.id.systemId.toInt() to it.state.fcSignatureFailures }
                    assertTrue(counts[1] != null && counts[2] != null, "$counts")
                    "NAMED_VALUE_INT SIGFAIL per sysid at the end: $counts (sysid 3 has no key, so no counter)"
                }
            }
        } finally {
            scope.cancel()
            val table = rows.joinToString("\n") { (id, ok, detail) -> "| $id | ${if (ok) "PASS" else "FAIL"} | $detail |" }
            File(work, "results.txt").writeText(table + "\n")
            println(table)
        }
        assertTrue(rows.all { it.second }, "failed rows: ${rows.filterNot { it.second }.map { it.first }}")
    }

    /** C:\x\y as WSL sees it, /mnt/c/x/y, for the spoof subprocess. */
    private fun wsl(f: File) = "/mnt/" + f.absolutePath.first().lowercase() + f.absolutePath.substring(2).replace('\\', '/')
}
