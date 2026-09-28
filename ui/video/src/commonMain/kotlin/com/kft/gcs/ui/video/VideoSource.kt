package com.kft.gcs.ui.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a picture comes from. Two kinds, because they need different machinery underneath:
 * - [PlayerSource]: a stream URL a media player opens by itself (the SIYI camera's RTSP).
 * - [ElementaryStreamSource]: raw H.264 bytes our own code receives (the Skydroid T12's serial link). We split them
 *   into NAL units ([NalQueue]) and the platform decoder renders them.
 */
sealed interface VideoSource

/** An RTSP (or other) URL, played by Media3 on Android and FFmpeg on desktop. Android plays H.264 only (ADR-004). */
data class PlayerSource(val url: String) : VideoSource

/**
 * H.264 Annex-B [bytes], in arbitrary chunks; collecting them starts the stream. [problem] says why nothing arrives
 * (for example "No Skydroid T12 on USB"), shown while connecting. [key] identifies the source for equality, since
 * two flows can't be compared.
 */
class ElementaryStreamSource(val key: String, val bytes: Flow<ByteArray>, val problem: StateFlow<String?>) : VideoSource {
    override fun equals(other: Any?) = other is ElementaryStreamSource && other.key == key
    override fun hashCode() = key.hashCode()
}
