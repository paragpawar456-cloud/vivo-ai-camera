package com.parag.prolens

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.concurrent.futures.await
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.parag.prolens.ai.FrameStats
import com.parag.prolens.ai.FocusPreset
import com.parag.prolens.ai.Photographer
import com.parag.prolens.ai.Recipe
import com.parag.prolens.ai.Scene
import com.parag.prolens.ai.SceneAnalyzer
import com.parag.prolens.ai.SceneClassifier
import com.parag.prolens.ai.ShootMode
import com.parag.prolens.ai.Vision
import com.parag.prolens.camera.CameraCaps
import com.parag.prolens.camera.Stops
import com.parag.prolens.camera.WhiteBalance
import com.parag.prolens.processing.Look
import com.parag.prolens.processing.PhotoPipeline
import com.parag.prolens.processing.RenderParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class FlashSetting { OFF, AUTO, ON, TORCH }
enum class GridType { OFF, THIRDS, GOLDEN, CROSS }
enum class Aspect(val label: String, val ratio: Float, val strategy: AspectRatioStrategy) {
    R4_3("4:3", 3f / 4f, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY),
    R16_9("16:9", 9f / 16f, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY),
}
enum class Panel { NONE, PRO, LOOKS, AI, BOKEH }
enum class ProDial { ISO, SHUTTER, EV, WB, FOCUS, APERTURE }

