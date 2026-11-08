package io.github.mikevillasis.espeonanim.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.mikevillasis.espeonanim.shared.guidance.Vec3
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Listens to gravity and gyroscope. The latest values feed live guidance; while buffering,
 * gravity samples are kept so they can be written as `t_s,gx,gy,gz` aligned to the video start.
 */
class ImuRecorder(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var gravity: Vec3? = null
        private set
    @Volatile var gyroMagnitude: Float? = null
        private set

    private class Sample(val tNs: Long, val x: Float, val y: Float, val z: Float)

    private val lock = Any()
    private var buffer: ArrayList<Sample>? = null
    private var lowPass: FloatArray? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        val rate = 10_000 // microseconds: ~100 Hz requested
        if (gravitySensor != null) sm.registerListener(this, gravitySensor, rate)
        else if (accelSensor != null) sm.registerListener(this, accelSensor, rate)
        if (gyroSensor != null) sm.registerListener(this, gyroSensor, rate)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
        gravity = null
        gyroMagnitude = null
        lowPass = null
    }

    /** Start keeping gravity samples (call just before starting the video recording). */
    fun beginBuffering() = synchronized(lock) { buffer = ArrayList(1024) }

    fun discardBuffer() = synchronized(lock) { buffer = null }

    /**
     * Writes the buffered samples to [file], timestamps relative to [videoStartNs]
     * (SystemClock.elapsedRealtimeNanos at the recording's Start event). Returns true if written.
     */
    fun writeCsv(file: File, videoStartNs: Long, endNs: Long = Long.MAX_VALUE): Boolean {
        val samples = synchronized(lock) { buffer.also { buffer = null } } ?: return false
        val rows = samples.filter { it.tNs >= videoStartNs && it.tNs <= endNs }
        if (rows.isEmpty()) return false
        file.bufferedWriter().use { w ->
            w.write("t_s,gx,gy,gz\n")
            for (s in rows) {
                w.write(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f\n", (s.tNs - videoStartNs) / 1e9, s.x, s.y, s.z))
            }
        }
        return true
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> onGravity(e.timestamp, e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback when no fused gravity sensor exists: low-pass the accelerometer.
                val lp = lowPass ?: floatArrayOf(e.values[0], e.values[1], e.values[2]).also { lowPass = it }
                val a = 0.9f
                for (i in 0..2) lp[i] = a * lp[i] + (1 - a) * e.values[i]
                onGravity(e.timestamp, lp[0], lp[1], lp[2])
            }
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                gyroMagnitude = sqrt(x * x + y * y + z * z)
            }
        }
    }

    private fun onGravity(tNs: Long, x: Float, y: Float, z: Float) {
        gravity = Vec3(x, y, z)
        synchronized(lock) { buffer?.add(Sample(tNs, x, y, z)) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
