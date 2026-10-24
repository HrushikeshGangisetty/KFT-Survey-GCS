package com.kft.gcs.core.mavlink.signing

/** How one vehicle (system id) on a link signs its traffic, as [SigningSource] has seen it (PRD FR-8). */
sealed interface SigningStatus {
    /** It sends unsigned frames and has never been bound to a key on this link. Today's normal case. */
    data object Unsigned : SigningStatus

    /** Bound to [droneId]: its heartbeats verify with that drone's key. */
    data class Signed(val droneId: String) : SigningStatus

    /** It signs, but none of this device's keys verifies its heartbeat. Its signed frames are dropped. */
    data object NoKey : SigningStatus

    /** It signs, but the vault is locked, so nothing can be checked. Its signed frames are dropped. */
    data object Locked : SigningStatus

    /**
     * Bound to [droneId], but its latest heartbeat failed verification. [count] is every bad-signature frame from it on
     * this link. The binding stands: the gateway still signs for [droneId], so a forger who injects one bad heartbeat
     * can't cut the GCS off from the real drone.
     */
    data class BadSignature(val droneId: String, val count: Int) : SigningStatus
}

/** Frames [SigningSource] dropped on one link, by reason. Kept for diagnostics; nothing here is ever passed on. */
data class SigningCounters(
    /** Signed frames from a bound vehicle whose signature didn't match its key. */
    val badSignature: Int = 0,
    /** Correctly signed frames that were not newer than the last one on their stream, or too old for a new stream. */
    val replayed: Int = 0,
    /** Unsigned or MAVLink 1 frames claiming a bound vehicle's system id (RADIO_STATUS excepted). */
    val spoofed: Int = 0,
    /** Signed frames from an unbound vehicle that couldn't be checked: no key, vault locked, or not a HEARTBEAT. */
    val unverified: Int = 0,
    /** MAVLink 2 frames with an incompat flag this GCS doesn't know, so their layout is unknown. */
    val malformed: Int = 0,
)

/** How serious a [SigningStatus] is, for the badge colour. The UI picks the actual colour. */
enum class SigningTone { GOOD, NEUTRAL, WARNING }

/**
 * The badge text for a vehicle (PRD FR-8). `Unsigned` reads "Unsigned link" because that is the fact: the link is
 * not signed. It is neutral, not a warning, since most of today's fleet is unsigned (NFR-5).
 */
val SigningStatus.label: String
    get() = when (this) {
        SigningStatus.Unsigned -> "Unsigned link"
        is SigningStatus.Signed -> "Signed"
        SigningStatus.NoKey -> "No key"
        SigningStatus.Locked -> "Keys locked"
        is SigningStatus.BadSignature -> "Bad signature ($count)"
    }

val SigningStatus.tone: SigningTone
    get() = when (this) {
        is SigningStatus.Signed -> SigningTone.GOOD
        SigningStatus.Unsigned -> SigningTone.NEUTRAL
        SigningStatus.NoKey, SigningStatus.Locked, is SigningStatus.BadSignature -> SigningTone.WARNING
    }
