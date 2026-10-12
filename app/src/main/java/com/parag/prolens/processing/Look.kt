package com.parag.prolens.processing

import android.graphics.ColorMatrix
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A colour "look", in the spirit of classic film stocks and cinema grades.
 * The same parameters drive the live preview (a ColorMatrix) and the full-quality
 * render on the captured photo (matrix + per-channel tone curves + vignette + grain).
 */
data class Look(
    val id: String,
    val title: String,
    val saturation: Float = 1f,
    val contrast: Float = 1f,     // S-curve strength, 1 = neutral
    val exposure: Float = 0f,     // stops
    val warmth: Float = 0f,       // -1 cool .. +1 warm
    val tint: Float = 0f,         // -1 green .. +1 magenta
    val fade: Float = 0f,         // lifted blacks (matte), 0..0.2
    val mono: Boolean = false,
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),    // RGB push in shadows
    val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f), // RGB push in highlights
    val vignette: Float = 0f,     // 0..0.6
    val grain: Float = 0f,        // 0..0.1
    val swatch: Long = 0xFF888888,
) {
    val isNeutral: Boolean
        get() = saturation == 1f && contrast == 1f && exposure == 0f && warmth == 0f && tint == 0f &&
            fade == 0f && !mono && shadowTint.all { it == 0f } && highlightTint.all { it == 0f } &&
            vignette == 0f && grain == 0f

    /** Fast approximation used on the live viewfinder. */
    fun previewMatrix(): ColorMatrix {
        val m = ColorMatrix()
        m.postConcat(ColorMatrix().apply { setSaturation(if (mono) 0f else saturation) })
        val gain = 2f.pow(exposure)
        val wR = 1f + warmth * 0.12f
        val wB = 1f - warmth * 0.12f
        val tG = 1f - tint * 0.08f
        m.postConcat(ColorMatrix().apply { setScale(wR * gain, tG * gain, wB * gain, 1f) })
        val c = 1f + (contrast - 1f) * 0.9f
        val off = 128f * (1f - c)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, off,
                    0f, c, 0f, 0f, off,
                    0f, 0f, c, 0f, off,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        if (fade > 0f) {
            val s = 1f - fade
            val o = 255f * fade
            m.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        s, 0f, 0f, 0f, o + shadowTint[0] * 255f * 0.5f,
                        0f, s, 0f, 0f, o + shadowTint[1] * 255f * 0.5f,
                        0f, 0f, s, 0f, o + shadowTint[2] * 255f * 0.5f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        return m
    }

    /** 3x3 colour matrix (row-major) used for the full-resolution render. */
    fun colorMatrix3(extraWarmth: Float = 0f): FloatArray {
        val s = if (mono) 0f else saturation
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val sat = floatArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s),
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s),
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s,
        )
        val w = warmth + extraWarmth
        val gain = 2f.pow(exposure)
        val rs = (1f + w * 0.10f) * gain
        val gs = (1f - tint * 0.06f) * gain
        val bs = (1f - w * 0.10f) * gain
        return floatArrayOf(
            sat[0] * rs, sat[1] * rs, sat[2] * rs,
            sat[3] * gs, sat[4] * gs, sat[5] * gs,
            sat[6] * bs, sat[7] * bs, sat[8] * bs,
        )
    }

    /** Per-channel tone curves (contrast S-curve, matte fade, split toning, soft highlight roll-off). */
    fun toneCurves(): Array<IntArray> = Array(3) { ch ->
        IntArray(256) { i ->
            var x = i / 255f
            // Filmic S-curve around mid grey
            if (contrast != 1f) {
                val k = 5f
                val lo = sigmoid(0f, k); val hi = sigmoid(1f, k)
                val curved = (sigmoid(x, k) - lo) / (hi - lo)
                val amount = max(-1f, min(1.5f, (contrast - 1f) * 2.5f))
                x += (curved - x) * amount
            }
            // Split toning: shadows weight (1-x)^2, highlights x^2
            val sh = (1f - x) * (1f - x)
            val hl = x * x
            x += shadowTint[ch] * sh * 0.35f + highlightTint[ch] * hl * 0.35f
            // Matte fade
            x = fade + x * (1f - fade)
            // Gentle highlight roll-off so nothing clips harshly
            if (x > 0.85f) x = 0.85f + (1f - exp(-(x - 0.85f) * 6.6f)) * 0.15f
            (max(0f, min(1f, x)) * 255f + 0.5f).toInt()
        }
    }

    private fun sigmoid(v: Float, k: Float) = 1f / (1f + exp(-k * (v - 0.5f)))

    override fun equals(other: Any?) = other is Look && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        val ORIGINAL = Look("original", "Original", swatch = 0xFF9EA3AD)

        val ALL: List<Look> = listOf(
            ORIGINAL,
            Look(
                "leica", "Leica Classic", saturation = 0.92f, contrast = 1.18f, warmth = 0.12f,
                vignette = 0.28f, highlightTint = floatArrayOf(0.03f, 0.01f, -0.02f), swatch = 0xFFB9472F,
            ),
            Look(
                "portra", "Portra 400", saturation = 0.9f, contrast = 0.94f, warmth = 0.3f, fade = 0.04f,
                shadowTint = floatArrayOf(-0.02f, 0.01f, 0.04f), highlightTint = floatArrayOf(0.05f, 0.02f, -0.03f),
                grain = 0.02f, swatch = 0xFFE2A77F,
            ),
            Look(
                "velvia", "Velvia 50", saturation = 1.35f, contrast = 1.15f, warmth = 0.05f,
                vignette = 0.12f, swatch = 0xFF2E9E6A,
            ),
            Look(
                "cine", "Cinematic", saturation = 0.95f, contrast = 1.12f,
                shadowTint = floatArrayOf(-0.07f, 0.02f, 0.09f), highlightTint = floatArrayOf(0.08f, 0.03f, -0.07f),
                vignette = 0.3f, swatch = 0xFF2F7F86,
            ),
            Look(
                "noir", "Noir", mono = true, contrast = 1.32f, vignette = 0.38f, grain = 0.05f,
                swatch = 0xFF2B2B2B,
            ),
            Look(
                "golden", "Golden Hour", saturation = 1.1f, contrast = 1.05f, warmth = 0.75f,
                highlightTint = floatArrayOf(0.06f, 0.03f, -0.05f), vignette = 0.18f, swatch = 0xFFF0A640,
            ),
            Look(
                "matte", "Matte Mood", saturation = 0.8f, contrast = 0.9f, warmth = -0.2f, fade = 0.09f,
                shadowTint = floatArrayOf(-0.02f, 0.0f, 0.05f), vignette = 0.2f, swatch = 0xFF6C7A89,
            ),
            Look(
                "food", "Food Fresh", saturation = 1.18f, contrast = 1.06f, exposure = 0.15f, warmth = 0.22f,
                highlightTint = floatArrayOf(0.02f, 0.02f, 0f), swatch = 0xFFE8603C,
            ),
            Look(
                "nature", "Vivid Nature", saturation = 1.25f, contrast = 1.1f, warmth = -0.05f,
                shadowTint = floatArrayOf(0f, 0.02f, 0.03f), vignette = 0.1f, swatch = 0xFF4BA33C,
            ),
            Look(
                "neon", "Night City", saturation = 1.12f, contrast = 1.16f, warmth = -0.35f, tint = 0.2f,
                shadowTint = floatArrayOf(0.02f, -0.02f, 0.08f), highlightTint = floatArrayOf(0.06f, 0f, 0.04f),
                vignette = 0.22f, swatch = 0xFF6E3CC8,
            ),
            Look(
                "silver", "Silver", mono = true, contrast = 1.02f, fade = 0.06f, grain = 0.03f,
                swatch = 0xFFBDBDBD,
            ),
        )

        fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
