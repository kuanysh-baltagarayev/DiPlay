package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * CarPlay's dashboard map (stream 111) on the Geely/ECARX instrument cluster. Display 2 (1920x1080)
 * feeds the windshield HUD from its lower part; in the DIM's map mode the cluster shows its top
 * 1920x720 and keeps drawing its own speed, rpm, gear, fuel, clock and warnings over it, as over its
 * own navigation map. The map fills a window there ([Layout], by default all of it) and the iPhone is
 * asked for a stream of exactly that size. No safe area is set: the cluster's icons and texts change
 * with the car's state, and they are drawn over the map, so they stay visible whatever lies under them.
 *
 * DiPlay switches the DIM itself ([EcarxDim]) when the window opens and closes, unless another app
 * (a cluster dashboard such as mSpeed) already holds map mode: then the mode is left as it is. The
 * window is opened by MConfig+ 48.2's action "DiAuto/DiPlay карта на приборке", which sends
 * [ACTION_SHOW], or by [ACTION_TOGGLE]; DiPlay answers
 * [ACTION_SHOWN]. [ACTION_HIDE] removes the window and sends [ACTION_END]; a session that ends while
 * the map is shown sends [ACTION_END] and gives the cluster its stock view back, and so does the next
 * start after a process that died with the map shown (without END: nothing is listening then).
 * When something else gives the cluster its stock view back (MConfig+'s own toggle, the cluster
 * menu, the car), the global NaviMode setting changes, the window closes too and END is sent, so
 * DiPlay does not keep a map the cluster no longer shows. While the window is not shown the iPhone
 * is asked not to draw the cluster map (stopUI). [ACTION_LAYOUT] moves or resizes the window (extras x, y, w, h);
 * the stream follows at the next connection.
 */
