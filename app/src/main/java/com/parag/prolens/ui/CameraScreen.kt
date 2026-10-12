package com.parag.prolens.ui

import android.content.Intent
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.parag.prolens.CameraViewModel
import com.parag.prolens.FlashSetting
import com.parag.prolens.GridType
import com.parag.prolens.Panel
import com.parag.prolens.ai.Scene
import com.parag.prolens.ai.ShootMode
import com.parag.prolens.camera.Stops
import com.parag.prolens.sensors.LevelSensor
import com.parag.prolens.ui.components.AiPanel
import com.parag.prolens.ui.components.BokehPanel
import com.parag.prolens.ui.components.Countdown
import com.parag.prolens.ui.components.FaceOverlay
import com.parag.prolens.ui.components.FlipButton
import com.parag.prolens.ui.components.FocusRing
import com.parag.prolens.ui.components.GalleryThumb
import com.parag.prolens.ui.components.GlassIcon
import com.parag.prolens.ui.components.GridOverlay
import com.parag.prolens.ui.components.Histogram
import com.parag.prolens.ui.components.LevelOverlay
import com.parag.prolens.ui.components.LooksPanel
import com.parag.prolens.ui.components.ModeSelector
import com.parag.prolens.ui.components.Pill
import com.parag.prolens.ui.components.ProPanel
import com.parag.prolens.ui.components.ReadoutStrip
import com.parag.prolens.ui.components.SectionTab
import com.parag.prolens.ui.components.ShutterBlink
import com.parag.prolens.ui.components.ShutterButton
import com.parag.prolens.ui.components.ZoomChips
import com.parag.prolens.ui.theme.Pro
import kotlinx.coroutines.delay

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    // Spirit level / camera angle
    DisposableEffect(Unit) {
        val level = LevelSensor(context) { r, p, q -> vm.onMotion(r, p, q) }
        level.start()
        onDispose { level.stop() }
    }

    // A gentle tick when the AI changes its mind, like a detent on a dial
    LaunchedEffect(vm.scene) {
        if (vm.scene != Scene.GENERAL) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val iconRotation by animateFloatAsState(
        when (vm.quarterTurns) { 1 -> 90f; 3 -> -90f; else -> 0f }, label = "rot",
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Pro.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopBar(vm, iconRotation)

        // ------------------------------------------------ viewfinder (+ panels slide up over it)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val panelMax = maxHeight * 0.72f
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .aspectRatio(vm.aspect.ratio)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) vm.userZoom(vm.zoom * zoomChange)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { vm.tapToFocus(it) })
                }
        ) {
            val look = vm.look
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { view ->
                    vm.attach(lifecycleOwner, view)
                    // Live colour grade on the viewfinder
                    if (look.isNeutral) {
                        view.setLayerType(View.LAYER_TYPE_NONE, null)
                    } else {
                        view.setLayerType(
                            View.LAYER_TYPE_HARDWARE,
                            Paint().apply { colorFilter = ColorMatrixColorFilter(look.previewMatrix()) },
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            GridOverlay(vm.grid)
            if (vm.showLevel) LevelOverlay(vm.roll, vm.pitch)
            FaceOverlay(vm.faces, mirror = vm.lensFacing == CameraSelector.LENS_FACING_FRONT)
            FocusRing(vm.focusRing)
            Countdown(vm.countdown)
            ShutterBlink(vm.shutterBlink)

            // AI badge
            AiBadge(vm, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))

            if (vm.showHistogram) {
                Histogram(vm.histogram, Modifier.align(Alignment.TopStart).padding(10.dp))
            }

            // Bottom of the viewfinder: data strip + lenses
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZoomChips(vm.zoom, vm.minZoom, vm.maxZoom, vm.caps.equivalentFocalMm, onZoom = { vm.userZoom(it) })
                Spacer(Modifier.height(8.dp))
                ReadoutStrip(readouts(vm), vm.meterStops)
            }

            Toast(vm, Modifier.align(Alignment.Center))
        }

        PanelSheet(vm, panelMax, Modifier.align(Alignment.BottomCenter))
        }

        // ------------------------------------------------ tools
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTab(Icons.Rounded.AutoAwesome, "AI Coach", vm.panel == Panel.AI, { vm.panel = toggle(vm.panel, Panel.AI) })
            SectionTab(Icons.Rounded.Tune, "Pro", vm.panel == Panel.PRO, { vm.panel = toggle(vm.panel, Panel.PRO) })
            SectionTab(Icons.Rounded.FilterVintage, "Looks", vm.panel == Panel.LOOKS, { vm.panel = toggle(vm.panel, Panel.LOOKS) })
            SectionTab(Icons.Rounded.BlurOn, "Bokeh", vm.panel == Panel.BOKEH, { vm.panel = toggle(vm.panel, Panel.BOKEH) })
        }

        // ------------------------------------------------ mode dial + shutter
        ModeSelector(vm.mode, onSelect = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.selectMode(it)
        })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryThumb(vm.lastThumb, iconRotation) {
                val uri = vm.lastUri
                val intent = if (uri != null) {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    Intent(Intent.ACTION_VIEW).setType("image/*")
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    vm.say("No gallery app found")
                }
            }
            ShutterButton(busy = vm.busy, aiMode = vm.mode != ShootMode.PRO) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.shoot()
            }
            FlipButton(iconRotation) { vm.flipCamera() }
        }
    }
}

