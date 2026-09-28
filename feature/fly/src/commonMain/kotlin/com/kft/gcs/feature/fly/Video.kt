package com.kft.gcs.feature.fly

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.kft.gcs.ui.video.ElementaryStreamSource
import com.kft.gcs.ui.video.PlayerSource
import com.kft.gcs.ui.video.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a video profile's picture comes from. `@SerialName`s are the on-disk names, as for connection profiles. */
@Serializable
sealed interface VideoLink {
    /** An RTSP URL, e.g. the SIYI camera behind an MK15 air unit. */
    @Serializable @SerialName("rtsp")
    data class Rtsp(val url: String) : VideoLink

    /** The Skydroid T12's own video over its USB link. */
    @Serializable @SerialName("t12")
    data object T12 : VideoLink
}

@Serializable
data class VideoProfile(val name: String, val link: VideoLink)

/** Always offered, can't be deleted: the two video systems KFT flies with. */
val VIDEO_PRESETS = listOf(
    // SIYI's documented RTSP address on its air units (the old KFT app's default); "main.264" is the main stream.
    VideoProfile("SIYI (RTSP 192.168.144.25:8554)", VideoLink.Rtsp("rtsp://192.168.144.25:8554/main.264")),
    VideoProfile("Skydroid T12 (USB)", VideoLink.T12),
)

/** Where the video profiles live, as one text blob (a file in the app's data folder; a string in tests). */
interface VideoStore {
    fun read(): String?
    fun write(text: String)
}

/** On-disk shape: the operator's own profiles (the presets aren't saved) and the one last chosen, by name. */
@Serializable
private data class SavedVideo(val selected: String? = null, val profiles: List<VideoProfile> = emptyList())

private val json = Json { prettyPrint = true; encodeDefaults = true }

/** Everything the video parts of the Fly view draw. [source] is null while video is off. */
@Immutable
data class VideoUiState(
    val profiles: List<VideoProfileRow>,
    val selected: String?,
    val source: VideoSource?,
    /** The picture-in-picture window is showing (a source is chosen and the operator hasn't hidden it). */
    val visible: Boolean,
    /** Video fills the view and the map is the small window. */
    val swapped: Boolean,
    val showStats: Boolean,
)

data class VideoProfileRow(val name: String, val summary: String, val custom: Boolean)

/**
 * Video on the Fly view: the profiles (presets plus the operator's own RTSP URLs, saved through [store] like
 * connection profiles), the chosen one, and the window state (shown, swapped with the map, statistics).
 *
 * @param t12 the T12's video, from `core:mavlink`'s T12Link. Passed in as a source so this class doesn't need the link.
 */
class VideoViewModel(private val store: VideoStore, private val t12: ElementaryStreamSource) : ViewModel() {
    private data class Local(val saved: SavedVideo, val visible: Boolean, val swapped: Boolean = false, val showStats: Boolean = false)

    private val local = MutableStateFlow(load().let { Local(it, visible = it.selected != null) })
    private val _state = MutableStateFlow(build(local.value))
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    /** Chooses the source (null: video off) and shows the window, also when it's the one already chosen but hidden. */
    fun onSourceSelected(name: String?) = change(save = true) {
        val known = name == null || profiles(it.saved).any { p -> p.name == name }
        if (!known) it else it.copy(saved = it.saved.copy(selected = name), visible = name != null, swapped = it.swapped && name != null)
    }

    /** Adds (or replaces, by name) an RTSP profile and selects it. Returns the problem to show instead, if any. */
    fun onRtspAdded(name: String, url: String): String? {
        val u = url.trim()
        if (!u.startsWith("rtsp://", ignoreCase = true) || u.length <= "rtsp://".length) return "Enter an rtsp:// URL, e.g. rtsp://192.168.144.25:8554/main.264"
        val n = name.trim().ifEmpty { u.removePrefix("rtsp://") }
        if (VIDEO_PRESETS.any { it.name == n }) return "That name belongs to a preset: pick another"
        change(save = true) { l ->
            val others = l.saved.profiles.filterNot { it.name == n }
            l.copy(saved = SavedVideo(selected = n, profiles = others + VideoProfile(n, VideoLink.Rtsp(u))), visible = true)
        }
        return null
    }

    fun onProfileDeleted(name: String) = change(save = true) { l ->
        val selected = l.saved.selected.takeUnless { it == name }
        l.copy(saved = SavedVideo(selected, l.saved.profiles.filterNot { it.name == name }), visible = l.visible && selected != null)
    }

    fun onHideClicked() = change { it.copy(visible = false, swapped = false) }

    fun onSwapClicked() = change { it.copy(swapped = !it.swapped) }

    fun onStatsToggled() = change { it.copy(showStats = !it.showStats) }

    private fun change(save: Boolean = false, update: (Local) -> Local) {
        local.update(update)
        if (save) store.write(json.encodeToString(local.value.saved))
        _state.value = build(local.value)
    }

    private fun build(l: Local): VideoUiState {
        val selected = profiles(l.saved).firstOrNull { it.name == l.saved.selected }
        return VideoUiState(
            profiles = profiles(l.saved).map { p -> VideoProfileRow(p.name, p.link.summary(), custom = p !in VIDEO_PRESETS) },
            selected = selected?.name,
            source = when (val link = selected?.link) {
                null -> null
                VideoLink.T12 -> t12
                // A data class: equal for the same URL, so the view keeps its player across state changes.
                is VideoLink.Rtsp -> PlayerSource(link.url)
            },
            visible = l.visible && selected != null,
            swapped = l.swapped && l.visible && selected != null,
            showStats = l.showStats,
        )
    }

    private fun profiles(saved: SavedVideo) = VIDEO_PRESETS + saved.profiles

    /** A broken or missing file means "nothing saved": the presets are still there, nothing selected. */
    private fun load(): SavedVideo = try {
        store.read()?.let { json.decodeFromString<SavedVideo>(it) } ?: SavedVideo()
    } catch (e: IllegalArgumentException) {
        SavedVideo()
    }
}

private fun VideoLink.summary() = when (this) {
    is VideoLink.Rtsp -> url
    VideoLink.T12 -> "USB · H.264 over the T12's serial link"
}
