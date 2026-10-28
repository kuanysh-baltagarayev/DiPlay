package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * A test window for the Geely/ECARX windshield HUD, which is fed from the lower part of display 2
 * (1920x1080): MConfig+ draws its HUD there as an overlay at x 0, y 720, 728x190. The window is a
 * bright rectangle with a ruler in display pixels, so a look at the windshield shows where a given
 * rectangle lands and whether it keeps clear of MConfig+'s street, arrow, time and track lines.
 *
 * `am broadcast -a com.shilapi.xcertplay.GEELY_HUD_TEST -p com.shihab.diplay --ei x 0 --ei y 762
 * --ei w 728 --ei h 96 [--ei sec 120] [--ez on false]`: without `on` the window is shown (or moved);
 * `--ez on false` removes it. It removes itself after `sec` seconds (default 120): a bright
 * rectangle on the windshield must not stay while driving. Nothing is saved.
 */
internal object GeelyHudTest {
    private const val TAG = "DiPlay-GeelyHudTest"
    const val ACTION = "com.shilapi.xcertplay.GEELY_HUD_TEST"
    private const val DISPLAY_ID = 2

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var context: Context? = null // main thread
    private var window: WindowManager? = null // main thread
    private var view: HudTestView? = null // main thread
    private val autoHide = Runnable { hide() }

    fun attach(appContext: Context) {
        if (context != null) return
        val app = appContext.applicationContext
        context = app
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                main.post { handle(intent) }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun handle(intent: Intent) {
        if (!intent.getBooleanExtra("on", true)) {
            hide()
            return
        }
        val rect = hudTestRect(intent.getIntExtra("x", 0), intent.getIntExtra("y", 762),
            intent.getIntExtra("w", 728), intent.getIntExtra("h", 96))
        show(rect)
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, hudTestHideMillis(intent.getIntExtra("sec", 120)))
    }

    private fun show(rect: HudTestRect) {
        val app = context ?: return
        runCatching {
            val existing = view
            if (existing != null) {
                existing.rect = rect
                window?.updateViewLayout(existing, params(rect))
                existing.invalidate()
            } else {
                val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(DISPLAY_ID) ?: error("display $DISPLAY_ID is missing")
                val displayContext = windowContext(app, display)
                val manager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: error("no window manager on display $DISPLAY_ID")
                val created = HudTestView(displayContext, rect)
                manager.addView(created, params(rect))
                window = manager
                view = created
            }
            Log.i(TAG, "HUD test window ${rect.width}x${rect.height} at ${rect.x},${rect.y} on display $DISPLAY_ID")
        }.onFailure { Log.w(TAG, "HUD test window failed", it) }
    }

    private fun hide() {
        main.removeCallbacks(autoHide)
        val shown = view ?: return
        view = null
        runCatching { window?.removeView(shown) }
        window = null
        Log.i(TAG, "HUD test window removed")
    }

    private fun params(rect: HudTestRect) = WindowManager.LayoutParams(rect.width, rect.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.x
        y = rect.y
    }

    private fun windowContext(app: Context, display: Display): Context {
        val displayContext = app.createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
    }

    /** A bright fill, a white frame and a ruler in display pixels: a tick every 10, a number every 100. */
    private class HudTestView(context: Context, var rect: HudTestRect) : View(context) {
        private val fill = Paint().apply { color = Color.argb(150, 0, 200, 255) }
        private val line = Paint().apply { color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE }
        private val tick = Paint().apply { color = Color.WHITE; strokeWidth = 1f }
        private val text = Paint().apply { color = Color.WHITE; textSize = 18f; isAntiAlias = true }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, fill)
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, line)
            var px = 0
            while (px <= width) {
                val long = (rect.x + px) % 100 == 0
                canvas.drawLine(px.toFloat(), 0f, px.toFloat(), if (long) 14f else 7f, tick)
                if (long) canvas.drawText((rect.x + px).toString(), px + 3f, 32f, text)
                px += 10
            }
            var py = 0
            while (py <= height) {
                val long = (rect.y + py) % 50 == 0
                canvas.drawLine(0f, py.toFloat(), if (long) 14f else 7f, py.toFloat(), tick)
                py += 10
            }
            canvas.drawText("${rect.width}x${rect.height} @ ${rect.x},${rect.y}", 8f, h - 8f, text)
        }
    }
}

internal data class HudTestRect(val x: Int, val y: Int, val width: Int, val height: Int)

private const val DISPLAY_WIDTH = 1920
private const val DISPLAY_HEIGHT = 1080
private const val MIN_SIZE = 16

/** The rectangle kept on display 2 and at least [MIN_SIZE] pixels each way. */
internal fun hudTestRect(x: Int, y: Int, width: Int, height: Int): HudTestRect {
    val w = width.coerceIn(MIN_SIZE, DISPLAY_WIDTH)
    val h = height.coerceIn(MIN_SIZE, DISPLAY_HEIGHT)
    return HudTestRect(x.coerceIn(0, DISPLAY_WIDTH - w), y.coerceIn(0, DISPLAY_HEIGHT - h), w, h)
}

/** Seconds until the window removes itself: 5 s to 10 min, so it never stays on the windshield. */
internal fun hudTestHideMillis(seconds: Int): Long = seconds.coerceIn(5, 600) * 1_000L
