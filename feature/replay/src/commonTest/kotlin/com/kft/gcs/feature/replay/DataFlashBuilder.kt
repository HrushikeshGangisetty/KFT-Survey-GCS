package com.kft.gcs.feature.replay

import kotlin.math.roundToLong

/**
 * Writes a small DataFlash log for tests, from the format letters in ArduPilot's LogStructure.h. Only the letters the
 * replay reads: Q (uint64), B (uint8), L (degrees × 10^7 in an int32), f (float), Z (64 chars).
 */
class DataFlashBuilder {
    private val out = mutableListOf<Byte>()
    private val letters = mutableMapOf<Int, String>()

    fun fmt(type: Int, name: String, format: String, labels: String) = apply {
        letters[type] = format
        val length = 3 + format.sumOf { SIZE.getValue(it) }
        header(128)
        out += type.toByte(); out += length.toByte()
        text(name, 4); text(format, 16); text(labels, 64)
    }

    fun row(type: Int, vararg values: Any) = apply {
        header(type)
        letters.getValue(type).forEachIndexed { i, letter ->
            val v = values[i]
            when (letter) {
                'Q' -> le((v as Number).toLong(), 8)
                'B' -> out += (v as Number).toByte()
                'L' -> le(((v as Double) * 1e7).roundToLong(), 4)
                'f' -> le((v as Float).toRawBits().toLong(), 4)
                'Z' -> text(v as String, 64)
            }
        }
    }

    fun build() = out.toByteArray()

    private fun header(type: Int) { out += 0xA3.toByte(); out += 0x95.toByte(); out += type.toByte() }
    private fun le(v: Long, n: Int) = repeat(n) { out += (v shr (8 * it)).toByte() }
    private fun text(s: String, n: Int) = s.encodeToByteArray().copyOf(n).forEach { out += it }

    private companion object {
        val SIZE = mapOf('Q' to 8, 'B' to 1, 'L' to 4, 'f' to 4, 'Z' to 64)
    }
}
