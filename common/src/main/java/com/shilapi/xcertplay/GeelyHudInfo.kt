package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.glance.CarPlayGlance
import kotlin.math.roundToInt

/**
 * A small readout in the bottom-right corner of the Geely/ECARX windshield HUD (display 2, below the
 * cluster's top 1920x720; MConfig+ draws its lines on the left and the head unit draws the speed at the
 * right centre, which a screenshot of display 2 does not show): the outside temperature, with a mark
 * when the road may be icy, and the range, but only when it is low. Both come from the car's own ECARX
 * sensors, as the cluster's do. It shows while a CarPlay session is connected.
 *
 * `am broadcast -a com.shilapi.xcertplay.GEELY_HUD_INFO -p com.shihab.diplay [--ez on false]
 * [--ei x 468 --ei y 814 --ei w 260 --ei h 96]` switches it off or moves it (display 2 pixels).
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
    private const val SENSOR_TEMPERATURE_AMBIENT = 1051392
    /** SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL. */
    private const val SENSOR_RANGE = 1054720
    private const val DISPLAY_ID = 2
    private const val TICK_MILLIS = 5_000L
    /** To be measured on the windshield with [GeelyHudTest]. */
    private val DEFAULT = HudRect(468, 814, 260, 96)

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy { Handler(HandlerThread("diplay-geely-hud-info").apply { start() }.looper) }
    @Volatile private var context: Context? = null
    @Volatile private var enabled = true
    private var window: WindowManager? = null // main thread
    private var view: HudInfoView? = null // main thread
    private var lastRaw: Pair<Float?, Float?>? = null // worker thread

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

    private fun configure(intent: Intent) {
        val app = context ?: return
        val old = rect(app)
        val next = clampHudRect(intent.getIntExtra("x", old.x), intent.getIntExtra("y", old.y),
            intent.getIntExtra("w", old.width), intent.getIntExtra("h", old.height))
        val on = if (intent.hasExtra("on")) intent.getBooleanExtra("on", true) else enabled
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on)
            .putInt(KEY_X, next.x).putInt(KEY_Y, next.y).putInt(KEY_W, next.width).putInt(KEY_H, next.height).apply()
        enabled = on
        hide() // the next tick draws it where it belongs
        Log.i(TAG, "HUD info ${if (on) "on" else "off"}, window $old -> $next")
    }

    /** Reads the sensors off the main thread (binder calls) and draws on it. */
    private val tick = object : Runnable {
        override fun run() {
            val app = context ?: return
            val lines = if (enabled && CarPlayGlance.snapshot().connected) hudInfoLines(read(app)) else emptyList()
            main.post { render(lines) }
            worker.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun read(app: Context): HudInfo {
        val rawTemperature = EcarxWheelSpeed.latest(app, SENSOR_TEMPERATURE_AMBIENT)
        val rawRange = EcarxWheelSpeed.latest(app, SENSOR_RANGE)
        val raw = rawTemperature to rawRange
        if (raw != lastRaw) {
            lastRaw = raw
            Log.i(TAG, "sensors: temperature raw $rawTemperature, range raw $rawRange")
        }
        return HudInfo(hudInfoTemperature(rawTemperature), hudInfoRangeKm(rawRange))
    }

    private fun render(lines: List<HudInfoLine>) {
        val app = context ?: return
        if (lines.isEmpty()) {
            hide()
            return
        }
        val shown = view
        if (shown != null) {
            shown.lines = lines
            shown.invalidate()
            return
        }
        runCatching {
            val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(DISPLAY_ID) ?: error("display $DISPLAY_ID is missing")
            val displayContext = GeelyClusterMap.windowContext(app, display)
            val manager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: error("no window manager on display $DISPLAY_ID")
            val rect = rect(app)
            val created = HudInfoView(displayContext, lines)
            manager.addView(created, WindowManager.LayoutParams(rect.width, rect.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rect.x
                y = rect.y
            })
            window = manager
            view = created
            Log.i(TAG, "HUD info window $rect shown")
        }.onFailure { Log.w(TAG, "HUD info window failed", it) }
    }

    private fun hide() {
        val shown = view ?: return
        view = null
        runCatching { window?.removeView(shown) }
        window = null
    }

    /** The lines, right-aligned from the top, in one colour each: white, amber for a warning, red for an alert. */
    private class HudInfoView(context: Context, var lines: List<HudInfoLine>) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }

        override fun onDraw(canvas: Canvas) {
            val rowHeight = height / 2f
            paint.textSize = rowHeight * 0.8f
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

/** The ambient temperature in degrees Celsius, or null for a reading that is not one. */
internal fun hudInfoTemperature(raw: Float?): Float? = raw?.takeIf { it.isFinite() && it in -60f..80f }

/**
 * The range in km. The unit is read from the size: a value up to 1500 is km, a larger one metres. Not
 * verified against the cluster yet.
 */
internal fun hudInfoRangeKm(raw: Float?): Int? {
    if (raw == null || !raw.isFinite()) return null
    return when {
        raw in 0f..MAX_PLAUSIBLE_RANGE_KM -> raw.roundToInt()
        raw / 1000f in 0f..MAX_PLAUSIBLE_RANGE_KM -> (raw / 1000f).roundToInt()
        else -> null
    }
}

/**
 * What the corner shows: the temperature always, marked when the road may be icy (the figure shown is 3
 * or below, so the mark always agrees with it), and the range only when it is low.
 */
internal fun hudInfoLines(info: HudInfo): List<HudInfoLine> = buildList {
    info.temperatureCelsius?.let { temperature ->
        val shown = temperature.roundToInt()
        val severity = when {
            shown <= 0 -> HudInfoSeverity.ALERT
            shown <= 3 -> HudInfoSeverity.WARNING
            else -> HudInfoSeverity.NORMAL
        }
        add(HudInfoLine(if (shown <= 3) "$shown° ЛЁД" else "$shown°", severity))
    }
    info.rangeKm?.takeIf { it < RANGE_SHOWN_BELOW_KM }?.let { km ->
        add(HudInfoLine("$km км", if (km < RANGE_ALERT_BELOW_KM) HudInfoSeverity.ALERT else HudInfoSeverity.WARNING))
    }
}
