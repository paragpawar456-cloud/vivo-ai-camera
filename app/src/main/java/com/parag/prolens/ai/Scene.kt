package com.parag.prolens.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector

/** Every subject the AI photographer knows how to shoot. */
enum class Scene(val title: String, val icon: ImageVector) {
    GENERAL("Auto", Icons.Rounded.AutoAwesome),
    PORTRAIT("Portrait", Icons.Rounded.Face),
    FOOD("Food", Icons.Rounded.Restaurant),
    LANDSCAPE("Landscape", Icons.Rounded.Landscape),
    SUNSET("Golden Hour", Icons.Rounded.WbTwilight),
    NIGHT("Night", Icons.Rounded.NightsStay),
    MACRO("Macro", Icons.Rounded.LocalFlorist),
    PET("Pet", Icons.Rounded.Pets),
    ARCHITECTURE("City", Icons.Rounded.Apartment),
    ACTION("Action", Icons.Rounded.DirectionsRun),
}

/** What the shutter row offers. AI picks the scene itself; the others force one; PRO is fully manual. */
enum class ShootMode(val title: String, val icon: ImageVector, val scene: Scene?) {
    AI("AI", Icons.Rounded.AutoAwesome, null),
    PORTRAIT("Portrait", Scene.PORTRAIT.icon, Scene.PORTRAIT),
    FOOD("Food", Scene.FOOD.icon, Scene.FOOD),
    LANDSCAPE("Landscape", Scene.LANDSCAPE.icon, Scene.LANDSCAPE),
    NIGHT("Night", Scene.NIGHT.icon, Scene.NIGHT),
    MACRO("Macro", Scene.MACRO.icon, Scene.MACRO),
    ACTION("Action", Scene.ACTION.icon, Scene.ACTION),
    PRO("Pro", Icons.Rounded.Tune, null),
}
