package com.runner.smartplayer.watch.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.runner.smartplayer.watch.model.HeartRateSnapshot
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.SensorAvailability
import kotlin.math.roundToInt

class HeartRateSensorController(
    context: Context,
    private val classifier: HeartRateModeClassifier = HeartRateModeClassifier(),
    private val onHeartRateSnapshot: (HeartRateSnapshot) -> Unit,
    private val onStableModeChanged: (PlaybackMode) -> Unit,
) : HeartRateController, SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private var sensorAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var started = false

    override fun start() {
        when {
            !hasPermission() -> publishSnapshot(null, SensorAvailability.PERMISSION_REQUIRED)
            heartRateSensor == null -> publishSnapshot(null, SensorAvailability.NO_SENSOR)
            started -> Unit
            else -> {
                started = sensorManager.registerListener(
                    this,
                    heartRateSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                )
                publishSnapshot(
                    bpm = null,
                    availability = if (started) SensorAvailability.AVAILABLE else SensorAvailability.UNRELIABLE,
                )
            }
        }
    }

    override fun stop() {
        if (started) {
            sensorManager.unregisterListener(this)
            started = false
        }
        publishSnapshot(null, SensorAvailability.STOPPED)
    }

    override fun resetClassifier(mode: PlaybackMode) {
        classifier.reset(mode)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val bpm = event?.values?.firstOrNull()?.roundToInt()
        val availability = when (sensorAccuracy) {
            SensorManager.SENSOR_STATUS_NO_CONTACT -> SensorAvailability.NO_CONTACT
            SensorManager.SENSOR_STATUS_UNRELIABLE -> SensorAvailability.UNRELIABLE
            else -> SensorAvailability.AVAILABLE
        }
        val modeChanged = classifier.submitSample(
            bpm = bpm,
            sensorReady = availability == SensorAvailability.AVAILABLE,
        )
        publishSnapshot(bpm, availability)
        if (modeChanged != null) {
            onStableModeChanged(modeChanged)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensorAccuracy = accuracy
        val availability = when (accuracy) {
            SensorManager.SENSOR_STATUS_NO_CONTACT -> SensorAvailability.NO_CONTACT
            SensorManager.SENSOR_STATUS_UNRELIABLE -> SensorAvailability.UNRELIABLE
            else -> SensorAvailability.AVAILABLE
        }
        publishSnapshot(null, availability)
    }

    private fun publishSnapshot(bpm: Int?, availability: SensorAvailability) {
        onHeartRateSnapshot(
            HeartRateSnapshot(
                bpm = bpm,
                mode = classifier.currentMode(),
                availability = availability,
                message = availability.message(),
            )
        )
    }

    private fun SensorAvailability.message(): String = when (this) {
        SensorAvailability.AVAILABLE -> "正在读取光学心率"
        SensorAvailability.NO_SENSOR -> "未检测到心率传感器"
        SensorAvailability.PERMISSION_REQUIRED -> "需要身体传感器权限"
        SensorAvailability.NO_CONTACT -> "手表未贴合手腕"
        SensorAvailability.UNRELIABLE -> "传感器信号不稳定"
        SensorAvailability.STOPPED -> "传感器已停止"
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BODY_SENSORS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
