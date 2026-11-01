package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Wheel speed and gear from the ECARX car SDK (`ISensor`), for the iPhone's dead reckoning. The SDK is
 * in the head unit's boot classpath, so it is reached by reflection; an ordinary app reads these
 * sensors with no extra permission (Geely Monjaro, ECARX KX11, Android 9).
 *
 * The speed is polled rather than subscribed to: a listener only fires on a change, so a standing car
 * would send nothing, and the iPhone should hear 0 m/s at a red light. Its raw value is taken as m/s:
 * the Monjaro tweak app MConfig+ shows raw x 3.72 as the cluster's km/h, and clusters read about 3 %
 * over the true speed.
 */
internal object EcarxWheelSpeed : VehicleSpeedSource {
    private const val TAG = "DiPlay-EcarxSpeed"
    private const val CAR = "com.ecarx.xui.adaptapi.car.Car"
    private const val ICAR = "com.ecarx.xui.adaptapi.car.ICar"
    private const val SENSOR = "com.ecarx.xui.adaptapi.car.sensor.ISensor"
    private const val SENSOR_TYPE_CAR_SPEED = 1048832
    private const val SENSOR_TYPE_GEAR = 2097664
    private const val GEAR_PARK = 2097712
    private const val GEAR_REVERSE = 2097728
    private const val GEAR_NEUTRAL = 2097680
    private const val GEAR_DRIVE = 2097696
    private val GEAR_FORWARD = 2097665..2097674 // GEAR_FIRST..GEAR_TENTH, manual mode
    private const val MAX_METERS_PER_SECOND = 90.0
    private const val READ_MILLIS = 250L
    private const val GEAR_EVERY_READS = 4
    private const val MAX_SAMPLES = 40
    private const val RETRY_MILLIS = 5_000L

    @Volatile private var context: Context? = null
    @Volatile private var sensor: Any? = null
    /** The poll, the video gate and the HUD readout all ask for the sensor manager: one lookup at a time. */
    private val sensorLock = Any()
    @Volatile private var speedSupported = false
    @Volatile private var speedDeniedAt: Long? = null
    private var speedStatusLogged: String? = null // poll thread
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastSuccessAt = 0L // a lookup or a call that answered
    private var failures = 0 // guarded by sensorLock: calls in a row that threw
    private var executor: ScheduledExecutorService? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reads = 0
    private var firstSampleLogged = false
    private var failureLogged = false

    /**
     * No position for the iPhone: the Monjaro's head unit has no GPS receiver, and an Android network
     * fix would only mislead it. With this, only `$PASCD` is sent, beside the iPhone's own GPS.
     */
    object NoPosition : Iap2LocationProvider {
        override fun start(): Boolean = false

        override fun stop() = Unit

        override fun latestNmea(): String? = null
    }

    private val sdkPresent by lazy { runCatching { Class.forName(CAR); Class.forName(SENSOR) }.isSuccess }

