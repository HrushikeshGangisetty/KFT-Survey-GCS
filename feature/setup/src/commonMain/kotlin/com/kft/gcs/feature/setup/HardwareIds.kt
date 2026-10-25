package com.kft.gcs.feature.setup

/**
 * An ArduPilot sensor device id, split into its fields. ArduPilot packs it into one 32-bit parameter
 * (AP_HAL/Device.h `struct DeviceStructure`, KFT fork d91a40ef), lowest bits first:
 * ```
 * bits 0-2   bus type   (1 I2C, 2 SPI, 3 DroneCAN, 4 SITL, 5 MSP, 6 serial, 7 WSPI)
 * bits 3-7   bus        (which bus of that type, from 0)
 * bits 8-15  address    (I2C address, SPI chip select, DroneCAN node id)
 * bits 16-23 devtype    (the chip, numbered per sensor class)
 * ```
 * The whole id stays below 2^24, so it travels through a float PARAM_VALUE exactly.
 */
data class DeviceId(val busType: Int, val bus: Int, val address: Int, val devtype: Int)

fun decodeDeviceId(id: Int) = DeviceId(id and 0x7, (id shr 3) and 0x1F, (id shr 8) and 0xFF, (id shr 16) and 0xFF)

/** One sensor on the Hardware ID page. */
data class SensorRow(val sensor: String, val param: String, val bus: String, val chip: String, val id: Int)

private enum class SensorClass(val label: String, val order: Int) { ACCEL("Accelerometer", 0), GYRO("Gyro", 1), COMPASS("Compass", 2), BARO("Barometer", 3), AIRSPEED("Airspeed", 4) }

/**
 * Every sensor whose id parameter is non-zero (0 = no sensor in that slot), accelerometers first. Parameter names, from
 * the fork's AP_GROUPINFO: COMPASS_DEV_ID, COMPASS_DEV_ID2..8 (4..8 are detected but unused); INS_ACC_ID, INS_ACC2_ID,
 * INS_ACC3_ID (and INS4_ACC_ID… on boards with more IMUs), the same with GYR; BARO1_DEVID..BARO3_DEVID; ARSPD_DEVID,
 * ARSPD2_DEVID.
 */
fun sensorRows(values: Map<String, Float>): List<SensorRow> = values.mapNotNull { (name, value) ->
    val (cls, instance) = sensorOf(name) ?: return@mapNotNull null
    val id = value.toInt().takeIf { it != 0 } ?: return@mapNotNull null
    val d = decodeDeviceId(id)
    Triple(cls, instance, SensorRow("${cls.label} $instance", name, busText(d), chipName(cls, d), id))
}.sortedWith(compareBy({ it.first.order }, { it.second })).map { it.third }

private val INS = Regex("INS(\\d?)_(ACC|GYR)(\\d?)_ID")
private val COMPASS = Regex("COMPASS_DEV_ID(\\d?)")
private val BARO = Regex("BARO(\\d)_DEVID")
private val ARSPD = Regex("ARSPD(\\d?)_DEVID")

private fun sensorOf(name: String): Pair<SensorClass, Int>? {
    INS.matchEntire(name)?.let { m ->
        val (group, kind, inner) = m.destructured
        return (if (kind == "ACC") SensorClass.ACCEL else SensorClass.GYRO) to (group.ifEmpty { inner.ifEmpty { "1" } }).toInt()
    }
    COMPASS.matchEntire(name)?.let { return SensorClass.COMPASS to it.groupValues[1].ifEmpty { "1" }.toInt() }
    BARO.matchEntire(name)?.let { return SensorClass.BARO to it.groupValues[1].toInt() }
    ARSPD.matchEntire(name)?.let { return SensorClass.AIRSPEED to it.groupValues[1].ifEmpty { "1" }.toInt() }
    return null
}

