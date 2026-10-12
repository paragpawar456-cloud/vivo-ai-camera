package com.parag.prolens.ai

import android.graphics.RectF
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/** Light statistics of one viewfinder frame. */
class FrameStats(
    val luma: Float,             // centre-weighted mean brightness 0..1
    val highlightClip: Float,    // fraction of nearly-white pixels
    val shadowClip: Float,       // fraction of nearly-black pixels
    val warmth: Float,           // >0 = red/orange cast (sunsets), <0 = blue
    val histogram: IntArray,     // 64 bins
)

/** What the vision models saw. Face rects are normalised to the upright frame. */
class Vision(
    val labels: List<Pair<String, Float>>,
    val faces: List<RectF>,
    val faceCenterSensor: Pair<Float, Float>?, // largest face centre in un-rotated analysis coords, 0..1
)

/**
 * Runs on every analysis frame: cheap light metering always, ML Kit labelling + face detection
 * a couple of times a second. Everything runs on-device.
 */
class SceneAnalyzer(
    private val onStats: (FrameStats) -> Unit,
    private val onVision: (Vision) -> Unit,
) : ImageAnalysis.Analyzer {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )
    private var lastVisionAt = 0L
    private var lastStatsAt = 0L
    @Volatile var visionEnabled = true

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAt >= 120) {
            lastStatsAt = now
            try {
                onStats(measure(image))
            } catch (_: Exception) {
            }
        }

        val media = image.image
        if (!visionEnabled || media == null || now - lastVisionAt < 450) {
            image.close()
            return
        }
        lastVisionAt = now
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val uprightW = if (rotation % 180 == 0) image.width else image.height
        val uprightH = if (rotation % 180 == 0) image.height else image.width

        val labelTask = labeler.process(input)
        val faceTask = faceDetector.process(input)
        Tasks.whenAllComplete(labelTask, faceTask).addOnCompleteListener {
            try {
                val labels = if (labelTask.isSuccessful) {
                    labelTask.result.map { it.text to it.confidence }
                } else emptyList()
                val faces = if (faceTask.isSuccessful) {
                    faceTask.result.map {
                        val b = it.boundingBox
                        RectF(
                            b.left / uprightW.toFloat(), b.top / uprightH.toFloat(),
                            b.right / uprightW.toFloat(), b.bottom / uprightH.toFloat(),
                        )
                    }
                } else emptyList()
                val biggest = faces.maxByOrNull { it.width() * it.height() }
                val sensorCenter = biggest?.let { toSensor(it.centerX(), it.centerY(), rotation) }
                onVision(Vision(labels, faces, sensorCenter))
            } finally {
                image.close()
            }
        }
    }

    /** Upright normalised point -> un-rotated analysis-buffer normalised point. */
    private fun toSensor(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }

    private fun measure(image: ImageProxy): FrameStats {
        val yPlane = image.planes[0]
        val buf = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixStride = yPlane.pixelStride
        val w = image.width
        val h = image.height
        val hist = IntArray(64)
        var sum = 0.0
        var weightSum = 0.0
        var bright = 0
        var dark = 0
        var count = 0
        val step = 4
        val cx = w / 2f
        val cy = h / 2f
        val maxR2 = cx * cx + cy * cy
        var y = 0
        while (y < h) {
            var x = 0
            val row = y * rowStride
            while (x < w) {
                val l = buf.get(row + x * pixStride).toInt() and 0xFF
                hist[l shr 2]++
                val dx = x - cx
                val dy = y - cy
                // Centre-weighted metering like a DSLR: centre counts ~3x more than corners
                val wgt = 1.0 + 2.0 * (1.0 - (dx * dx + dy * dy) / maxR2)
                sum += l * wgt
                weightSum += wgt
                if (l > 250) bright++
                if (l < 6) dark++
                count++
                x += step
            }
            y += step
        }

        // Colour cast from the chroma planes (V = Cr, U = Cb)
        var warmth = 0f
        if (image.planes.size >= 3) {
            val u = image.planes[1]
            val v = image.planes[2]
            var cr = 0L
            var cb = 0L
            var n = 0
            val cw = w / 2
            val ch = h / 2
            var yy = 0
            while (yy < ch) {
                var xx = 0
                while (xx < cw) {
                    cr += (v.buffer.get(yy * v.rowStride + xx * v.pixelStride).toInt() and 0xFF) - 128
                    cb += (u.buffer.get(yy * u.rowStride + xx * u.pixelStride).toInt() and 0xFF) - 128
                    n++
                    xx += 8
                }
                yy += 8
            }
            if (n > 0) warmth = (cr - cb).toFloat() / n
        }

        return FrameStats(
            luma = (sum / weightSum / 255.0).toFloat(),
            highlightClip = bright / count.toFloat(),
            shadowClip = dark / count.toFloat(),
            warmth = warmth,
            histogram = hist,
        )
    }

    fun close() {
        labeler.close()
        faceDetector.close()
    }
}