    /** Whether the ECARX car SDK is on this head unit. */
    fun present(): Boolean = sdkPresent

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    /**
     * Whether this head unit has a working GPS receiver of its own. The KX11 declares the GPS feature
     * and lists a "gps" provider, but keeps it disabled and has never had a fix from it, so only an
     * enabled provider with a fix counts.
     */
    @SuppressLint("MissingPermission") // The location permission is checked; a refusal reads as no GPS.
    fun hasGps(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) != null
    }.getOrDefault(false)

    @Synchronized
    override fun start() {
        if (executor != null) return
        samples.clear()
        gear = null
        reads = 0
        firstSampleLogged = false
        speedDeniedAt = null // a new session asks again at once
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "wheel speed started")
    }

    override fun stop() {
        val running = synchronized(this) { executor.also { executor = null } } ?: return
        running.shutdown()
        Log.i(TAG, "wheel speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    internal fun gear(event: Int): VehicleGear? = when (event) {
        GEAR_PARK -> VehicleGear.PARK
        GEAR_REVERSE -> VehicleGear.REVERSE
        GEAR_NEUTRAL -> VehicleGear.NEUTRAL
        GEAR_DRIVE, in GEAR_FORWARD -> VehicleGear.DRIVE
        else -> null
    }

    /**
     * Whether the car is in P, or null when the gear cannot be read; for video in car, which plays only
     * in P. Blocking (a binder call); the video gate reads it once a second.
     */
    fun parked(appContext: Context): Boolean? {
        attach(appContext)
        val sensor = sensor() ?: return null
        val event = runCatching { call(sensor, "getSensorEvent", SENSOR_TYPE_GEAR) as Int }.getOrNull() ?: return null
        return parked(event)
    }

    internal fun parked(event: Int): Boolean? = gear(event)?.let { it == VehicleGear.PARK }

    private val supportedTypes = HashSet<Int>() // guarded by itself: only a yes is kept
    private val deniedAt = HashMap<Int, Long>() // guarded by supportedTypes: when a no was last heard

    private fun statusSupported(status: String?): Boolean = status != null && status != "notavailable" && status != "error"

    /**
     * The latest value of any ECARX sensor, or null when it cannot be read or the sensor is not
     * supported (an unsupported sensor may read 0). Blocking (a binder call): not for the main thread.
     */
    fun latest(appContext: Context, type: Int): Float? {
        attach(appContext)
        val manager = sensor() ?: return null
        val now = SystemClock.elapsedRealtime()
        val known = synchronized(supportedTypes) {
            when {
                type in supportedTypes -> true
                ecarxStillDenied(deniedAt[type], now) -> false
                else -> null
            }
        }
        if (known == false) return null
        if (known == null) {
            val status = runCatching { call(manager, "isSensorSupported", type)?.toString() }
            Log.i(TAG, "sensor $type support: ${status.getOrNull() ?: status.exceptionOrNull()}")
            // A failed call is asked again at the next read; only an answer is kept, as for the speed.
            if (status.isFailure) return null
            val supported = statusSupported(status.getOrNull())
            // An answer of a manager dropped meanwhile is not kept for the new one.
            synchronized(supportedTypes) {
                when {
                    sensor !== manager -> Unit
                    supported -> supportedTypes += type
                    else -> deniedAt[type] = now
                }
            }
            if (!supported) return null
        }
        return latestValue(manager, type)
    }

    private fun latestValue(manager: Any, type: Int): Float? =
        runCatching { call(manager, "getSensorLatestValue", type) as Float }.getOrNull()

    internal fun metersPerSecond(raw: Float): Double? =
        raw.toDouble().takeIf { it.isFinite() && it in 0.0..MAX_METERS_PER_SECOND }

    private fun poll() {
        val sensor = sensor() ?: return
        if (reads++ % GEAR_EVERY_READS == 0) {
            val event = runCatching { call(sensor, "getSensorEvent", SENSOR_TYPE_GEAR) as Int }.getOrNull()
            val next = event?.let(::gear)
            // Checked under the lock that failed() clears the readings with: nothing read through a
            // manager dropped meanwhile is kept.
            val changed = synchronized(this) { this.sensor === sensor && (gear != next).also { gear = next } }
            if (changed) Log.i(TAG, "gear $next (event $event)")
        }
        // The gear read may have dropped this manager: the new one is asked, not this one.
        if (this.sensor !== sensor) return
        if (!askSpeedSupported(sensor)) return
        val before = SystemClock.elapsedRealtime()
        val raw = latestValue(sensor, SENSOR_TYPE_CAR_SPEED) ?: return
        val speed = metersPerSecond(raw) ?: return
        val sample = VehicleSpeedSample((before + SystemClock.elapsedRealtime()) / 2, speed)
        synchronized(this) {
            if (this.sensor !== sensor) return
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += sample
        }
        if (!firstSampleLogged) {
            firstSampleLogged = true
            Log.i(TAG, "first wheel speed raw $raw = ${"%.1f".format(speed * 3.6)} km/h")
        }
    }

    /**
     * The ECARX sensor manager. The ECARX framework can come up after DiPlay at boot, so a failed
     * lookup is retried, but at most every few seconds: the poll, the video gate and the HUD readout
     * all ask, and each try goes through `Car.create`.
     */
    private fun sensor(): Any? = synchronized(sensorLock) { findSensor() }

    private fun findSensor(): Any? {
        sensor?.let { return it }
        val app = context ?: return null
        val now = SystemClock.elapsedRealtime()
        if (lastAttemptAt != 0L && now - lastAttemptAt < RETRY_MILLIS) return null
        lastAttemptAt = now
        return runCatching {
            val car = Class.forName(CAR).getMethod("create", Context::class.java).invoke(null, app)
            // Through the public interface: the implementing class may not be accessible to reflection.
            Class.forName(ICAR).getMethod("getSensorManager").invoke(car)!!
        }.onSuccess {
            sensor = it
            lastSuccessAt = now
            failures = 0
        }.onFailure {
            if (!failureLogged) Log.w(TAG, "ECARX sensors unavailable, retrying", it)
            failureLogged = true
        }.getOrNull()
    }

    /**
     * Whether the speed sensor is supported. A yes holds until the sensor manager is looked up again; a
     * no is asked again after a while, as in [latest]: the framework may answer no while it is still
     * coming up at boot. An unsupported sensor may read 0, and a false 0 would tell the iPhone the car
     * stands while its GPS sees it moving. The gear does not depend on it.
     */
    private fun askSpeedSupported(manager: Any): Boolean {
        if (speedSupported) return true
        val now = SystemClock.elapsedRealtime()
        if (ecarxStillDenied(speedDeniedAt, now)) return false
        // A failed call is asked again at the next read; only an answer is kept.
        val status = runCatching { call(manager, "isSensorSupported", SENSOR_TYPE_CAR_SPEED)?.toString() }
            .getOrElse { return false }
        val supported = statusSupported(status)
        // Logged when the answer changes, not every time a no is asked again.
        if (status != speedStatusLogged) {
            speedStatusLogged = status
            Log.i(TAG, "ECARX speed sensor $status")
        }
        // Under the lock failed() resets these with: an answer of a manager dropped meanwhile is not kept.
        return synchronized(sensorLock) {
            when {
                sensor !== manager -> false
                supported -> true.also { speedSupported = true }
                else -> false.also { speedDeniedAt = now }
            }
        }
    }

    private fun call(sensor: Any, method: String, type: Int): Any? = try {
        Class.forName(SENSOR).getMethod(method, Int::class.javaPrimitiveType).invoke(sensor, type)
            .also {
                synchronized(sensorLock) {
                    if (this.sensor === sensor) {
                        failures = 0
                        lastSuccessAt = SystemClock.elapsedRealtime()
                    }
                }
            }
    } catch (failure: Exception) {
        failed(sensor, method, failure)
        throw failure
    }

    /**
     * The sensor manager is a proxy to the ECARX car service. When that service restarts, every call
     * through the old proxy throws for good; without a new lookup the speed, the gear and the HUD
     * readout would stay empty until DiPlay restarts. Only a manager through which nothing has answered
     * for a while is dropped: one sensor that keeps throwing while another answers is no restart.
     */
    private fun failed(used: Any, method: String, failure: Exception) = synchronized(sensorLock) {
        if (sensor !== used) return@synchronized
        failures++
        if (!ecarxReconnectDue(failures, SystemClock.elapsedRealtime() - lastSuccessAt)) return@synchronized
        Log.w(TAG, "ECARX sensor calls keep failing ($method: ${failure.cause ?: failure}); looking the sensor manager up again")
        sensor = null
        failures = 0
        lastAttemptAt = 0L
        failureLogged = false
        // The new manager is asked again, and nothing read through the old one is sent with its readings.
        speedSupported = false
        speedDeniedAt = null
        synchronized(supportedTypes) {
            supportedTypes.clear()
            deniedAt.clear()
        }
        synchronized(this) {
            gear = null
            samples.clear()
        }
    }
}

/** The framework may answer "no" while it is still coming up, so a no is asked again after this. */
private const val ECARX_ASK_AGAIN_MILLIS = 30_000L
/** Calls in a row that throw before the sensor manager is looked up again. */
private const val ECARX_FAILURES_BEFORE_RECONNECT = 3
/** How long nothing must have answered through a sensor manager before it is looked up again. */
private const val ECARX_RECONNECT_MIN_MILLIS = 30_000L

/** Whether a sensor that answered no at [deniedAt] is still taken as unsupported at [now]. */
internal fun ecarxStillDenied(deniedAt: Long?, now: Long): Boolean =
    deniedAt != null && now - deniedAt < ECARX_ASK_AGAIN_MILLIS

/**
 * Whether [failures] calls in a row that threw, through a manager that last answered (or was found)
 * [sinceAnswerMillis] ago, call for a new lookup.
 */
internal fun ecarxReconnectDue(failures: Int, sinceAnswerMillis: Long): Boolean =
    failures >= ECARX_FAILURES_BEFORE_RECONNECT && sinceAnswerMillis >= ECARX_RECONNECT_MIN_MILLIS
