package com.kft.gcs.feature.params

/**
 * Everything the Params screen draws (CLAUDE.md §3).
 *
 * @property status one line: "Not downloaded", "Downloading 120 / 1320", "1320 parameters · 2 modified".
 * @property canDownload a vehicle is connected, its KFT login allows traffic, and nothing else is running.
 * @property canWrite [canDownload], parameters are loaded, and the vehicle is disarmed.
 * @property canSave parameters are loaded (a file can be written whatever the vehicle is doing).
 * @property writeHint why editing is off when it is, e.g. "Disarm to change parameters". Null when it's on.
 * @property metadata where the descriptions come from, or why there are none ("Descriptions: ArduCopter 4.5.7,
 *   downloaded"). Null before the first download.
 * @property rows the parameters matching [query] (by name, display name or description), by group then name.
 */
data class ParamsUiState(
    val status: String = "Not downloaded",
    val canDownload: Boolean = false,
    val canWrite: Boolean = false,
    val canSave: Boolean = false,
    val writeHint: String? = null,
    val query: String = "",
    val metadata: String? = null,
    val rows: List<ParamRow> = emptyList(),
    val edit: ParamEdit? = null,
    val fileLoad: FileLoad? = null,
)

/**
 * One line of the list. [modified] = the value differs from what was downloaded (changed in this session).
 * [note] = the last write that didn't take, e.g. "Locked or rejected by the vehicle: it kept 0."
 * The rest comes from the metadata when there is some: [group] (the name's prefix, for the list headers),
 * [displayName], [valueLabel] (the documented name of the current value, "Servo"), [units], and the two flags.
 */
data class ParamRow(
    val name: String,
    val value: String,
    val modified: Boolean,
    val note: String?,
    val group: String = name.substringBefore('_'),
    val displayName: String? = null,
    val valueLabel: String? = null,
    val units: String? = null,
    val rebootRequired: Boolean = false,
    val readOnly: Boolean = false,
)

/**
 * The edit dialog. While [confirm] is null it asks for the value; once the value checks out, [confirm] holds the
 * question ("Change CAM1_TYPE from 0 to 1?") and the dialog asks for confirmation before anything is sent.
 *
 * With metadata: [description], [facts] ("Units s · Range 0 to 5 · Step 0.1 · Needs a restart"), and how to pick the
 * value: [choices] (documented values, code text to label) become a dropdown, [bits] a checkbox list, otherwise a
 * text field. [readOnly] parameters show their help but can't be set.
 */
data class ParamEdit(
    val name: String,
    val type: String,
    val current: String,
    val text: String,
    val error: String?,
    val confirm: String?,
    val displayName: String? = null,
    val description: String? = null,
    val facts: String? = null,
    val readOnly: Boolean = false,
    val choices: List<Pair<String, String>> = emptyList(),
    val bits: List<BitChoice> = emptyList(),
)

/** One bit of a bitmask parameter: its number and documented meaning. The tick comes from the typed text ([hasBit]). */
data class BitChoice(val bit: Int, val label: String)

/** A `.param` file compared with the vehicle, waiting for the operator to confirm the writes. */
data class FileLoad(val fileName: String, val changes: List<ParamChange>, val skipped: String?)

data class ParamChange(val name: String, val from: String, val to: String)
