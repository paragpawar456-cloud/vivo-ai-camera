package com.parag.prolens.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraEnhance
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Iso
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.ShutterSpeed
import androidx.compose.material.icons.rounded.WbAuto
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parag.prolens.CameraViewModel
import com.parag.prolens.ProDial
import com.parag.prolens.ai.Photographer
import com.parag.prolens.ai.Scene
import com.parag.prolens.ai.ShootMode
import com.parag.prolens.camera.Stops
import com.parag.prolens.camera.WhiteBalance
import com.parag.prolens.processing.Look
import com.parag.prolens.ui.theme.Pro
import kotlin.math.abs
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(20.dp)

@Composable
private fun PanelBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(PanelShape)
            .background(Pro.Panel)
            .border(BorderStroke(1.dp, Pro.Line), PanelShape)
            .padding(14.dp)
    ) { content() }
}

// ------------------------------------------------------------------ AI photographer

@Composable
fun AiPanel(vm: CameraViewModel) {
    val r = vm.recipe
    val shown = if (vm.mode == ShootMode.AI && !vm.autoApply) Photographer.recipeFor(vm.scene) else r
    PanelBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Pro.Gold, Pro.GoldDeep))),
                contentAlignment = Alignment.Center,
            ) { Icon(vm.scene.icon, null, tint = Color.Black, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (vm.scene == Scene.GENERAL) "Just point and press the big button" else "${vm.scene.title} detected",
                    color = Pro.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Text(shown.headline, color = Pro.TextDim, fontSize = 12.sp)
            }
            if (vm.mode == ShootMode.AI) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(
                        checked = vm.autoApply, onCheckedChange = { vm.toggleAutoApply() },
                        colors = SwitchDefaults.colors(checkedTrackColor = Pro.Gold, checkedThumbColor = Color.Black),
                    )
                    Text(if (vm.autoApply) "AI sets camera" else "Only tips", color = Pro.TextDim, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // What a DSLR shooter would dial in
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pro.PanelHigh).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CameraEnhance, null, tint = Pro.Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("AI has set your camera like a pro DSLR:", color = Pro.TextDim, fontSize = 10.sp)
                Text(vm.dslrEquivalent(), color = Pro.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Camera angle coach
        shown.angleHint?.let { hint ->
            val ok = shown.idealPitch.any { vm.pitch in it }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ScreenRotation, null,
                    tint = if (ok) Pro.Good else Pro.Gold, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(hint, color = Pro.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "${vm.pitch.roundToInt()}°", color = if (ok) Pro.Good else Pro.Gold,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                )
            }
        }

        // Lens suggestion
        val ideal = Photographer.idealZoom(shown, vm.caps, vm.maxZoom)
        if (ideal != null && abs(ideal - vm.zoom) / ideal > 0.15f) {
            Spacer(Modifier.height(8.dp))
            Pill(
                "Use ${shown.idealFocalMm?.toInt()}mm lens (${"%.1f".format(ideal)}×)",
                icon = Icons.Rounded.ZoomIn,
            ) { vm.userZoom(ideal) }
        }

        // Pro tips
        Spacer(Modifier.height(8.dp))
        shown.tips.take(2).forEach { tip ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Icon(Icons.Rounded.Lightbulb, null, tint = Pro.GoldDeep, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(tip, color = Pro.TextDim, fontSize = 12.sp)
            }
        }

        if (vm.mode == ShootMode.AI && !vm.autoApply && vm.scene != Scene.GENERAL) {
            Spacer(Modifier.height(8.dp))
            Pill("Apply ${vm.scene.title} settings", icon = Icons.Rounded.AutoAwesome, selected = true) {
                vm.applySuggestion()
            }
        }

        // Brightness
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Exposure, null, tint = Pro.TextDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Slider(
                value = vm.userEv, onValueChange = { vm.setEv((it * 3).roundToInt() / 3f) },
                valueRange = -2f..2f, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Pro.Gold, activeTrackColor = Pro.Gold),
            )
            Text("%+.1f".format(vm.userEv), color = Pro.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        if (vm.detectedLabels.isNotEmpty()) {
            Text(
                "Sees: " + vm.detectedLabels.joinToString(", "),
                color = Pro.TextDim.copy(alpha = 0.7f), fontSize = 10.sp,
            )
        }
    }
}