internal object GeelyClusterMap {
    private const val TAG = "DiPlay-GeelyClusterMap"
    const val ACTION_SHOW = "hud.AAMAP_SHOW"
    const val ACTION_HIDE = "hud.AAMAP_HIDE"
    const val ACTION_SHOWN = "hud.AAMAP_SHOWN"
    const val ACTION_END = "hud.AAMAP_END"
    const val ACTION_TOGGLE = "com.shilapi.xcertplay.GEELY_CLUSTER_MAP_TOGGLE"
    const val ACTION_LAYOUT = "com.shilapi.xcertplay.GEELY_CLUSTER_MAP_LAYOUT"
    /** The part of display 2 that reaches the cluster. */
    const val CANVAS_WIDTH = 1920
    const val CANVAS_HEIGHT = 720
    private const val PREFS = "geely_cluster_map"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_W = "w"
    private const val KEY_H = "h"
    /** Set while DiPlay holds the cluster in map mode, so a killed process is undone at the next start. */
    private const val KEY_HELD = "dim_held"
    /** ECARX names every extra display "HDMI", so the canvas that reaches the cluster is found by id. */
    private const val DISPLAY_ID = 2
    /** BYD's own cluster display; DiPlay's BYD cluster output owns those head units. */
    private const val BYD_CLUSTER_DISPLAY = "fission_bg"
    private const val MIRROR_KEY = "geely-cluster"
    /** ECARX keeps the cluster's navigation mode here; 3 is the map mode DiPlay switches to. */
    private const val NAVI_MODE_SETTING = "NaviMode"
    /** A mode change this soon after DiPlay's own switch is DiPlay's, not someone else's. */
    private const val OWN_SWITCH_GRACE_MILLIS = 1_500L
    private const val NAVI_MODE_CHECK_DELAY_MILLIS = 500L
    /** The decoder may still write to the surface right after the window goes. */
    private const val SURFACE_RELEASE_DELAY_MILLIS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Handler(HandlerThread("diplay-geely-cluster").apply { start() }.looper) }
    @Volatile private var available: Boolean? = null
    @Volatile private var wanted = false
    private var context: Context? = null // main thread
    private var connected = false // main thread
    private var window: WindowManager? = null // main thread
    private var root: FrameLayout? = null // main thread
    private var turnCard: ClusterTurnCardView? = null // main thread
    private var card = TurnCard(null, 0, 0, 0, 0, false) // main thread
    private var ownSwitchAt = 0L // main thread, elapsedRealtime of DiPlay's last DIM switch
    private var ownsDim = false // main thread: DiPlay switched the cluster to map mode for this window

    /**
     * The map window on the cluster canvas, by default all of it. Width and height are multiples of 8,
     * as the stream sizes are. A smaller window leaves black around the map: on a Monjaro with the
     * seat belt fastened the stock readings leave x 0-1820, y 115-555 free (photos 2026-10-22).
     */
    data class Layout(val x: Int, val y: Int, val width: Int, val height: Int) {
        companion object {
            val DEFAULT = Layout(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
            private const val MIN = 64

            fun sanitize(x: Int, y: Int, width: Int, height: Int): Layout {
                val w = (width.coerceIn(MIN, CANVAS_WIDTH) / 8) * 8
                val h = (height.coerceIn(MIN, CANVAS_HEIGHT) / 8) * 8
                return Layout(x.coerceIn(0, CANVAS_WIDTH - w), y.coerceIn(0, CANVAS_HEIGHT - h), w, h)
            }
        }
    }

    fun layout(context: Context): Layout {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Layout.DEFAULT
        return Layout.sanitize(prefs.getInt(KEY_X, d.x), prefs.getInt(KEY_Y, d.y), prefs.getInt(KEY_W, d.width),
            prefs.getInt(KEY_H, d.height))
    }

    private fun saveLayout(context: Context, layout: Layout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_X, layout.x)
            .putInt(KEY_Y, layout.y).putInt(KEY_W, layout.width).putInt(KEY_H, layout.height).apply()
    }

    private fun setHeld(context: Context, held: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HELD, held).apply()
    }

    private data class TurnCard(val guidance: ClusterTurnGuidance?, val x: Int, val y: Int,
        val size: Int, val opacity: Int, val night: Boolean)

    /** Whether this head unit gets the cluster map this way: an ECARX canvas, the DIM SDK and MConfig+. */
    fun available(context: Context): Boolean = available ?: synchronized(this) {
        available ?: detect(context.applicationContext ?: context).also {
            available = it
            Log.i(TAG, "Geely cluster map ${if (it) "available" else "not available"}")
        }
    }

    fun attach(appContext: Context) {
        if (context != null || !available(appContext)) return
        val app = appContext.applicationContext
        context = app
        releaseStale(app)
        BydNavigationOutputs.clusterUiFilter = { requested -> requested && wanted }
        CarPlayGlance.connectionListener = { next -> main.post { onConnected(next) } }
        MapMirrors.addStreamListener { active -> if (active && !wanted) control(false) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SHOW -> showRequested()
                    ACTION_HIDE -> hideRequested()
                    ACTION_TOGGLE -> toggle()
                    ACTION_LAYOUT -> relayout(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
            addAction(ACTION_TOGGLE)
            addAction(ACTION_LAYOUT)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(NAVI_MODE_SETTING), false,
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        main.removeCallbacks(checkNaviMode)
                        main.postDelayed(checkNaviMode, NAVI_MODE_CHECK_DELAY_MILLIS)
                    }
                })
        }.onFailure { Log.w(TAG, "cannot watch the cluster's navigation mode", it) }
        Log.i(TAG, "waiting for the cluster map to be asked for; window ${layout(app)}")
    }

    /** Closes the window when the cluster left map mode without DiPlay asking for it. */
    private val checkNaviMode = Runnable {
        val app = context ?: return@Runnable
        if (root == null || SystemClock.elapsedRealtime() - ownSwitchAt < OWN_SWITCH_GRACE_MILLIS) return@Runnable
        val mode = naviMode(app) ?: return@Runnable
        if (mode == EcarxDim.NAVI_MODE_FULL) return@Runnable
        Log.i(TAG, "the cluster left map mode (NaviMode $mode) without DiPlay; closing the map window")
        wanted = false
        control(false)
        close(restoreCluster = false)
        send(ACTION_END)
    }

    /** Shows the map when it is hidden and hides it when it is shown. Main thread. */
    fun toggle() {
        if (wanted) hideRequested() else showRequested()
    }

    private fun showRequested() {
        if (!connected) {
            Log.i(TAG, "cluster map asked for without a CarPlay session; ignored")
            return
        }
        wanted = true
        // Without the window (no overlay permission, no display 2) the iPhone must not keep
        // drawing the map, and MConfig+ gets no answer.
        if (root != null) send(ACTION_SHOWN) else if (!show()) wanted = false
    }

    private fun hideRequested() {
        if (!wanted) return
        wanted = false
        control(false)
        close()
        send(ACTION_END)
    }

    private fun relayout(intent: Intent) {
        val app = context ?: return
        val old = layout(app)
        val next = Layout.sanitize(intent.getIntExtra("x", old.x), intent.getIntExtra("y", old.y),
            intent.getIntExtra("w", old.width), intent.getIntExtra("h", old.height))
        saveLayout(app, next)
        val frame = root
        val manager = window
        if (frame != null && manager != null) {
            runCatching { manager.updateViewLayout(frame, windowParams(next)) }
        }
        Log.i(TAG, "cluster map window $old -> $next; the stream size follows at the next connection")
    }

    /** A process killed while it held the cluster leaves it on display 2's last frame or black. */
    private fun releaseStale(app: Context) {
        if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HELD, false)) return
        Log.i(TAG, "a previous run left the cluster in map mode (naviMode ${EcarxDim.naviMode(app)}); giving it back")
        EcarxDim.switch(app, false)
        setHeld(app, false)
    }

    /** The turn card DiPlay draws over the dashboard map, as on the other cluster outputs. */
    fun setTurnCard(guidance: ClusterTurnGuidance?, x: Int, y: Int, size: Int, opacity: Int, night: Boolean) {
        main.post {
            card = TurnCard(guidance, x, y, size, opacity, night)
            applyTurnCard()
        }
    }

    private fun detect(context: Context): Boolean = runCatching {
        if (!GeelyHudBroadcasts.installed(context) || !EcarxDim.present()) return false
        val displays = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.displays
            ?: return false
        displays.none { it.name?.contains(BYD_CLUSTER_DISPLAY) == true } &&
            displays.any { it.displayId == DISPLAY_ID }
    }.getOrDefault(false)

    private fun onConnected(next: Boolean) {
        connected = next
        if (next) return
        val shown = wanted
        wanted = false
        close()
        if (shown) send(ACTION_END)
    }

    /** Opens the window and answers MConfig+; false when it could not be opened. */
    private fun show(): Boolean {
        val app = context ?: return false
        if (root != null) return true
        return runCatching {
            val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(DISPLAY_ID) ?: error("display $DISPLAY_ID is missing")
            val displayContext = windowContext(app, display)
            val manager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: error("no window manager on display $DISPLAY_ID")
            val frame = FrameLayout(displayContext).apply { setBackgroundColor(Color.BLACK) }
            frame.addView(TextureView(displayContext).apply { surfaceTextureListener = MirrorSurface() },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val cardView = ClusterTurnCardView(displayContext).apply { visibility = View.GONE }
            frame.addView(cardView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val layout = layout(app)
            manager.addView(frame, windowParams(layout))
            window = manager
            root = frame
            turnCard = cardView
            applyTurnCard()
            // A cluster dashboard (mSpeed, Monjaro Dashboard) may already hold map mode to draw on display 2.
            // Then DiPlay only lays its window over it and leaves the mode alone on hide, as MConfig+ does.
            ownsDim = naviMode(app) != EcarxDim.NAVI_MODE_FULL
            if (ownsDim) {
                setHeld(app, true)
                ownSwitchAt = SystemClock.elapsedRealtime()
                EcarxDim.switch(app, true)
            }
            send(ACTION_SHOWN)
            Log.i(TAG, "cluster map window $layout shown on display $DISPLAY_ID; " +
                if (ownsDim) "cluster switched to map mode" else "cluster already in map mode, left as it is")
        }.onFailure { Log.w(TAG, "cluster map window failed", it) }.isSuccess
    }

    private fun windowParams(layout: Layout) = WindowManager.LayoutParams(layout.width, layout.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        x = layout.x
        y = layout.y
    }

    private fun windowContext(app: Context, display: Display): Context {
        val displayContext = app.createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
    }

    /**
     * [restoreCluster] false: the cluster already left map mode on its own, so it is left as it is. The
     * stock view also stays when another app held map mode before the window opened.
     */
    private fun close(restoreCluster: Boolean = true) {
        val frame = root
        val manager = window
        root = null
        window = null
        turnCard = null
        val owned = ownsDim
        ownsDim = false
        if (frame == null || manager == null) return
        runCatching { manager.removeViewImmediate(frame) }
        context?.let { app ->
            if (restoreCluster && owned) {
                ownSwitchAt = SystemClock.elapsedRealtime()
                EcarxDim.switch(app, false)
            }
            setHeld(app, false)
        }
        Log.i(TAG, "cluster map window closed")
    }

    private fun naviMode(app: Context): Int? =
        runCatching { Settings.Global.getInt(app.contentResolver, NAVI_MODE_SETTING) }.getOrNull()

    private fun applyTurnCard() {
        val view = turnCard ?: return
        view.setLayout(card.x, card.y, card.size)
        view.setOpacity(card.opacity)
        view.setNightMode(card.night)
        view.setGuidance(card.guidance)
    }

    /** showUI/stopUI go through the session's command worker, never the main thread. */
    private fun control(shown: Boolean) {
        worker.post { runCatching { BydNavigationOutputs.clusterStreamControl(shown) } }
    }

    /**
     * To every app, as the MConfig+ author's mod does: besides MConfig+, apps such as mSpeed follow
     * SHOWN and END to clear the cluster area under the map.
     */
    private fun send(action: String) {
        runCatching { context?.sendBroadcast(Intent(action)) }
    }

    private class MirrorSurface : TextureView.SurfaceTextureListener {
        private var surface: Surface? = null

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val next = Surface(texture)
            surface = next
            MapMirrors.set(MIRROR_KEY, next)
            if (wanted) control(true)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            MapMirrors.set(MIRROR_KEY, null)
            val old = surface
            surface = null
            main.postDelayed({
                runCatching {
                    old?.release()
                    texture.release()
                }
            }, SURFACE_RELEASE_DELAY_MILLIS)
            return false
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }
}