private fun toggle(current: Panel, p: Panel) = if (current == p) Panel.NONE else p

private fun readouts(vm: CameraViewModel): List<Pair<String, Boolean>> {
    val manualIso = vm.mode == ShootMode.PRO && vm.proIso != null
    val manualShutter = vm.mode == ShootMode.PRO && vm.proShutterNs != null
    val ai = vm.manualExposureActive && vm.mode != ShootMode.PRO
    return listOf(
        "ISO ${vm.liveIso}" to (manualIso || ai),
        Stops.shutterLabel(vm.liveShutterNs) to (manualShutter || ai),
        Stops.fNumberLabel(vm.virtualAperture ?: vm.proAperture ?: vm.liveAperture) to (vm.virtualAperture != null),
        "%+.1f".format(if (vm.mode == ShootMode.PRO) vm.userEv else vm.activeRecipe().evBias + vm.userEv) to (vm.userEv != 0f),
    )
}

@Composable
private fun TopBar(vm: CameraViewModel, rotation: Float) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIcon(
            when (vm.flash) {
                FlashSetting.OFF -> Icons.Rounded.FlashOff
                FlashSetting.AUTO -> Icons.Rounded.FlashAuto
                FlashSetting.ON -> Icons.Rounded.FlashOn
                FlashSetting.TORCH -> Icons.Rounded.FlashlightOn
            },
            "Flash", active = vm.flash != FlashSetting.OFF, rotation = rotation,
        ) { vm.cycleFlash() }
        GlassIcon(
            when (vm.timer) { 3 -> Icons.Rounded.Timer3; 10 -> Icons.Rounded.Timer10; else -> Icons.Rounded.TimerOff },
            "Timer", active = vm.timer > 0, rotation = rotation,
        ) { vm.cycleTimer() }
        GlassIcon(
            if (vm.grid == GridType.OFF) Icons.Rounded.GridOff else Icons.Rounded.Grid3x3,
            "Grid", active = vm.grid != GridType.OFF, rotation = rotation,
        ) { vm.cycleGrid() }
        GlassIcon(Icons.Rounded.Straighten, "Level", active = vm.showLevel, rotation = rotation) { vm.toggleLevel() }
        GlassIcon(Icons.Rounded.BarChart, "Histogram", active = vm.showHistogram, rotation = rotation) { vm.toggleHistogram() }
        Box(Modifier.weight(1f))
        if (vm.vendorAvailable) {
            Pill("Vivo engine", icon = Icons.Rounded.Memory, selected = vm.vendorOn) { vm.toggleVendor() }
            Spacer(Modifier.width(6.dp))
        }
        Pill(vm.aspect.label, icon = Icons.Rounded.AspectRatio) { vm.cycleAspect() }
    }
}

@Composable
private fun AiBadge(vm: CameraViewModel, modifier: Modifier) {
    if (vm.mode == ShootMode.PRO) {
        Pill("PRO · Manual", icon = Icons.Rounded.Tune, modifier = modifier)
        return
    }
    val text = when {
        vm.mode != ShootMode.AI -> "${vm.mode.title} mode"
        vm.scene == Scene.GENERAL -> "AI · Scanning scene"
        !vm.autoApply -> "AI suggests ${vm.scene.title}"
        else -> "AI · ${vm.scene.title}"
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Pro.GoldDeep.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.55f))
                )
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(vm.scene.icon, null, tint = Pro.Gold, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (vm.look.id != "original") {
            Text("  ·  ${vm.look.title}", color = Pro.Gold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CoachLine(vm: CameraViewModel) {
    val r = if (vm.mode == ShootMode.AI && !vm.autoApply) com.parag.prolens.ai.Photographer.recipeFor(vm.scene) else vm.activeRecipe()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(r.headline, color = Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(r.tips.firstOrNull() ?: "", color = Pro.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun Toast(vm: CameraViewModel, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    val t = vm.toast
    LaunchedEffect(t?.second) {
        if (t == null) return@LaunchedEffect
        visible = true
        delay(2000)
        visible = false
    }
    AnimatedVisibility(visible && t != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            t?.first ?: "",
            color = Color.White, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun PanelSheet(vm: CameraViewModel, panelMax: androidx.compose.ui.unit.Dp, modifier: Modifier) {
        AnimatedVisibility(
            vm.panel != Panel.NONE,
            modifier = modifier,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMax)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 6.dp)
            ) {
                when (vm.panel) {
                    Panel.AI -> AiPanel(vm)
                    Panel.PRO -> ProPanel(vm)
                    Panel.LOOKS -> Box(
                        Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp))
                            .background(Pro.Panel).padding(vertical = 12.dp)
                    ) { LooksPanel(vm) }
                    Panel.BOKEH -> BokehPanel(vm)
                    Panel.NONE -> {}
                }
            }
        }
}