// ------------------------------------------------------------------ Pro / manual

@Composable
fun ProPanel(vm: CameraViewModel) {
    val c = vm.caps
    PanelBox {
        if (!c.manualSensor) {
            Text(
                "This lens doesn't allow manual ISO/shutter, so those use smart auto. Other controls work.",
                color = Pro.Warn, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DialChip(Icons.Rounded.Iso, "SENSITIVITY", vm.proIso?.toString() ?: "A ${vm.liveIso}", vm.proIso != null, vm.proDial == ProDial.ISO) { vm.proDial = ProDial.ISO }
            DialChip(Icons.Rounded.ShutterSpeed, "SHUTTER", vm.proShutterNs?.let { Stops.shutterLabel(it) } ?: "A ${Stops.shutterLabel(vm.liveShutterNs)}", vm.proShutterNs != null, vm.proDial == ProDial.SHUTTER) { vm.proDial = ProDial.SHUTTER }
            DialChip(Icons.Rounded.Exposure, "BRIGHTNESS", "%+.1f".format(vm.userEv), vm.userEv != 0f, vm.proDial == ProDial.EV) { vm.proDial = ProDial.EV }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DialChip(Icons.Rounded.WbAuto, "COLOUR", vm.proWb.kelvin?.let { "${it}K" } ?: "AWB", vm.proWb != WhiteBalance.AUTO, vm.proDial == ProDial.WB) { vm.proDial = ProDial.WB }
            DialChip(Icons.Rounded.CenterFocusStrong, "FOCUS", vm.proFocus?.let { focusLabel(it) } ?: "AF", vm.proFocus != null, vm.proDial == ProDial.FOCUS) { vm.proDial = ProDial.FOCUS }
            DialChip(Icons.Rounded.Camera, "BLUR", apertureLabel(vm), vm.proAperture != null || vm.virtualAperture != null, vm.proDial == ProDial.APERTURE) { vm.proDial = ProDial.APERTURE }
        }
        Spacer(Modifier.height(8.dp))
        Text(dialHelp(vm.proDial), color = Pro.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))

        when (vm.proDial) {
            ProDial.ISO -> {
                val isos = Stops.ISOS.filter { it in c.minIso..c.maxIso }
                StopRuler(listOf<Int?>(null) + isos, vm.proIso, { it?.toString() ?: "AUTO" }) { vm.pickIso(it) }
            }
            ProDial.SHUTTER -> {
                val list = Stops.SHUTTERS_NS.filter { it in c.exposureRangeNs.lower..c.exposureRangeNs.upper }
                StopRuler(listOf<Long?>(null) + list, vm.proShutterNs, { it?.let(Stops::shutterLabel) ?: "AUTO" }) { vm.pickShutter(it) }
            }
            ProDial.EV -> {
                val evs = (-9..9).map { it / 3f }
                StopRuler(evs, vm.userEv, { if (it == 0f) "0" else "%+.1f".format(it) }) { vm.setEv(it) }
            }
            ProDial.WB -> StopRuler(WhiteBalance.entries, vm.proWb, { it.label }) { vm.pickWb(it) }
            ProDial.FOCUS -> FocusDial(vm)
            ProDial.APERTURE -> ApertureDial(vm)
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            Pill("Reset", icon = Icons.Rounded.RestartAlt) { vm.resetPro() }
        }
    }
}

private fun focusLabel(d: Float) = if (d <= 0.05f) "∞" else {
    val m = 1f / d
    if (m >= 1f) "%.1fm".format(m) else "${(m * 100).roundToInt()}cm"
}

private fun apertureLabel(vm: CameraViewModel): String = when {
    vm.proAperture != null -> Stops.fNumberLabel(vm.proAperture!!)
    vm.virtualAperture != null -> "✦ " + Stops.fNumberLabel(vm.virtualAperture!!)
    else -> Stops.fNumberLabel(vm.caps.baseAperture)
}

