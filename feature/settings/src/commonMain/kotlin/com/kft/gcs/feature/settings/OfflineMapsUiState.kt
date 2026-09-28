package com.kft.gcs.feature.settings

import com.kft.gcs.ui.map.MapOverlay

/**
 * Everything the Maps tab draws.
 * - [area] / [estimate]: the area on screen and what downloading it costs; null until the map reports its view.
 * - [blockReason]: why Download is disabled (the provider's terms, no area yet, too many tiles), shown in words.
 * - [overlays]: outlines of the downloaded areas, drawn on the shared map while this tab is open.
 */
data class OfflineMapsUiState(
    val sources: List<SourceOption>,
    val sourceId: String,
    val zoomNote: String?,
    val minZoom: Int,
    val maxZoom: Int,
    val area: String?,
    val estimate: String?,
    val blockReason: String?,
    val canDownload: Boolean,
    val regions: List<RegionRow>,
    val imported: List<ImportedRow>,
    val overlays: List<MapOverlay>,
)

data class SourceOption(val id: String, val name: String)

/** A downloaded area. [fraction] is null when there's no bar to show (done, or the total isn't known yet). */
data class RegionRow(
    val id: Long,
    val name: String,
    val detail: String,
    val fraction: Float?,
    val canPause: Boolean,
    val canResume: Boolean,
)

data class ImportedRow(val id: String, val name: String, val size: String)
