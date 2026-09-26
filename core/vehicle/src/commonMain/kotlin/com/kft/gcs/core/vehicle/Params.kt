package com.kft.gcs.core.vehicle

/**
 * One vehicle parameter, as the vehicle last reported it. No MAVLink types, so screens can hold it.
 *
 * [value] is a Float because that is what PARAM_VALUE carries. ArduPilot uses MAVLink's "C cast" encoding: an
 * integer parameter is sent as the number itself converted to float (`AP_Param::cast_to_float`), not its bytes
 * packed into the float. So `3.0f` means 3. An INT32 above 2^24 (16 777 216) can't be represented exactly; ArduPilot
 * has no parameter that large in practice, and every GCS shares the limit.
 *
 * @property index the vehicle's position for it in PARAM_REQUEST_LIST order. It is only valid until the parameter
 *   count changes (enabling a feature such as CAM1_TYPE adds parameters), which is why sets go by [name].
 */
data class Param(val name: String, val value: Float, val type: ParamType, val index: Int)

/**
 * The four storage types ArduPilot has (`ap_var_type` → MAV_PARAM_TYPE in GCS_MAVLINK::mav_param_type). Integer
 * types take whole numbers only: ArduPilot adds 0.01 and truncates (`AP_Param::set_float`), so 2.5 would become 2.
 */
enum class ParamType(val integer: Boolean) {
    INT8(true), INT16(true), INT32(true), REAL32(false);

    /** The range the vehicle can store, for input checks. INT32 stops at 2^24: beyond it a float skips integers. */
    val range: ClosedFloatingPointRange<Double>
        get() = when (this) {
            INT8 -> -128.0..127.0
            INT16 -> -32768.0..32767.0
            INT32 -> -16_777_216.0..16_777_216.0
            REAL32 -> -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()
        }
}

/**
 * The value as a person reads and types it: whole numbers without ".0" for integer types, Kotlin's shortest
 * round-trip text for floats ("0.1", not "0.100000001"). The same text goes into `.param` files.
 */
val Param.valueText: String get() = formatParamValue(value, type)

/** See [valueText]. */
fun formatParamValue(value: Float, type: ParamType): String =
    if (type.integer) value.toLong().toString() else value.toString().removeSuffix(".0")

/**
 * Reads what the operator typed for a parameter of [type]. Null plus a reason when the vehicle couldn't store it
 * exactly: not a number, a fraction for an integer type, or out of range.
 */
fun parseParamInput(text: String, type: ParamType): Result<Float> {
    val number = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: return Result.failure(IllegalArgumentException("Not a number"))
    if (type.integer && number != kotlin.math.floor(number)) return Result.failure(IllegalArgumentException("Whole numbers only"))
    if (number !in type.range) return Result.failure(IllegalArgumentException("Out of range for ${type.name}"))
    return Result.success(number.toFloat())
}

/** How a parameter write ended. */
sealed interface ParamSetResult {
    /** The vehicle reported the new value back. [param] is what it now holds. */
    data class Applied(val param: Param) : ParamSetResult

    /**
     * The value was not applied, or not confirmed. [reason] is written for the operator. [vehicleValue] is the value
     * the vehicle reported keeping, when it said one (a locked or read-only parameter echoes its old value).
     */
    data class NotApplied(val reason: String, val vehicleValue: Param? = null) : ParamSetResult
}
