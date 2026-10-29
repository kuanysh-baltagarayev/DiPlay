package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.View
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/**
 * A small readout in the bottom-right corner of the Geely/ECARX windshield HUD (display 2, below the
 * cluster's top 1920x720; MConfig+ draws its lines on the left and the head unit draws the speed at the
 * right centre, which a screenshot of display 2 does not show): the outside temperature, with a mark
 * when the road may be icy, and the range, but only when it is low. Both come from the car's own ECARX
 * sensors, as the cluster's do. It shows while a CarPlay session is connected.
 *
 * `am broadcast -a com.shilapi.xcertplay.GEELY_HUD_INFO -p com.shihab.diplay [--ez on false]
 * [--ei x 468 --ei y 828 --ei w 260 --ei h 78] [--ei rs 1]` switches it off or moves it (display 2
 * pixels); `rs` is the divisor that turns the range sensor's raw value into km (1 until checked against
 * the cluster). The change shows at once.
 */
internal object GeelyHudInfo {
    private const val TAG = "DiPlay-GeelyHudInfo"
    const val ACTION = "com.shilapi.xcertplay.GEELY_HUD_INFO"
    private const val PREFS = "geely_hud_info"
    private const val KEY_ON = "on"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_W = "w"
    private const val KEY_H = "h"
    private const val KEY_RANGE_DIVISOR = "rs"
    private const val SENSOR_TEMPERATURE_AMBIENT = 1051392
    /** SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL. */
    private const val SENSOR_RANGE = 1054720
    private const val DISPLAY_ID = 2
    private const val TICK_MILLIS = 5_000L
    /** Inside the head unit's HUD task ([0,720][728,906]); to be measured on the windshield with [GeelyHudTest]. */
    private val DEFAULT = HudRect(468, 828, 260, 78)
    /** Ticks in a row a low range has to be read before it is shown. */
    private const val LOW_RANGE_TICKS = 2
    /** After a failed attempt to open the window, to log once and not on every tick. */
    private const val RETRY_MILLIS = 30_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy { Handler(HandlerThread("diplay-geely-hud-info").apply { start() }.looper) }
    @Volatile private var context: Context? = null
    @Volatile private var enabled = true
    private var window: WindowManager? = null // main thread
    private var view: HudInfoView? = null // main thread
    private var lastRaw: Pair<Float?, Float?>? = null // worker thread
    private var lastImplausible: Pair<Float?, Float?>? = null // worker thread
    private var icy = false // worker thread: the ice mark is on, with a hysteresis
    private var lowRangeTicks = 0 // worker thread: readings in a row below the shown range
    private var retryAt = 0L // main thread

    fun attach(appContext: Context) {
        if (context != null) return
        val app = appContext.applicationContext
        context = app
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION) main.post { configure(intent) }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        worker.post(tick)
    }

    private fun rect(app: Context): HudRect {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return clampHudRect(prefs.getInt(KEY_X, DEFAULT.x), prefs.getInt(KEY_Y, DEFAULT.y),
            prefs.getInt(KEY_W, DEFAULT.width), prefs.getInt(KEY_H, DEFAULT.height))
    }

    private fun rangeDivisor(app: Context): Int =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RANGE_DIVISOR, 1).coerceIn(1, 1000)

    private fun configure(intent: Intent) {
        val app = context ?: return
        val old = rect(app)
        val next = clampHudRect(intent.getIntExtra("x", old.x), intent.getIntExtra("y", old.y),
            intent.getIntExtra("w", old.width), intent.getIntExtra("h", old.height))
        val on = if (intent.hasExtra("on")) intent.getBooleanExtra("on", true) else enabled
        val divisor = intent.getIntExtra("rs", rangeDivisor(app)).coerceIn(1, 1000)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on)
            .putInt(KEY_X, next.x).putInt(KEY_Y, next.y).putInt(KEY_W, next.width).putInt(KEY_H, next.height)
            .putInt(KEY_RANGE_DIVISOR, divisor).apply()
        enabled = on
        hide()
        // Draw again at once, where it belongs now, instead of at the next tick.
        worker.removeCallbacks(tick)
        worker.post(tick)
        Log.i(TAG, "HUD info ${if (on) "on" else "off"}, window $old -> $next, range divisor $divisor")
    }

    /** Reads the sensors off the main thread (binder calls) and draws on it. */
    private val tick = object : Runnable {
        override fun run() {
            val app = context ?: return
            val lines = runCatching {
                if (enabled && CarPlayGlance.snapshot().connected) lines(app) else {
                    icy = false
                    lowRangeTicks = 0
                    emptyList()
                }
            }.onFailure { Log.w(TAG, "HUD info read failed", it) }.getOrDefault(emptyList())
            main.post { render(lines) }
            worker.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun lines(app: Context): List<HudInfoLine> {
        val rawTemperature = EcarxWheelSpeed.latest(app, SENSOR_TEMPERATURE_AMBIENT)
        val rawRange = EcarxWheelSpeed.latest(app, SENSOR_RANGE)
        val raw = rawTemperature to rawRange
        if (raw != lastRaw) {
            lastRaw = raw
            Log.i(TAG, "sensors: temperature raw $rawTemperature, range raw $rawRange")
        }
        val km = hudInfoRangeKm(rawRange, rangeDivisor(app))
        if (rawRange != null && km == null && raw != lastImplausible) {
            lastImplausible = raw
            Log.w(TAG, "range raw $rawRange is not a plausible range with divisor ${rangeDivisor(app)}: not shown")
        }
        // A low range is shown once it has been read twice in a row, not on a value the ECU has not set yet.
        lowRangeTicks = if (km != null && km < RANGE_SHOWN_BELOW_KM) lowRangeTicks + 1 else 0
        val info = HudInfo(hudInfoTemperature(rawTemperature), km?.takeIf { lowRangeTicks >= LOW_RANGE_TICKS })
        icy = info.temperatureCelsius?.let { hudInfoIcy(it.roundToInt(), icy) } ?: false
        return hudInfoLines(info, icy, app.getString(R.string.hud_info_ice), app.getString(R.string.hud_info_km))
    }

    private fun render(lines: List<HudInfoLine>) {
        val app = context ?: return
        if (!enabled || lines.isEmpty()) {
            hide()
            return
        }
        val shown = view
        if (shown != null && !shown.dead) {
            shown.lines = lines
            shown.invalidate()
            return
        }
        // The system drops an overlay when its display goes away: open a new one.
        if (shown != null) hide()
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return
        runCatching {
            val target = GeelyClusterMap.displayWindow(app)
            val rect = rect(app)
            val created = HudInfoView(target.context, lines)
            target.manager.addView(created,
                GeelyClusterMap.windowParams(rect.x, rect.y, rect.width, rect.height, touchable = false))
            window = target.manager
            view = created
            Log.i(TAG, "HUD info window $rect shown")
        }.onFailure {
            retryAt = now + RETRY_MILLIS
            Log.w(TAG, "HUD info window failed, trying again in ${RETRY_MILLIS / 1000} s", it)
        }
    }

    private fun hide() {
        val shown = view ?: return
        view = null
        runCatching { window?.removeView(shown) }
        window = null
    }

    /** The lines, right-aligned from the top, in one colour each: white, amber for a warning, red for an alert. */
    private class HudInfoView(context: Context, var lines: List<HudInfoLine>) : View(context) {
        /** The system took the window away (its display went); a view not yet attached is not dead. */
        @Volatile var dead = false
            private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }

        init {
            addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    dead = true
                }
            })
        }

        override fun onDraw(canvas: Canvas) {
            val rowHeight = height / 2f
            paint.textSize = rowHeight * 0.8f
            // Shrink the text until the longest line fits the window.
            val room = width - 16f
            val longest = lines.maxOfOrNull { paint.measureText(it.text) } ?: 0f
            if (longest > room && longest > 0f) paint.textSize = (paint.textSize * room / longest).coerceAtLeast(8f)
            lines.forEachIndexed { index, line ->
                paint.color = when (line.severity) {
                    HudInfoSeverity.NORMAL -> Color.WHITE
                    HudInfoSeverity.WARNING -> Color.rgb(255, 179, 0)
                    HudInfoSeverity.ALERT -> Color.rgb(255, 59, 48)
                }
                canvas.drawText(line.text, width - 8f, rowHeight * (index + 1) - rowHeight * 0.2f, paint)
            }
        }
    }
}

