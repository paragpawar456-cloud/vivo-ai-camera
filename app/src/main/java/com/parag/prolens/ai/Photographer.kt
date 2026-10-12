package com.parag.prolens.ai

import com.parag.prolens.camera.CameraCaps
import com.parag.prolens.camera.WhiteBalance
import com.parag.prolens.processing.Look
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Priority { SHUTTER, LOW_ISO }
enum class FocusPreset { CONTINUOUS, INFINITY, CLOSE }

/**
 * A complete shooting decision, the way a professional would think about a scene:
 * which exposure variable matters most, how far to push ISO, where to focus,
 * which focal length flatters the subject, how much background blur, which colour grade,
 * and which camera angle works.
 */
data class Recipe(
    val scene: Scene,
    val headline: String,
    val priority: Priority,
    val shutterNs: Long,
    val slowestNs: Long,
    val maxIso: Int,
    val evBias: Float,
    val wb: WhiteBalance,
    val warmth: Float,
    val focus: FocusPreset,
    val idealFocalMm: Float?,
    val virtualAperture: Float?,
    val look: Look,
    val idealPitch: List<ClosedFloatingPointRange<Float>>,
    val angleHint: String?,
    val tips: List<String>,
)

private const val S = 1_000_000_000L

object Photographer {

    fun recipeFor(scene: Scene): Recipe = when (scene) {
        Scene.GENERAL -> Recipe(
            scene, "Balanced exposure, true colour", Priority.SHUTTER,
            shutterNs = S / 125, slowestNs = S / 30, maxIso = 1600, evBias = 0f,
            wb = WhiteBalance.AUTO, warmth = 0f, focus = FocusPreset.CONTINUOUS, idealFocalMm = null,
            virtualAperture = null, look = Look.ORIGINAL,
            idealPitch = listOf(-12f..12f), angleHint = "Hold level for a clean frame",
            tips = listOf(
                "Tap your subject to set focus and exposure",
                "Place the subject on a thirds line, not dead centre",
            ),
        )
        Scene.PORTRAIT -> Recipe(
            scene, "85mm f/1.8 portrait look", Priority.SHUTTER,
            shutterNs = S / 200, slowestNs = S / 60, maxIso = 1600, evBias = 0.3f,
            wb = WhiteBalance.AUTO, warmth = 0.12f, focus = FocusPreset.CONTINUOUS, idealFocalMm = 85f,
            virtualAperture = 1.8f, look = Look.byId("portra"),
            idealPitch = listOf(-8f..12f), angleHint = "Shoot from their eye level",
            tips = listOf(
                "Step back and use the 85mm lens: faces look natural, background melts",
                "Turn the subject slightly towards the light source",
                "Keep a little space in front of the face",
            ),
        )
        Scene.FOOD -> Recipe(
            scene, "Fresh, appetising, soft background", Priority.LOW_ISO,
            shutterNs = S / 125, slowestNs = S / 30, maxIso = 800, evBias = 0.3f,
            wb = WhiteBalance.AUTO, warmth = 0.18f, focus = FocusPreset.CONTINUOUS, idealFocalMm = 50f,
            virtualAperture = 2.8f, look = Look.byId("food"),
            idealPitch = listOf(35f..55f, 80f..95f),
            angleHint = "45° for burgers & drinks, 90° overhead for plates",
            tips = listOf(
                "Use window light from the side or behind the dish",
                "50mm avoids the wide-angle distortion that makes plates look odd",
                "Tap the hero part of the dish to focus there",
            ),
        )
        Scene.LANDSCAPE -> Recipe(
            scene, "f/11 depth, protected highlights", Priority.LOW_ISO,
            shutterNs = S / 250, slowestNs = S / 30, maxIso = 400, evBias = -0.3f,
            wb = WhiteBalance.DAYLIGHT, warmth = 0f, focus = FocusPreset.INFINITY, idealFocalMm = null,
            virtualAperture = null, look = Look.byId("nature"),
            idealPitch = listOf(-6f..6f), angleHint = "Keep the horizon perfectly level",
            tips = listOf(
                "Put the horizon on the lower third when the sky is dramatic",
                "Include a foreground object (rock, path, tree) for depth",
                "Base ISO keeps skies clean and noise-free",
            ),
        )
        Scene.SUNSET -> Recipe(
            scene, "Rich golden sky, deep silhouettes", Priority.LOW_ISO,
            shutterNs = S / 250, slowestNs = S / 30, maxIso = 400, evBias = -0.7f,
            wb = WhiteBalance.CLOUDY, warmth = 0.15f, focus = FocusPreset.INFINITY, idealFocalMm = null,
            virtualAperture = null, look = Look.byId("golden"),
            idealPitch = listOf(-6f..6f), angleHint = "Level horizon, sun off-centre",
            tips = listOf(
                "Exposed for the sky: let the foreground fall into silhouette",
                "Try the telephoto lens to make the sun look bigger",
            ),
        )
        Scene.NIGHT -> Recipe(
            scene, "Handheld night, clean shadows", Priority.LOW_ISO,
            shutterNs = S / 30, slowestNs = S / 8, maxIso = 3200, evBias = -0.5f,
            wb = WhiteBalance.AUTO, warmth = 0f, focus = FocusPreset.CONTINUOUS, idealFocalMm = null,
            virtualAperture = null, look = Look.ORIGINAL,
            idealPitch = listOf(-12f..12f), angleHint = "Brace your elbows and hold still",
            tips = listOf(
                "Use the 3s timer so pressing the shutter doesn't shake the phone",
                "Lean on a wall or rest the phone on something solid",
                "Darker than daylight on purpose: keeps lights from blowing out",
            ),
        )
        Scene.MACRO -> Recipe(
            scene, "Close-up detail, creamy falloff", Priority.LOW_ISO,
            shutterNs = S / 250, slowestNs = S / 60, maxIso = 800, evBias = 0f,
            wb = WhiteBalance.AUTO, warmth = 0f, focus = FocusPreset.CLOSE, idealFocalMm = null,
            virtualAperture = 2.8f, look = Look.byId("velvia"),
            idealPitch = listOf(-30f..60f), angleHint = "Get parallel to the subject's surface",
            tips = listOf(
                "Move the phone slowly closer until the detail snaps sharp",
                "Side light reveals texture in petals and surfaces",
                "1/60s or faster: magnification exaggerates shake",
            ),
        )
        Scene.PET -> Recipe(
            scene, "Fast shutter, warm fur, soft background", Priority.SHUTTER,
            shutterNs = S / 500, slowestNs = S / 125, maxIso = 3200, evBias = 0.3f,
            wb = WhiteBalance.AUTO, warmth = 0.1f, focus = FocusPreset.CONTINUOUS, idealFocalMm = 70f,
            virtualAperture = 2.0f, look = Look.ORIGINAL,
            idealPitch = listOf(-10f..15f), angleHint = "Get down to their eye level",
            tips = listOf(
                "1/500s freezes a turning head or wagging tail",
                "Make a sound just before you shoot to catch their attention",
            ),
        )
        Scene.ARCHITECTURE -> Recipe(
            scene, "Straight lines, crisp detail", Priority.LOW_ISO,
            shutterNs = S / 250, slowestNs = S / 30, maxIso = 800, evBias = -0.3f,
            wb = WhiteBalance.AUTO, warmth = 0f, focus = FocusPreset.INFINITY, idealFocalMm = null,
            virtualAperture = null, look = Look.byId("leica"),
            idealPitch = listOf(-4f..4f), angleHint = "Keep the phone vertical so walls don't lean",
            tips = listOf(
                "Use the grid to keep vertical lines parallel",
                "Step back and use the telephoto lens instead of tilting up",
            ),
        )
        Scene.ACTION -> Recipe(
            scene, "1/1000s to freeze motion", Priority.SHUTTER,
            shutterNs = S / 1000, slowestNs = S / 500, maxIso = 6400, evBias = 0f,
            wb = WhiteBalance.AUTO, warmth = 0f, focus = FocusPreset.CONTINUOUS, idealFocalMm = null,
            virtualAperture = null, look = Look.byId("velvia"),
            idealPitch = listOf(-15f..15f), angleHint = "Pan smoothly with the subject",
            tips = listOf(
                "Pre-focus by tapping where the action will happen",
                "Leave room in the frame in the direction of movement",
            ),
        )
    }