@OptIn(ExperimentalCamera2Interop::class)
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    // ---------------------------------------------------------------- UI state
    var mode by mutableStateOf(ShootMode.AI); private set
    var scene by mutableStateOf(Scene.GENERAL); private set
    var sceneConfidence by mutableFloatStateOf(0f); private set
    var recipe by mutableStateOf(Photographer.recipeFor(Scene.GENERAL)); private set
    var detectedLabels by mutableStateOf<List<String>>(emptyList()); private set
    var autoApply by mutableStateOf(true); private set

    var look by mutableStateOf(Look.ORIGINAL); private set
    var lookLocked by mutableStateOf(false); private set
    var virtualAperture by mutableStateOf<Float?>(null); private set
    var apertureLocked by mutableStateOf(false); private set
    var userEv by mutableFloatStateOf(0f); private set

    // Pro (manual) controls: null = auto
    var proIso by mutableStateOf<Int?>(null); private set
    var proShutterNs by mutableStateOf<Long?>(null); private set
    var proWb by mutableStateOf(WhiteBalance.AUTO); private set
    var proFocus by mutableStateOf<Float?>(null); private set
    var proAperture by mutableStateOf<Float?>(null); private set
    var proDial by mutableStateOf(ProDial.ISO)

    // Live readouts
    var liveIso by mutableIntStateOf(0); private set
    var liveShutterNs by mutableStateOf(0L); private set
    var liveFocus by mutableStateOf<Float?>(null); private set
    var liveAperture by mutableFloatStateOf(1.8f); private set
    var luma by mutableFloatStateOf(0.45f); private set
    var meterStops by mutableFloatStateOf(0f); private set
    var histogram by mutableStateOf(IntArray(64)); private set
    var faces by mutableStateOf<List<RectF>>(emptyList()); private set
    var manualExposureActive by mutableStateOf(false); private set

    // Camera
    var caps by mutableStateOf(CameraCaps()); private set
    var zoom by mutableFloatStateOf(1f); private set
    var minZoom by mutableFloatStateOf(1f); private set
    var maxZoom by mutableFloatStateOf(1f); private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK); private set
    var aspect by mutableStateOf(Aspect.R4_3); private set
    var flash by mutableStateOf(FlashSetting.OFF); private set
    var timer by mutableIntStateOf(0); private set
    var grid by mutableStateOf(GridType.THIRDS); private set
    var showHistogram by mutableStateOf(true); private set
    var showLevel by mutableStateOf(true); private set
    var vendorAvailable by mutableStateOf(false); private set
    var vendorOn by mutableStateOf(false); private set

    // Motion
    var roll by mutableFloatStateOf(0f); private set
    var pitch by mutableFloatStateOf(0f); private set
    var quarterTurns by mutableIntStateOf(0); private set

    // Capture
    var panel by mutableStateOf(Panel.NONE)
    var countdown by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var shutterBlink by mutableIntStateOf(0); private set
    var lastThumb by mutableStateOf<ImageBitmap?>(null); private set
    var lastUri by mutableStateOf<Uri?>(null); private set
    var focusRing by mutableStateOf<Pair<Offset, Long>?>(null); private set
    var toast by mutableStateOf<Pair<String, Long>?>(null); private set

    // ---------------------------------------------------------------- engine
    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val classifier = SceneClassifier()
    private val pipeline = PhotoPipeline(app)
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private var analyzer: SceneAnalyzer? = null

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    // auto-exposure readings from the camera (camera thread)
    @Volatile private var aeIso = 100
    @Volatile private var aeExposureNs = 16_000_000L
    @Volatile private var aeFocus: Float? = null
    @Volatile private var aeAperture = 1.8f
    private var lastMetaPush = 0L

    // closed-loop exposure state (ISO x seconds needed for a correct exposure)
    private var isoSeconds = 100.0 / 60.0
    private var appliedIso = 0
    private var appliedShutter = 0L
    private var lastTapAt = 0L
    private var lastFaceMeterAt = 0L
    private var userZoomed = false

    fun attach(owner: LifecycleOwner, view: PreviewView) {
        if (this.owner === owner && previewView === view) return
        this.owner = owner
        previewView = view
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            provider = provider ?: ProcessCameraProvider.getInstance(ctx).await()
            extensions = extensions ?: try {
                ExtensionsManager.getInstanceAsync(ctx, provider!!).await()
            } catch (e: Exception) {
                null
            }
            bind()
        }
    }

    private fun vendorModeFor(m: ShootMode): Int? = when (m) {
        ShootMode.PORTRAIT -> ExtensionMode.BOKEH
        ShootMode.NIGHT -> ExtensionMode.NIGHT
        ShootMode.LANDSCAPE -> ExtensionMode.HDR
        else -> null
    }

    @SuppressLint("RestrictedApi")
    private fun bind() {
        val provider = provider ?: return
        val owner = owner ?: return
        val view = previewView ?: return

        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val ext = extensions
        val vMode = vendorModeFor(mode)
        vendorAvailable = vMode != null && ext != null && try {
            ext.isExtensionAvailable(base, vMode)
        } catch (e: Exception) {
            false
        }
        val useVendor = vendorOn && vendorAvailable
        val selector = if (useVendor) ext!!.getExtensionEnabledCameraSelector(base, vMode!!) else base

        val aspectSelector = ResolutionSelector.Builder().setAspectRatioStrategy(aspect.strategy).build()

        val previewBuilder = Preview.Builder().setResolutionSelector(aspectSelector)
        if (!useVendor) {
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(metaCallback)
        }
        val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect.strategy)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .setFlashMode(flashMode())
            .setJpegQuality(98)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect.strategy)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()
        analyzer?.close()
        val a = SceneAnalyzer(onStats = ::onStats, onVision = ::onVision)
        analyzer = a
        analysis.setAnalyzer(analysisExecutor, a)

        val analysisOk = !useVendor || try {
            ext!!.isImageAnalysisSupported(base, vMode!!)
        } catch (e: Exception) {
            false
        }
        val useCases = mutableListOf<UseCase>(preview, capture)
        if (analysisOk) useCases += analysis

        provider.unbindAll()
        val cam = try {
            provider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
        } catch (e: Exception) {
            // Some vendor modes refuse a 3rd stream: retry without analysis, then without vendor engine
            try {
                provider.bindToLifecycle(owner, selector, preview, capture)
            } catch (e2: Exception) {
                vendorOn = false
                provider.bindToLifecycle(owner, base, preview, capture, analysis)
            }
        }
        camera = cam
        imageCapture = capture
        imageAnalysis = if (analysisOk) analysis else null
        caps = CameraCaps.from(cam.cameraInfo)
        liveAperture = caps.baseAperture

        cam.cameraInfo.zoomState.removeObservers(owner)
        cam.cameraInfo.zoomState.observe(owner) { z ->
            zoom = z.zoomRatio
            minZoom = z.minZoomRatio
            maxZoom = z.maxZoomRatio
        }
        isoSeconds = aeIso * aeExposureNs / 1e9
        appliedIso = 0
        appliedShutter = 0L
        if (flash == FlashSetting.TORCH) cam.cameraControl.enableTorch(true)
        applyControls(force = true)
    }

    private fun flashMode() = when (flash) {
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }

    private val metaCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { aeIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { aeExposureNs = it }
            aeFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            result.get(CaptureResult.LENS_APERTURE)?.let { aeAperture = it }
            val now = SystemClock.elapsedRealtime()
            if (now - lastMetaPush > 200) {
                lastMetaPush = now
                main.post {
                    liveIso = aeIso
                    liveShutterNs = aeExposureNs
                    liveFocus = aeFocus
                    liveAperture = aeAperture
                }
            }
        }
    }

    // ---------------------------------------------------------------- AI loop

    private fun onStats(s: FrameStats) {
        main.post { handleStats(s) }
    }

    private fun handleStats(s: FrameStats) {
        luma = s.luma
        histogram = s.histogram
        val target = targetLuma()
        meterStops = (2.2 * log2((max(s.luma, 0.005f) / target).toDouble())).toFloat()
        lastWarmth = s.warmth
        lastHighlightClip = s.highlightClip
        if (manualExposureActive) runExposureLoop(s)
    }

    private var lastWarmth = 0f
    private var lastHighlightClip = 0f

    private fun onVision(v: Vision) {
        main.post { handleVision(v) }
    }

    private fun handleVision(v: Vision) {
        faces = v.faces
        // EV100 of the scene, from what the auto-exposure chose (or our manual values + meter)
        val n = aeAperture.toDouble()
        val t = max(aeExposureNs, 1L) / 1e9
        var ev100 = log2(n * n / t) - log2(aeIso / 100.0)
        if (manualExposureActive) ev100 += meterStops
        val changed = classifier.update(
            v.labels, v.faces, ev100.toFloat(), aeFocus, lastWarmth, luma,
        )
        detectedLabels = classifier.topLabels
        sceneConfidence = classifier.confidence
        if (mode == ShootMode.AI && changed) {
            onSceneDetected(classifier.scene)
        }
        // Eye/face priority AF: meter on the biggest face, like a pro body's eye-AF
        val c = v.faceCenterSensor
        val now = SystemClock.elapsedRealtime()
        val analysis = imageAnalysis
        if (c != null && analysis != null && (scene == Scene.PORTRAIT || mode == ShootMode.PORTRAIT) &&
            now - lastTapAt > 4000 && now - lastFaceMeterAt > 1500
        ) {
            lastFaceMeterAt = now
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, analysis).createPoint(c.first, c.second)
            camera?.cameraControl?.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(2, TimeUnit.SECONDS).build()
            )
        }
    }

    private fun onSceneDetected(s: Scene) {
        if (!autoApply) {
            // Suggest-only: show what we'd do, but keep balanced settings
            scene = s
            return
        }
        applyScene(s)
    }

    private fun applyScene(s: Scene) {
        scene = s
        recipe = Photographer.recipeFor(s)
        if (!lookLocked) look = recipe.look
        if (!apertureLocked) virtualAperture = recipe.virtualAperture
        applyControls(force = true)
    }

    private fun targetLuma(): Float {
        val ev = if (mode == ShootMode.PRO) userEv else activeRecipe().evBias + userEv
        return (0.46 * 2.0.pow(ev / 2.2)).toFloat().coerceIn(0.08f, 0.9f)
    }

    /** The recipe that actually drives the camera right now. */
    fun activeRecipe(): Recipe = when {
        mode == ShootMode.PRO -> Photographer.recipeFor(Scene.GENERAL)
        mode == ShootMode.AI && !autoApply -> Photographer.recipeFor(Scene.GENERAL)
        else -> recipe
    }

    private fun runExposureLoop(s: FrameStats) {
        val target = targetLuma()
        var step = (target / max(s.luma, 0.01f)).toDouble().pow(2.2).pow(0.45)
        step = step.coerceIn(0.5, 2.0)
        if (s.highlightClip > 0.03 && step > 1.0) step = 1.0
        if (s.highlightClip > 0.08) step = min(step, 0.85)
        if (abs(ln(step)) < 0.04) return
        val r = activeRecipe()
        val c = caps
        val maxProduct = min(r.maxIso, c.maxIso) * min(r.slowestNs, c.exposureRangeNs.upper) / 1e9
        val minProduct = c.minIso * c.exposureRangeNs.lower / 1e9
        isoSeconds = (isoSeconds * step).coerceIn(minProduct, max(minProduct, maxProduct))
        applyControls()
    }

    // ---------------------------------------------------------------- applying settings

    private fun applyControls(force: Boolean = false) {
        val cam = camera ?: return
        val c = caps
        val proManual = mode == ShootMode.PRO && (proIso != null || proShutterNs != null)
        val aiManual = mode != ShootMode.PRO && !(mode == ShootMode.AI && !autoApply)
        val canManual = c.manualSensor && !(vendorOn && vendorAvailable) &&
            (flash == FlashSetting.OFF || flash == FlashSetting.TORCH)
        val manual = canManual && (proManual || aiManual)

        if (manual && !manualExposureActive) {
            // seed from what auto-exposure was doing, so the switch is seamless
            isoSeconds = aeIso * aeExposureNs / 1e9
        }
        manualExposureActive = manual

        val opts = CaptureRequestOptions.Builder()
        var iso = 0
        var shutter = 0L
        if (manual) {
            val r = activeRecipe()
            when {
                mode == ShootMode.PRO && proIso != null && proShutterNs != null -> {
                    iso = proIso!!; shutter = proShutterNs!!
                }
                mode == ShootMode.PRO && proShutterNs != null -> { // shutter priority, auto ISO
                    shutter = proShutterNs!!
                    iso = (isoSeconds / (shutter / 1e9)).roundToInt().coerceIn(c.minIso, c.maxIso)
                }
                mode == ShootMode.PRO && proIso != null -> { // ISO fixed, auto shutter
                    iso = proIso!!
                    shutter = (isoSeconds / iso * 1e9).toLong()
                        .coerceIn(c.exposureRangeNs.lower, min(c.exposureRangeNs.upper, 1_000_000_000L))
                }
                else -> {
                    val (i, t) = Photographer.solveExposure(isoSeconds, r, c)
                    iso = i; shutter = t
                }
            }
            iso = iso.coerceIn(c.minIso, c.maxIso)
            shutter = shutter.coerceIn(c.exposureRangeNs.lower, c.exposureRangeNs.upper)
            if (!force && appliedIso > 0 &&
                abs(iso - appliedIso) < appliedIso * 0.03 && abs(shutter - appliedShutter) < appliedShutter * 0.03
            ) return
            appliedIso = iso
            appliedShutter = shutter
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L))
            liveIso = iso
            liveShutterNs = shutter
        }

        // White balance
        val wb = if (mode == ShootMode.PRO) proWb else activeRecipe().wb
        if (wb != WhiteBalance.AUTO && c.awbModes.contains(wb.awbMode)) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.awbMode)
        }

        // Focus
        val focusDist: Float? = when {
            mode == ShootMode.PRO -> proFocus
            activeRecipe().focus == FocusPreset.INFINITY && SystemClock.elapsedRealtime() - lastTapAt > 5000 ->
                0.4f // hyperfocal-ish: sharp from ~1.2 m to infinity
            else -> null
        }
        if (focusDist != null && c.manualFocus) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist.coerceIn(0f, c.minFocusDiopters))
        }

        // Real aperture (only on lenses that have a variable iris)
        val ap = proAperture
        if (ap != null && c.hasVariableAperture) {
            opts.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, ap)
        }

        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts.build())
        } catch (_: Exception) {
        }

        // Exposure compensation when the camera's own auto-exposure is in charge
        if (!manual) {
            val ev = if (mode == ShootMode.PRO) userEv else activeRecipe().evBias + userEv
            val idx = (ev / c.evStep).roundToInt().coerceIn(c.evRange.lower, c.evRange.upper)
            cam.cameraControl.setExposureCompensationIndex(idx)
        } else {
            cam.cameraControl.setExposureCompensationIndex(0)
        }
    }

    // ---------------------------------------------------------------- user actions

    fun selectMode(m: ShootMode) {
        if (m == mode) return
        val vendorBefore = vendorOn && vendorAvailable
        mode = m
        classifier.reset()
        when {
            m == ShootMode.AI -> applyScene(Scene.GENERAL)
            m.scene != null -> {
                applyScene(m.scene)
                Photographer.idealZoom(recipe, caps, maxZoom)?.let { if (!userZoomed) setZoom(it) }
                if (m.scene == Scene.LANDSCAPE && !userZoomed) setZoom(1f)
            }
            m == ShootMode.PRO -> {
                scene = Scene.GENERAL
                recipe = Photographer.recipeFor(Scene.GENERAL)
                if (!lookLocked) look = Look.ORIGINAL
                if (!apertureLocked) virtualAperture = null
                panel = Panel.PRO
            }
        }
        if (m != ShootMode.PRO && panel == Panel.PRO) panel = Panel.NONE
        val vendorAfterMode = vendorModeFor(m) != null
        if (vendorBefore || (vendorOn && vendorAfterMode)) bind() else {
            // availability label for the new mode
            val ext = extensions
            val vm = vendorModeFor(m)
            vendorAvailable = vm != null && ext != null && try {
                ext.isExtensionAvailable(CameraSelector.Builder().requireLensFacing(lensFacing).build(), vm)
            } catch (e: Exception) {
                false
            }
            applyControls(force = true)
        }
    }

    fun toggleAutoApply() {
        autoApply = !autoApply
        if (autoApply) applyScene(classifier.scene) else {
            recipe = Photographer.recipeFor(Scene.GENERAL)
            if (!lookLocked) look = Look.ORIGINAL
            if (!apertureLocked) virtualAperture = null
            applyControls(force = true)
        }
        say(if (autoApply) "AI will set the camera for you" else "AI will only suggest")
    }

    /** Apply a suggestion manually (suggest-only mode). */
    fun applySuggestion() {
        val s = scene
        if (s == Scene.GENERAL) return
        ShootMode.entries.firstOrNull { it.scene == s }?.let { selectMode(it) } ?: run {
            autoApply = true
            applyScene(s)
        }
    }

    fun toggleVendor() {
        vendorOn = !vendorOn
        bind()
        say(if (vendorOn) "Using Vivo's own ${mode.title} engine" else "Using ProLens engine")
    }

    fun chooseLook(l: Look?) {
        if (l == null) {
            lookLocked = false
            look = activeRecipe().look
        } else {
            lookLocked = true
            look = l
        }
    }

    fun setVirtualAperture(f: Float?) {
        apertureLocked = true
        virtualAperture = f
    }

    fun setEv(ev: Float) {
        userEv = ev
        applyControls(force = true)
    }

    fun setProIso(v: Int?) { proIso = v; applyControls(force = true) }
    fun setProShutter(v: Long?) { proShutterNs = v; applyControls(force = true) }
    fun setProWb(v: WhiteBalance) { proWb = v; applyControls(force = true) }
    fun setProFocus(v: Float?) { proFocus = v; applyControls(force = true) }
    fun setProAperture(v: Float?) { proAperture = v; applyControls(force = true) }

    fun resetPro() {
        proIso = null; proShutterNs = null; proWb = WhiteBalance.AUTO; proFocus = null; proAperture = null
        userEv = 0f
        applyControls(force = true)
    }

    fun setZoom(ratio: Float) {
        val r = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(r)
        zoom = r
    }

    fun userZoom(ratio: Float) {
        userZoomed = true
        setZoom(ratio)
    }

    fun tapToFocus(viewPoint: Offset) {
        val view = previewView ?: return
        val cam = camera ?: return
        lastTapAt = SystemClock.elapsedRealtime()
        if (mode == ShootMode.PRO && proFocus != null) proFocus = null
        applyControls(force = true)
        val point = view.meteringPointFactory.createPoint(viewPoint.x, viewPoint.y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        )
        focusRing = viewPoint to SystemClock.elapsedRealtime()
        tapFocusNorm = (viewPoint.x / view.width) to (viewPoint.y / view.height)
    }

    private var tapFocusNorm: Pair<Float, Float>? = null

    fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        userZoomed = false
        bind()
    }

    fun cycleFlash() {
        flash = FlashSetting.entries[(flash.ordinal + 1) % FlashSetting.entries.size]
        imageCapture?.flashMode = flashMode()
        camera?.cameraControl?.enableTorch(flash == FlashSetting.TORCH)
        applyControls(force = true)
    }

    fun cycleTimer() {
        timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    fun cycleGrid() {
        grid = GridType.entries[(grid.ordinal + 1) % GridType.entries.size]
    }

    fun toggleHistogram() { showHistogram = !showHistogram }
    fun toggleLevel() { showLevel = !showLevel }

    fun cycleAspect() {
        aspect = Aspect.entries[(aspect.ordinal + 1) % Aspect.entries.size]
        bind()
    }

    fun onMotion(r: Float, p: Float, q: Int) {
        roll = r; pitch = p; quarterTurns = q
    }

    fun say(text: String) {
        toast = text to SystemClock.elapsedRealtime()
    }

    // ---------------------------------------------------------------- capture

    fun shoot() {
        if (busy || countdown > 0) return
        val capture = imageCapture ?: return
        viewModelScope.launch {
            for (i in timer downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = 0
            busy = true
            val ctx = getApplication<Application>()
            val file = File(ctx.cacheDir, "shot_${System.nanoTime()}.jpg")
            val opts = ImageCapture.OutputFileOptions.Builder(file).build()
            sound.play(MediaActionSound.SHUTTER_CLICK)
            shutterBlink++
            val ok = suspendCancellableCoroutine { cont ->
                capture.takePicture(opts, captureExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
            if (!ok) {
                busy = false
                say("Couldn't take the photo, try again")
                return@launch
            }
            val note = "ISO $liveIso · ${Stops.shutterLabel(liveShutterNs)} · EV ${"%+.1f".format(activeRecipe().evBias + userEv)}"
            val params = RenderParams(
                look = look,
                extraWarmth = if (mode == ShootMode.PRO) 0f else activeRecipe().warmth,
                virtualAperture = virtualAperture,
                subjectIsPerson = scene == Scene.PORTRAIT || faces.isNotEmpty(),
                focusPoint = if (SystemClock.elapsedRealtime() - lastTapAt < 10_000) tapFocusNorm else null,
                sceneName = if (mode == ShootMode.PRO) "Pro" else scene.title,
                settingsNote = note,
            )
            try {
                val saved = pipeline.finish(file, params)
                lastUri = saved.uri
                lastThumb = saved.thumbnail?.asImageBitmap()
            } catch (e: Exception) {
                say("Saving failed: ${e.message}")
            }
            busy = false
        }
    }

    override fun onCleared() {
        analyzer?.close()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        sound.release()
        super.onCleared()
    }

    /** Friendly "what a DSLR would be set to" line. */
    fun dslrEquivalent(): String {
        val mm = (caps.equivalentFocalMm * zoom).roundToInt()
        val f = virtualAperture ?: proAperture ?: liveAperture
        return "≈ ${mm}mm · ${Stops.fNumberLabel(f)} · ${Stops.shutterLabel(liveShutterNs)} · ISO $liveIso"
    }

    private fun log2(v: Double) = ln(v) / ln(2.0)
}
