package com.parag.prolens.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spirit level + camera angle from gravity.
 * roll  = how far the horizon is tilted, relative to the nearest upright/sideways hold (degrees)
 * pitch = how far the camera points down: 0 = straight ahead, 90 = straight down (flat-lay)
 * quarterTurns = 0 portrait, 1/3 = held sideways (used to rotate on-screen icons)
 */
class LevelSensor(context: Context, private val onChange: (roll: Float, pitch: Float, quarterTurns: Int) -> Unit) :
    SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val g = FloatArray(3)
    private var init = false

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val a = if (init) 0.2f else 1f
        for (i in 0..2) g[i] = g[i] * (1 - a) + e.values[i] * a
        init = true
        val (x, y, z) = Triple(g[0], g[1], g[2])
        val inPlane = sqrt(x * x + y * y)
        val pitch = Math.toDegrees(atan2(z.toDouble(), inPlane.toDouble())).toFloat()
        // Angle of gravity inside the screen plane: 0 = upright portrait
        val planeAngle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        val quarter = ((planeAngle / 90f).roundToInt() % 4 + 4) % 4
        var roll = planeAngle - (planeAngle / 90f).roundToInt() * 90f
        if (pitch > 70f) roll = 0f // lying flat: horizon is meaningless
        onChange(roll, pitch, quarter)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
