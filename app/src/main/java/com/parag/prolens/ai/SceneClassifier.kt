package com.parag.prolens.ai

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw vision + light readings into a stable scene decision.
 * Scores are smoothed over time and a new scene must win clearly twice in a row,
 * so the camera doesn't flicker between styles while you frame the shot.
 */
class SceneClassifier {

    private val keywords: Map<Scene, List<String>> = mapOf(
        Scene.FOOD to listOf(
            "food", "dish", "cuisine", "dessert", "cake", "pizza", "bread", "fruit", "vegetable",
            "meal", "sushi", "noodle", "pasta", "cookie", "coffee", "juice", "drink", "burger",
            "sandwich", "salad", "soup", "rice", "curry", "chocolate", "ice cream", "tableware",
            "plate", "bowl", "cup", "bento", "breakfast", "lunch", "dinner", "snack", "cheese", "meat",
        ),
        Scene.LANDSCAPE to listOf(
            "sky", "mountain", "cloud", "lake", "river", "sea", "ocean", "beach", "waterfall",
            "forest", "field", "desert", "snow", "glacier", "canyon", "hill", "valley", "coast",
            "lighthouse", "park", "jungle", "grass", "tree", "landscape", "horizon", "rainbow",
        ),
        Scene.SUNSET to listOf("sunset", "sunrise", "dusk", "dawn", "afterglow", "twilight"),
        Scene.PORTRAIT to listOf("selfie", "smile", "beard", "eyelash", "lip", "hair", "skin", "person", "bride"),
        Scene.MACRO to listOf("flower", "petal", "insect", "butterfly", "bee", "moth", "ladybug", "leaf", "spider", "dew"),
        Scene.PET to listOf("dog", "cat", "pet", "bird", "horse", "rabbit", "hamster", "puppy", "kitten", "parrot"),
        Scene.ARCHITECTURE to listOf(
            "building", "skyscraper", "tower", "bridge", "church", "temple", "monument", "city",
            "architecture", "house", "castle", "mosque", "roof", "palace", "column", "stadium", "street",
        ),
        Scene.ACTION to listOf(
            "sport", "skateboard", "surfing", "bicycle", "motorcycle", "racing", "running",
            "football", "cricket", "basketball", "tennis", "skiing", "dance", "jump", "car",
        ),
    )

    private val scores = FloatArray(Scene.entries.size)
    private var pending: Scene? = null
    var scene: Scene = Scene.GENERAL
        private set
    var confidence: Float = 0f
        private set
    var topLabels: List<String> = emptyList()
        private set

    /**
     * @param ev100 scene brightness measured from the live auto-exposure (null if unknown)
     * @param focusDiopters current lens focus distance in diopters (1/m); large = very close
     * @return true when the chosen scene changed
     */
    fun update(
        labels: List<Pair<String, Float>>,
        faces: List<RectF>,
        ev100: Float?,
        focusDiopters: Float?,
        warmth: Float,
        luma: Float,
    ): Boolean {
        topLabels = labels.sortedByDescending { it.second }.take(4).map { it.first }
        val raw = FloatArray(Scene.entries.size)

        for ((scene, words) in keywords) {
            var s = 0f
            for ((text, conf) in labels) {
                val t = text.lowercase()
                if (words.any { t.contains(it) }) s += conf
            }
            raw[scene.ordinal] = min(1f, s)
        }
        // Trees/grass alone are weak evidence for a landscape
        raw[Scene.LANDSCAPE.ordinal] *= 0.85f

        // Faces are the strongest portrait signal
        val biggestFace = faces.maxOfOrNull { it.width() * it.height() } ?: 0f
        if (biggestFace > 0.015f) {
            raw[Scene.PORTRAIT.ordinal] = max(raw[Scene.PORTRAIT.ordinal], min(1f, 0.65f + biggestFace * 3f))
        }

        // Sunsets: warm colour cast with a sky in frame
        if (warmth > 14f && luma > 0.18f && raw[Scene.LANDSCAPE.ordinal] > 0.3f) {
            raw[Scene.SUNSET.ordinal] = max(raw[Scene.SUNSET.ordinal], 0.55f + min(0.4f, (warmth - 14f) / 30f))
        }

        // Very close focus means macro, whatever the subject
        if (focusDiopters != null && focusDiopters > 6f) {
            raw[Scene.MACRO.ordinal] = max(raw[Scene.MACRO.ordinal], 0.7f)
        } else {
            raw[Scene.MACRO.ordinal] *= 0.6f
        }

        // Night from measured light (EV100 < 4 is a dim street at night)
        val night = when {
            ev100 != null && ev100 < 2.5f -> 1f
            ev100 != null && ev100 < 4.5f -> 0.75f
            ev100 == null && luma < 0.1f -> 0.6f
            else -> 0f
        }
        raw[Scene.NIGHT.ordinal] = night
        if (night >= 0.75f) raw[Scene.LANDSCAPE.ordinal] *= 0.5f

        // Smooth over time
        for (i in scores.indices) scores[i] = scores[i] * 0.55f + raw[i] * 0.45f

        var best = Scene.GENERAL
        var bestScore = 0.38f // below this, stay in balanced Auto
        for (s in Scene.entries) {
            if (s == Scene.GENERAL) continue
            if (scores[s.ordinal] > bestScore) {
                best = s
                bestScore = scores[s.ordinal]
            }
        }
        val currentScore = if (scene == Scene.GENERAL) 0.38f else scores[scene.ordinal]

        if (best != scene && bestScore > currentScore + 0.08f) {
            if (pending == best) {
                scene = best
                confidence = bestScore
                pending = null
                return true
            }
            pending = best
        } else {
            pending = null
        }
        confidence = if (scene == Scene.GENERAL) 1f - bestScore else scores[scene.ordinal]
        return false
    }

    fun reset() {
        scores.fill(0f)
        scene = Scene.GENERAL
        pending = null
    }
}