internal data class HudInfo(val temperatureCelsius: Float?, val rangeKm: Int?)

internal enum class HudInfoSeverity { NORMAL, WARNING, ALERT }

internal data class HudInfoLine(val text: String, val severity: HudInfoSeverity)

/** The range is shown only below this, in km. */
private const val RANGE_SHOWN_BELOW_KM = 100
private const val RANGE_ALERT_BELOW_KM = 50
private const val MAX_PLAUSIBLE_RANGE_KM = 1500f

/** The ice mark stays on up to this shown temperature once on, so a reading near 3 does not make it flicker. */
private const val ICE_ON_AT_OR_BELOW = 3
private const val ICE_OFF_AT_OR_ABOVE = 5

/** The ambient temperature in degrees Celsius, or null for a reading that is not one. */
internal fun hudInfoTemperature(raw: Float?): Float? = raw?.takeIf { it.isFinite() && it in -60f..80f }

/**
 * The range in km: the raw value over [divisor] (1 until the sensor is checked against the cluster).
 * Nothing for a value that is negative or implausible: a wrong unit must not raise a false alert. A
 * range of 0 is a range: the empty tank is the case that matters most.
 */
internal fun hudInfoRangeKm(raw: Float?, divisor: Int = 1): Int? {
    if (raw == null || !raw.isFinite() || divisor < 1) return null
    val km = raw / divisor
    return if (km >= 0f && km <= MAX_PLAUSIBLE_RANGE_KM) km.roundToInt() else null
}

/** The ice mark for the shown [temperature]: on at 3 or below, off again at 5 or above. */
internal fun hudInfoIcy(temperature: Int, wasIcy: Boolean): Boolean = when {
    temperature <= ICE_ON_AT_OR_BELOW -> true
    temperature >= ICE_OFF_AT_OR_ABOVE -> false
    else -> wasIcy
}

/**
 * What the corner shows: the temperature always, marked when the road may be icy, and the range only when
 * it is low. [icy] comes from [hudInfoIcy].
 */
internal fun hudInfoLines(info: HudInfo, icy: Boolean, iceLabel: String, kmLabel: String): List<HudInfoLine> = buildList {
    info.temperatureCelsius?.let { temperature ->
        val shown = temperature.roundToInt()
        val severity = when {
            shown <= 0 -> HudInfoSeverity.ALERT
            icy -> HudInfoSeverity.WARNING
            else -> HudInfoSeverity.NORMAL
        }
        add(HudInfoLine(if (icy) "$shown° $iceLabel" else "$shown°", severity))
    }
    info.rangeKm?.takeIf { it < RANGE_SHOWN_BELOW_KM }?.let { km ->
        add(HudInfoLine("$km $kmLabel", if (km < RANGE_ALERT_BELOW_KM) HudInfoSeverity.ALERT else HudInfoSeverity.WARNING))
    }
}
