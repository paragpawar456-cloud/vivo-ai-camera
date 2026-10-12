package com.parag.prolens.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Everything the darkroom needs to finish a photo. */
data class RenderParams(
    val look: Look,
    val extraWarmth: Float,
    val virtualAperture: Float?,          // simulated f-number for background blur; null = off
    val subjectIsPerson: Boolean,         // use person segmentation for the depth mask
    val focusPoint: Pair<Float, Float>?,  // upright normalised focus point for non-person blur
    val sceneName: String,
    val settingsNote: String,             // e.g. "ISO 100 · 1/250 · EV -0.3"
)

class SavedPhoto(val uri: Uri, val thumbnail: Bitmap?)

/**
 * The "darkroom": takes the full-resolution JPEG from the sensor and applies the
 * colour grade, simulated aperture (background blur) and finishing touches, then saves
 * to the gallery (DCIM/ProLens AI). Untouched captures are saved byte-for-byte.
 */
class PhotoPipeline(private val context: Context) {

    suspend fun finish(input: File, p: RenderParams): SavedPhoto = withContext(Dispatchers.Default) {
        val exif = ExifInterface(input.absolutePath)
        val rotation = exif.rotationDegrees
        val flipped = exif.isFlipped
        val needsRender = !p.look.isNeutral || p.extraWarmth != 0f || p.virtualAperture != null

        val outFile = if (needsRender) {
            try {
                render(input, p, rotation, flipped)
            } catch (e: OutOfMemoryError) {
                System.gc()
                input // fall back to the untouched photo rather than lose the shot
            }
        } else input

        // Tag the file like a pro camera would
        val outExif = ExifInterface(outFile.absolutePath)
        if (outFile != input) {
            for (tag in COPY_TAGS) exif.getAttribute(tag)?.let { outExif.setAttribute(tag, it) }
        }
        outExif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProLens AI")
        outExif.setAttribute(
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            "${p.sceneName} · ${p.look.title} · ${p.settingsNote}" +
                (p.virtualAperture?.let { " · bokeh f/$it" } ?: ""),
        )
        outExif.saveAttributes()

        val uri = saveToGallery(outFile)
        val thumb = thumbnail(outFile)
        if (outFile != input) outFile.delete()
        input.delete()
        SavedPhoto(uri, thumb)
    }

    private suspend fun render(input: File, p: RenderParams, rotation: Int, flipped: Boolean): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2

        val full = decodeMutable(input, sample)
        val w = full.width
        val h = full.height

        // ---- Depth-of-field (simulated aperture) ----
        var blur: Bitmap? = null
        var mask: FloatArray? = null
        var maskW = 0
        var maskH = 0
        val fNumber = p.virtualAperture
        if (fNumber != null) {
            val scale = 960f / max(w, h)
            val small = Bitmap.createScaledBitmap(full, max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), true)
            blur = bokehBlur(small, fNumber)