    /**
     * Split a required exposure (ISO x seconds) between ISO and shutter the way the recipe prefers.
     * Returns (iso, exposureTimeNs).
     */
    fun solveExposure(isoSeconds: Double, r: Recipe, caps: CameraCaps): Pair<Int, Long> {
        val minIso = caps.minIso.toDouble()
        val maxIso = min(r.maxIso, caps.maxIso).toDouble().coerceAtLeast(minIso)
        val minT = caps.exposureRangeNs.lower / 1e9
        val maxT = min(r.slowestNs.toDouble() / 1e9, caps.exposureRangeNs.upper / 1e9)
        var iso: Double
        var t: Double
        when (r.priority) {
            Priority.SHUTTER -> {
                t = r.shutterNs / 1e9
                iso = isoSeconds / t
                if (iso > maxIso) {
                    iso = maxIso
                    t = min(isoSeconds / iso, maxT)
                } else if (iso < minIso) {
                    iso = minIso
                    t = isoSeconds / iso
                }
            }
            Priority.LOW_ISO -> {
                iso = minIso
                t = isoSeconds / iso
                if (t > maxT) {
                    t = maxT
                    iso = min(isoSeconds / t, maxIso)
                }
            }
        }
        t = t.coerceIn(minT, max(minT, caps.exposureRangeNs.upper / 1e9))
        iso = iso.coerceIn(minIso, maxIso)
        return iso.roundToInt() to (t * 1e9).toLong()
    }

    /** Focal-length zoom ratio that gives the recipe's ideal "full-frame equivalent" lens. */
    fun idealZoom(r: Recipe, caps: CameraCaps, maxZoom: Float): Float? {
        val target = r.idealFocalMm ?: return null
        return (target / caps.equivalentFocalMm).coerceIn(1f, maxZoom)
    }
}
