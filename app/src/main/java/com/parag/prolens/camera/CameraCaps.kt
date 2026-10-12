package com.parag.prolens.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.sqrt

/** What the current lens/sensor actually lets us control, read from Camera2. */
data class CameraCaps(
    val manualSensor: Boolean = false,
    val manualFocus: Boolean = false,
    val isoRange: Range<Int> = Range(100, 3200),
    val exposureRangeNs: Range<Long> = Range(100_000L, 250_000_000L),
    val apertures: FloatArray = floatArrayOf(1.8f),
    val focalLengthMm: Float = 6f,
    val equivalentFocalMm: Float = 24f,
    val minFocusDiopters: Float = 10f,
    val evRange: Range<Int> = Range(-12, 12),
    val evStep: Float = 1f / 6f,
    val awbModes: IntArray = intArrayOf(),
) {
    val hasVariableAperture get() = apertures.size > 1
    val baseAperture get() = apertures.minOrNull() ?: 1.8f
    val minIso get() = isoRange.lower
    val maxIso get() = isoRange.upper

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun from(info: CameraInfo): CameraCaps {
            val c = Camera2CameraInfo.from(info)
            fun <T> get(key: CameraCharacteristics.Key<T>): T? = try {
                c.getCameraCharacteristic(key)
            } catch (_: Exception) {
                null
            }

            val capabilities = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val focal = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 6f
            val sensor = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val diag = sensor?.let { sqrt(it.width * it.width + it.height * it.height) } ?: 7.5f
            val minFocus = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val ev = info.exposureState
            return CameraCaps(
                manualSensor = manualSensor,
                manualFocus = minFocus > 0f,
                isoRange = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200),
                exposureRangeNs = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?: Range(100_000L, 250_000_000L),
                apertures = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f),
                focalLengthMm = focal,
                equivalentFocalMm = focal * 43.27f / diag,
                minFocusDiopters = minFocus,
                evRange = ev.exposureCompensationRange,
                evStep = ev.exposureCompensationStep.toFloat(),
                awbModes = get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(),
            )
        }
    }
}

/** White balance presets that map onto Camera2's built-in AWB modes (reliable on every vendor). */
enum class WhiteBalance(val label: String, val kelvin: Int?, val awbMode: Int) {
    AUTO("Auto", null, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", 5500, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", 6500, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", 7500, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN("Tungsten", 3200, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", 4000, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    TWILIGHT("Twilight", 4500, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT),
}

/** Standard photographic shutter stops, in nanoseconds. */
object Stops {
    val SHUTTERS_NS: List<Long> = listOf(
        8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4,
    ).map { 1_000_000_000L / it } + listOf(500_000_000L, 1_000_000_000L)

    val ISOS = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 12800)

    val VIRTUAL_APERTURES = listOf(1.2f, 1.4f, 1.8f, 2.0f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)

    fun shutterLabel(ns: Long): String {
        if (ns <= 0) return "--"
        val sec = ns / 1e9
        return if (sec >= 0.5) {
            val r = Math.round(sec * 10) / 10.0
            if (r == Math.floor(r)) "${r.toInt()}\"" else "$r\""
        } else {
            "1/${Math.round(1.0 / sec)}"
        }
    }

    fun fNumberLabel(f: Float) = if (f >= 10f || f == Math.floor(f.toDouble()).toFloat()) "f/${f.toInt()}" else "f/$f"
}