            // Upright copy of the small image for the segmentation model
            val upright = uprightCopy(small, rotation, flipped)
            maskW = upright.width
            maskH = upright.height
            mask = if (p.subjectIsPerson) personMask(upright) else null
            if (mask == null || mask.average() < 0.02) {
                mask = radialMask(maskW, maskH, p.focusPoint ?: (0.5f to 0.5f))
            }
            mask = featherMask(mask, maskW, maskH, 2)
            upright.recycle()
            small.recycle()
        }

        // ---- Colour grade + DOF composite, in parallel strips ----
        val m = p.look.colorMatrix3(p.extraWarmth)
        val curves = p.look.toneCurves()
        val cR = curves[0]; val cG = curves[1]; val cB = curves[2]
        val vig = p.look.vignette
        val grain = p.look.grain * 255f
        val cx = w / 2f
        val cy = h / 2f
        val invR2 = 1f / (cx * cx + cy * cy)
        val bw = blur?.width ?: 0
        val bh = blur?.height ?: 0
        val blurPx = blur?.let { b -> IntArray(bw * bh).also { b.getPixels(it, 0, bw, 0, 0, bw, bh) } }
        val bsx = if (bw > 0) (bw - 1f) / (w - 1f) else 0f
        val bsy = if (bh > 0) (bh - 1f) / (h - 1f) else 0f

        val depth = mask
        val dW = maskW
        val dH = maskH
        val strips = Runtime.getRuntime().availableProcessors().coerceIn(2, 8) * 4
        val rowsPer = (h + strips - 1) / strips
        coroutineScope {
            (0 until strips).map { s ->
                async(Dispatchers.Default) {
                    val y0 = s * rowsPer
                    val y1 = min(h, y0 + rowsPer)
                    if (y0 >= y1) return@async
                    val row = IntArray(w)
                    for (y in y0 until y1) {
                        full.getPixels(row, 0, w, 0, y, w, 1)
                        val ny = y / (h - 1f)
                        val dy = y - cy
                        for (x in 0 until w) {
                            val px = row[x]
                            var r = ((px shr 16) and 0xFF).toFloat()
                            var g = ((px shr 8) and 0xFF).toFloat()
                            var b = (px and 0xFF).toFloat()

                            if (blurPx != null && depth != null) {
                                val nx = x / (w - 1f)
                                // map buffer coords -> upright mask coords
                                var u: Float
                                val v: Float
                                when (rotation) {
                                    90 -> { u = 1f - ny; v = nx }
                                    180 -> { u = 1f - nx; v = 1f - ny }
                                    270 -> { u = ny; v = 1f - nx }
                                    else -> { u = nx; v = ny }
                                }
                                if (flipped) u = 1f - u
                                val sharpness = sampleMask(depth, dW, dH, u, v)
                                if (sharpness < 0.995f) {
                                    val bp = sampleRgb(blurPx, bw, bh, x * bsx, y * bsy)
                                    val k = 1f - sharpness
                                    r += (((bp shr 16) and 0xFF) - r) * k
                                    g += (((bp shr 8) and 0xFF) - g) * k
                                    b += ((bp and 0xFF) - b) * k
                                }
                            }

                            val nr = m[0] * r + m[1] * g + m[2] * b
                            val ng = m[3] * r + m[4] * g + m[5] * b
                            val nb = m[6] * r + m[7] * g + m[8] * b
                            var ir = cR[clamp255(nr)]
                            var ig = cG[clamp255(ng)]
                            var ib = cB[clamp255(nb)]

                            if (vig > 0f) {
                                val dx = x - cx
                                val d = (dx * dx + dy * dy) * invR2
                                val f = 1f - vig * d * d * 1.6f
                                ir = (ir * f).toInt(); ig = (ig * f).toInt(); ib = (ib * f).toInt()
                            }
                            if (grain > 0f) {
                                var hsh = x * 374761393 + y * 668265263
                                hsh = (hsh xor (hsh ushr 13)) * 1274126177
                                val n = (((hsh ushr 16) and 0xFF) - 128) / 128f * grain
                                ir = (ir + n).toInt(); ig = (ig + n).toInt(); ib = (ib + n).toInt()
                            }
                            row[x] = (0xFF shl 24) or (clampI(ir) shl 16) or (clampI(ig) shl 8) or clampI(ib)
                        }
                        full.setPixels(row, 0, w, 0, y, w, 1)
                    }
                }
            }.awaitAll()
        }
        blur?.recycle()

        val out = File(context.cacheDir, "render_${System.nanoTime()}.jpg")
        FileOutputStream(out).use { full.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        full.recycle()
        return out
    }

    private fun decodeMutable(file: File, startSample: Int): Bitmap {
        var sample = startSample
        while (true) {
            try {
                val o = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                return BitmapFactory.decodeFile(file.absolutePath, o)
                    ?: throw IllegalStateException("Could not decode photo")
            } catch (e: OutOfMemoryError) {
                if (sample >= 8) throw e
                System.gc()
                sample *= 2
            }
        }
    }

    // ---------------- Depth helpers ----------------

    /** Lens-like blur: highlights bloom into bokeh balls, strength follows the f-number. */
    private fun bokehBlur(src: Bitmap, fNumber: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            var rr = ((c shr 16) and 0xFF) / 255f
            var gg = ((c shr 8) and 0xFF) / 255f
            var bb = (c and 0xFF) / 255f
            val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
            if (l > 0.75f) { // specular highlights become bright discs, like real glass
                val boost = 1f + (l - 0.75f) * 4f
                rr *= boost; gg *= boost; bb *= boost
            }
            r[i] = rr; g[i] = gg; b[i] = bb
        }
        // f/1.2 ≈ 1.8% of the long edge, f/16 ≈ almost nothing
        val radius = max(1, (max(w, h) * 0.022f * (1.4f / fNumber)).toInt())
        repeat(3) { // three box passes ≈ gaussian
            boxBlur(r, w, h, radius); boxBlur(g, w, h, radius); boxBlur(b, w, h, radius)
        }
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or (clampF(r[i]) shl 16) or (clampF(g[i]) shl 8) or clampF(b[i])
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(ch: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(max(w, h))
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val o = y * w
            var acc = 0f
            for (i in -r..r) acc += ch[o + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = acc * inv
                acc += ch[o + (x + r + 1).coerceAtMost(w - 1)] - ch[o + (x - r).coerceAtLeast(0)]
            }
            System.arraycopy(tmp, 0, ch, o, w)
        }
        for (x in 0 until w) {
            var acc = 0f
            for (i in -r..r) acc += ch[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = acc * inv
                acc += ch[(y + r + 1).coerceAtMost(h - 1) * w + x] - ch[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) ch[y * w + x] = tmp[y]
        }
    }

    private fun uprightCopy(src: Bitmap, rotation: Int, flipped: Boolean): Bitmap {
        if (rotation == 0 && !flipped) return src.copy(Bitmap.Config.ARGB_8888, false)
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        if (flipped) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** 1 = person (keep sharp), 0 = background. */
    private suspend fun personMask(upright: Bitmap): FloatArray? = try {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
        val result = segmenter.process(InputImage.fromBitmap(upright, 0)).await()
        segmenter.close()
        val mw = result.width
        val mh = result.height
        val buf = result.buffer
        buf.rewind()
        val raw = FloatArray(mw * mh) { buf.float }
        if (mw == upright.width && mh == upright.height) raw
        else FloatArray(upright.width * upright.height) { i ->
            val x = i % upright.width
            val y = i / upright.width
            raw[(y * mh / upright.height) * mw + (x * mw / upright.width)]
        }
    } catch (e: Exception) {
        null
    }

    /** Elliptical in-focus zone around the focus point, falling off smoothly like a real lens. */
    private fun radialMask(w: Int, h: Int, focus: Pair<Float, Float>): FloatArray {
        val (fx, fy) = focus
        val aspect = w.toFloat() / h
        return FloatArray(w * h) { i ->
            val x = (i % w) / w.toFloat()
            val y = (i / w) / h.toFloat()
            val dx = (x - fx) * aspect
            val dy = (y - fy)
            val d = sqrt(dx * dx + dy * dy)
            val t = ((d - 0.16f) / 0.30f).coerceIn(0f, 1f)
            1f - t * t * (3 - 2 * t)
        }
    }

    private fun featherMask(mask: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = mask.copyOf()
        boxBlur(out, w, h, r)
        return out
    }

    private fun sampleMask(mask: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val fx = (u * (w - 1)).coerceIn(0f, w - 1f)
        val fy = (v * (h - 1)).coerceIn(0f, h - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0; val ty = fy - y0
        val a = mask[y0 * w + x0] + (mask[y0 * w + x1] - mask[y0 * w + x0]) * tx
        val b = mask[y1 * w + x0] + (mask[y1 * w + x1] - mask[y1 * w + x0]) * tx
        return a + (b - a) * ty
    }

    private fun sampleRgb(px: IntArray, w: Int, h: Int, fxIn: Float, fyIn: Float): Int {
        val fx = fxIn.coerceIn(0f, w - 1f)
        val fy = fyIn.coerceIn(0f, h - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0; val ty = fy - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        fun ch(shift: Int): Int {
            val a = ((c00 shr shift) and 0xFF) + (((c10 shr shift) and 0xFF) - ((c00 shr shift) and 0xFF)) * tx
            val b = ((c01 shr shift) and 0xFF) + (((c11 shr shift) and 0xFF) - ((c01 shr shift) and 0xFF)) * tx
            return (a + (b - a) * ty).toInt()
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------------- Output ----------------

    private fun saveToGallery(file: File): Uri {
        val name = "ProLens_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ProLens AI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Gallery is not available")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun thumbnail(file: File): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, b)
        var s = 1
        while (max(b.outWidth, b.outHeight) / s > 320) s *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        val rot = ExifInterface(file.absolutePath).rotationDegrees
        if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Long-edge cap for the full-quality render (keeps memory sane on 50–200 MP sensors). */
        const val MAX_EDGE = 8192

        private val COPY_TAGS = listOf(
            ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        )

        private fun clamp255(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()
        private fun clampI(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
        private fun clampF(v: Float): Int = (v * 255f).toInt().coerceIn(0, 255)
    }
}
