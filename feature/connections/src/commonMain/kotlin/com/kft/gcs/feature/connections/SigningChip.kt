package com.kft.gcs.feature.connections

import androidx.compose.runtime.Composable
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.mavlink.signing.SigningTone
import com.kft.gcs.core.mavlink.signing.label
import com.kft.gcs.core.mavlink.signing.tone
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip

/**
 * How a vehicle signs its MAVLink (PRD FR-8). Signed = good + closed lock; Unsigned = neutral + open lock (most of the
 * fleet today, not a problem); everything else = warning + the usual triangle. The icon and text carry the meaning too,
 * never the colour alone. Each feature keeps its own copy because features never import each other (CLAUDE.md §2).
 */
@Composable
internal fun SigningChip(status: SigningStatus) {
    when (status.tone) {
        SigningTone.GOOD -> StatusChip(status.label, Status.OK, icon = KftIcons.Lock)
        SigningTone.NEUTRAL -> StatusChip(status.label, Status.NEUTRAL, icon = KftIcons.LockOpen)
        SigningTone.WARNING -> StatusChip(status.label, Status.WARN)
    }
}