private fun busText(d: DeviceId): String = when (d.busType) {
    1 -> "I2C bus ${d.bus}, address 0x${d.address.toString(16).padStart(2, '0')}"
    2 -> "SPI bus ${d.bus}, device ${d.address}"
    // AP_DroneCAN makes these ids with the CAN driver index (from 0) as the bus and the node id as the address.
    3 -> "DroneCAN ${d.bus + 1}, node ${d.address}"
    4 -> "SITL (simulated)"
    5 -> "MSP"
    6 -> "Serial ${d.bus}"
    7 -> "WSPI bus ${d.bus}"
    else -> "Unknown bus"
}

/** The chip, from the sensor class's own DevTypes table. DroneCAN ids carry a sensor number there instead of a chip. */
private fun chipName(cls: SensorClass, d: DeviceId): String {
    if (d.busType == 3) return "DroneCAN sensor"
    val table = when (cls) {
        SensorClass.ACCEL, SensorClass.GYRO -> IMU_TYPES
        SensorClass.COMPASS -> COMPASS_TYPES
        SensorClass.BARO -> BARO_TYPES
        SensorClass.AIRSPEED -> emptyMap()
    }
    return table[d.devtype] ?: "Type 0x${d.devtype.toString(16).padStart(2, '0')}"
}

/** AP_InertialSensor_Backend.h `enum DevTypes`. */
private val IMU_TYPES = mapOf(
    0x09 to "BMI160", 0x10 to "L3G4200D", 0x11 to "LSM303D", 0x12 to "BMA180", 0x13 to "MPU6000", 0x16 to "MPU9250",
    0x17 to "IIS328DQ", 0x18 to "LSM9DS1", 0x21 to "MPU6000", 0x22 to "L3GD20", 0x24 to "MPU9250", 0x25 to "I3G4250D",
    0x26 to "LSM9DS1", 0x27 to "ICM20789", 0x28 to "ICM20689", 0x29 to "BMI055", 0x2A to "SITL", 0x2B to "BMI088",
    0x2C to "ICM20948", 0x2D to "ICM20648", 0x2E to "ICM20649", 0x2F to "ICM20602", 0x30 to "ICM20601", 0x31 to "ADIS1647x",
    0x32 to "Serial", 0x33 to "ICM40609", 0x34 to "ICM42688", 0x35 to "ICM42605", 0x36 to "ICM40605", 0x37 to "IIM42652",
    0x38 to "BMI270", 0x39 to "BMI085", 0x3A to "ICM42670", 0x3B to "ICM45686", 0x3C to "SCHA63T", 0x3D to "IIM42653",
)

/** AP_Compass_Backend.h `enum DevTypes`. */
private val COMPASS_TYPES = mapOf(
    0x01 to "HMC5883 (old)", 0x02 to "LSM303D", 0x04 to "AK8963", 0x05 to "BMM150", 0x06 to "LSM9DS1", 0x07 to "HMC5883",
    0x08 to "LIS3MDL", 0x09 to "AK09916", 0x0A to "IST8310", 0x0B to "ICM20948", 0x0C to "MMC3416", 0x0D to "QMC5883L",
    0x0E to "MAG3110", 0x0F to "SITL", 0x10 to "IST8308", 0x11 to "RM3100", 0x12 to "RM3100", 0x13 to "MMC5983",
    0x14 to "AK09918", 0x15 to "AK09915", 0x16 to "QMC5883P", 0x17 to "BMM350", 0x18 to "IIS2MDC",
)

/** AP_Baro_Backend.h `enum DevTypes`. */
private val BARO_TYPES = mapOf(
    0x01 to "SITL", 0x02 to "BMP085", 0x03 to "BMP280", 0x04 to "BMP388", 0x05 to "DPS280", 0x06 to "DPS310", 0x07 to "FBM320",
    0x08 to "ICM20789", 0x09 to "KellerLD", 0x0A to "LPS2XH", 0x0B to "MS5611", 0x0C to "SPL06", 0x0D to "DroneCAN", 0x0E to "MSP",
    0x0F to "ICP101XX", 0x10 to "ICP201XX", 0x11 to "MS5607", 0x12 to "MS5837", 0x13 to "MS5637", 0x14 to "BMP390", 0x15 to "BMP581",
    0x16 to "SPA06",
)
