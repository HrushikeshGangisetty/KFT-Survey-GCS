package com.kft.gcs.feature.params

/**
 * Everything the Params screen draws (CLAUDE.md §3).
 *
 * @property status one line: "Not downloaded", "Downloading 120 / 1320", "1320 parameters · 2 modified".
 * @property canDownload a vehicle is connected, its KFT login allows traffic, and nothing else is running.
 * @property canWrite [canDownload], parameters are loaded, and the vehicle is disarmed.
 * @property canSave parameters are loaded (a file can be written whatever the vehicle is doing).
 * @property writeHint why editing is off when it is, e.g. "Disarm to change parameters". Null when it's on.
 * @property rows the parameters matching [query], sorted by name.
 */
data class ParamsUiState(
    val status: String = "Not downloaded",
    val canDownload: Boolean = false,
    val canWrite: Boolean = false,
    val canSave: Boolean = false,
    val writeHint: String? = null,
    val query: String = "",
    val rows: List<ParamRow> = emptyList(),
    val edit: ParamEdit? = null,
    val fileLoad: FileLoad? = null,
)

/**
 * One line of the list. [modified] = the value differs from what was downloaded (changed in this session).
 * [note] = the last write that didn't take, e.g. "Locked or rejected by the vehicle: it kept 0."
 */
data class ParamRow(val name: String, val value: String, val modified: Boolean, val note: String?)

/**
 * The edit dialog. While [confirm] is null it asks for the value; once the value checks out, [confirm] holds the
 * question ("Change CAM1_TYPE from 0 to 1?") and the dialog asks for confirmation before anything is sent.
 */
data class ParamEdit(val name: String, val type: String, val current: String, val text: String, val error: String?, val confirm: String?)

/** A `.param` file compared with the vehicle, waiting for the operator to confirm the writes. */
data class FileLoad(val fileName: String, val changes: List<ParamChange>, val skipped: String?)

data class ParamChange(val name: String, val from: String, val to: String)
