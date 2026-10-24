package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.definitions.common.ParamRequestRead
import com.divpundir.mavlink.definitions.common.ParamValue
import com.kft.gcs.core.mavlink.signing.FakeKeyring
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.mavlink.signing.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

/**
 * Signing S3 acceptance against one ArduCopter SITL (`start-sitl.ps1`) whose key was set from MAVProxy on SERIAL0 with
 * `signing setup <passphrase>`. MAVProxy's key is SHA-256 of the passphrase (mavproxy_signing.py), so the same
 * passphrase gives this run the same key.
 *
 * Skipped unless `KFT_SITL_SIGNING_RUN=1`. `KFT_SITL_SIGNING_PASSPHRASE` is the passphrase (default `kft-sitl`). Links:
 * the keyed GCS on TCP 5762 (SERIAL1) and a GCS with no keys on TCP 5763 (SERIAL2). Not SERIAL0: ArduPilot accepts
 * unsigned frames on channel 0 (its USB port), so only the other ports test signing.
 */
class SigningSitlRun {
    @Test
    fun aKeyedGcsBindsAndIsAnsweredAndAKeylessOneIsRefused() {
        if (System.getenv("KFT_SITL_SIGNING_RUN") != "1") return println("KFT_SITL_SIGNING_RUN not set: skipped")
        val passphrase = System.getenv("KFT_SITL_SIGNING_PASSPHRASE") ?: "kft-sitl"
        val keyring = FakeKeyring(mapOf("sitl" to sha256(passphrase.encodeToByteArray())), linkId = 42u)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = SerialPorts()
        val t12 = T12Link(scope, Dispatchers.IO, ports)
        val keyed = Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), ports, t12, keyring)
        val keyless = Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), ports, t12)
        try {
            runBlocking {
                keyed.connect(LinkConfig.TcpClient("127.0.0.1", 5762))
                val link = keyed.open.value.single()
                // 1. Trial binding: SITL's signed heartbeat verifies with the passphrase's key.
                val vehicle = withTimeout(30.seconds) {
                    link.state.first { (it as? LinkState.Connected)?.vehicle?.signing == SigningStatus.Signed("sitl") }
                }.let { (it as LinkState.Connected).vehicle!! }
                println("bound: system ${vehicle.systemId} -> ${vehicle.signing}")

                // 2. A signed request is answered: the FC accepted our signature and our 48-bit timestamp.
                val answer = async { withTimeout(10.seconds) { link.frames.first { it.message is ParamValue } } }
                delay(200) // let the collector subscribe before the request goes out
                val sent = link.senderFor(vehicle.systemId).send(ParamRequestRead(targetSystem = vehicle.systemId, targetComponent = 1u, paramId = "SYSID_THISMAV", paramIndex = -1))
                assertEquals(TxResult.Sent, sent)
                val value = answer.await().message as ParamValue
                println("answered: ${value.paramId} = ${value.paramValue}")
                assertEquals(vehicle.systemId.toFloat(), value.paramValue)

                // 3. No key on this device: the vehicle shows NoKey, never appears as a vehicle, and every send is refused.
                keyless.connect(LinkConfig.TcpClient("127.0.0.1", 5763))
                val other = keyless.open.value.single()
                delay(5_000)
                val connected = assertIs<LinkState.Connected>(other.state.value)
                assertEquals(emptyList(), connected.vehicles, "its heartbeats don't verify, so they never reach the decoder")
                val refused = assertIs<TxResult.Rejected>(other.senderFor(vehicle.systemId).send(ParamRequestRead(targetSystem = vehicle.systemId, paramId = "SYSID_THISMAV", paramIndex = -1)))
                assertEquals(MavTxGateway.NO_KEY, refused.reason)

                // 4. Disconnect saves the signing clock.
                keyed.disconnect(link.id)
                delay(500)
                println("saved timestamp: ${assertNotNull(keyring.timestamps["sitl"])}")
            }
        } finally {
            scope.cancel()
        }
    }
}