@Composable
private fun FocusDial(vm: CameraViewModel) {
    val max = vm.caps.minFocusDiopters
    if (max <= 0f) {
        Text("This lens is fixed-focus.", color = Pro.TextDim, fontSize = 12.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("AF", selected = vm.proFocus == null) { vm.pickFocus(null) }
        Spacer(Modifier.width(10.dp))
        Text("∞", color = Pro.TextDim, fontSize = 14.sp)
        Slider(
            value = vm.proFocus ?: 0f, onValueChange = { vm.pickFocus(it) },
            valueRange = 0f..max, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(thumbColor = Pro.Gold, activeTrackColor = Pro.Gold),
        )
        Text("Macro", color = Pro.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun ApertureDial(vm: CameraViewModel) {
    val c = vm.caps
    if (c.hasVariableAperture) {
        Text("Real lens aperture", color = Pro.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        StopRuler(listOf<Float?>(null) + c.apertures.sorted(), vm.proAperture, { it?.let(Stops::fNumberLabel) ?: "AUTO" }) { vm.pickAperture(it) }
        Spacer(Modifier.height(8.dp))
    }
    BokehDialBody(vm)
}

// ------------------------------------------------------------------ Bokeh / virtual aperture

@Composable
fun BokehPanel(vm: CameraViewModel) {
    PanelBox { BokehDialBody(vm) }
}

@Composable
private fun BokehDialBody(vm: CameraViewModel) {
    Text("Blur the background", color = Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Makes your subject stand out like an expensive camera. Applied when the photo is saved. Stronger = softer background.",
        color = Pro.TextDim, fontSize = 11.sp,
    )
    Spacer(Modifier.height(8.dp))
    StopRuler(listOf<Float?>(null, 8f, 4f, 2.8f, 1.8f, 1.4f), vm.virtualAperture, { blurLabel(it) }) {
        vm.pickVirtualAperture(it)
    }
}

// ------------------------------------------------------------------ Looks

@Composable
fun LooksPanel(vm: CameraViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LookCard("AI Pick", Brush.sweepGradient(listOf(Pro.Gold, Color(0xFFE8603C), Color(0xFF6E3CC8), Color(0xFF2F7F86), Pro.Gold)), !vm.lookLocked, Icons.Rounded.AutoAwesome) {
                vm.chooseLook(null)
            }
        }
        items(Look.ALL) { l ->
            LookCard(
                l.title,
                Brush.linearGradient(listOf(Color(l.swatch), Color(l.swatch).copy(alpha = 0.45f), Color.Black)),
                vm.lookLocked && vm.look == l,
                null,
            ) { vm.chooseLook(l) }
        }
    }
}

@Composable
private fun LookCard(title: String, brush: Brush, selected: Boolean, icon: ImageVector?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
                .border(BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) Pro.Gold else Pro.Line), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title, color = if (selected) Pro.Gold else Pro.TextDim, fontSize = 10.sp, maxLines = 1,
            textAlign = TextAlign.Center, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun DialChip(icon: ImageVector, label: String, value: String, manual: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Pro.Gold.copy(alpha = 0.14f) else Pro.PanelHigh)
            .border(BorderStroke(1.dp, if (selected) Pro.Gold else Color.Transparent), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Pro.TextDim, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Pro.TextDim, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Text(
            value, color = if (manual) Pro.Gold else Pro.Text, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
        )
    }
}

/** A scrolling row of stops, like turning a command dial. */
@Composable
fun <T> StopRuler(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { o ->
            val sel = o == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Pro.Gold else Pro.PanelHigh)
                    .clickable { onPick(o) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), color = if (sel) Color.Black else Pro.Text, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun blurLabel(f: Float?): String = when {
    f == null -> "Off"
    f >= 8f -> "Tiny"
    f >= 4f -> "Light"
    f >= 2.8f -> "Medium"
    f >= 1.8f -> "Strong"
    else -> "Max"
}

private fun dialHelp(d: ProDial): String = when (d) {
    ProDial.ISO -> "ISO = light sensitivity. Higher is brighter in the dark but adds grain. AUTO is best."
    ProDial.SHUTTER -> "Shutter = how long the camera looks. 1/500 freezes motion; 1/15 is brighter but can blur."
    ProDial.EV -> "Brightness. Slide + for a brighter photo, − for darker."
    ProDial.WB -> "Colour of light. Pick Daylight, Cloudy, Tungsten (indoor bulbs) to fix yellow or blue photos."
    ProDial.FOCUS -> "What's sharp. AF = automatic. Slide towards Macro for very close things."
    ProDial.APERTURE -> "How blurry the background is."
}
