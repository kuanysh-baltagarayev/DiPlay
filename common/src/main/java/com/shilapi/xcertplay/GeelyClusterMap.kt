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
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
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
 *
 * HUD mode (on by default, [ACTION_HUD_MODE]) uses the same stream for a small map in the free band of the
 * windshield HUD instead: below the cluster's top 1920x720 on display 2, between MConfig+'s street and
 * track lines. The iPhone is asked for a stream a few times that window's size (it takes a stream of the
 * window's own size for a tiny screen with huge controls), shown scaled down. SRC (MConfig+ action 38)
 * opens and closes the window, which also closes with the session; with `auto` it follows the route by
 * itself instead. The cluster's map mode is never touched (mSpeed may hold it) and nothing is announced
 * to MConfig+ or mSpeed. The cluster map itself is not available in this mode.
 */
internal object GeelyClusterMap {
    private const val TAG = "DiPlay-GeelyClusterMap"
    const val ACTION_SHOW = "hud.AAMAP_SHOW"
    const val ACTION_HIDE = "hud.AAMAP_HIDE"
    const val ACTION_SHOWN = "hud.AAMAP_SHOWN"
    const val ACTION_END = "hud.AAMAP_END"
    const val ACTION_TOGGLE = "com.shilapi.xcertplay.GEELY_CLUSTER_MAP_TOGGLE"
    const val ACTION_LAYOUT = "com.shilapi.xcertplay.GEELY_CLUSTER_MAP_LAYOUT"
    /**
     * `--ez on` (default true) turns the HUD map on or off; `--ez auto` (default false) opens the window by
     * itself while a route is guided instead of by SRC; `--ei x y w h` moves it (display 2 pixels).
     * The iPhone draws a stream `k` times the window (default 4: it takes a 192x96 display for a tiny
     * screen with huge controls), shown scaled down in the window; `--es content map|instruments`,
     * `--ei mx my` (where the iPhone puts the car, percent of the stream) and `--ei sa` (percent of each
     * edge kept clear) tune its view. They apply at the next connection.
     */
    const val ACTION_HUD_MODE = "com.shilapi.xcertplay.GEELY_HUD_MAP_MODE"
    /** The part of display 2 that reaches the cluster. */
    const val CANVAS_WIDTH = 1920
    const val CANVAS_HEIGHT = 720
    private const val PREFS = "geely_cluster_map"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_W = "w"
    private const val KEY_H = "h"
    private const val KEY_HUD_MODE = "hud_mode"
    private const val KEY_HUD_AUTO = "hud_auto"
    private const val KEY_HUD_X = "hud_x"
    private const val KEY_HUD_Y = "hud_y"
    private const val KEY_HUD_W = "hud_w"
    private const val KEY_HUD_H = "hud_h"
    private const val KEY_HUD_K = "hud_k"
    private const val KEY_HUD_CONTENT = "hud_content"
    private const val KEY_HUD_MX = "hud_mx"
    private const val KEY_HUD_MY = "hud_my"
    private const val KEY_HUD_SA = "hud_sa"
    /** The free band of MConfig+'s HUD between its street and track lines (tested on the windshield 2026-10-28). */
    private val HUD_DEFAULT = HudRect(0, 762, 192, 96)
    /** Where the iPhone puts the car on the HUD map, percent from the left: about the middle of the small window (tested 2026-10-28). */
    private const val HUD_MARKER_X = 30
    private const val HUD_TICK_MILLIS = 1_000L
    /** After a failed attempt to open the HUD window, to log once and not every second. */
    private const val HUD_RETRY_MILLIS = 30_000L
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
    @Volatile private var checkedAt = 0L
    @Volatile private var wanted = false
    /** The mode of the current session, fixed when it connects: the stream was asked for in that mode. */
    @Volatile private var activeHud = false
    /** HUD mode only: the window follows the route by itself instead of waiting for SRC. */
    @Volatile private var hudAuto = false
    private var hudRetryAt = 0L // main thread, elapsedRealtime before which the HUD window is not opened again
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
            // The broadcast is open to every app: a window too small to read must not be saved.
            internal const val MIN_WIDTH = 320
            internal const val MIN_HEIGHT = 120

            fun sanitize(x: Int, y: Int, width: Int, height: Int): Layout {
                val w = (width.coerceIn(MIN_WIDTH, CANVAS_WIDTH) / 8) * 8
                val h = (height.coerceIn(MIN_HEIGHT, CANVAS_HEIGHT) / 8) * 8
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

    /** Whether the stream and window are the HUD's small map rather than the cluster's. */
    fun hudMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HUD_MODE, true)

    private fun hudAuto(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HUD_AUTO, false)

    /** The HUD map window and the size of its stream, in display 2 pixels. */
    fun hudRect(context: Context): HudRect {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = HUD_DEFAULT
        return clampHudRect(prefs.getInt(KEY_HUD_X, d.x), prefs.getInt(KEY_HUD_Y, d.y),
            prefs.getInt(KEY_HUD_W, d.width), prefs.getInt(KEY_HUD_H, d.height))
    }

    /** How the iPhone is asked to draw the HUD map: stream size as a multiple of the window, view, car place. */
    data class HudStream(val factor: Int, val content: CarPlayClusterDisplay.Content, val markerX: Int,
        val markerY: Int, val edge: Int)

    fun hudStream(context: Context): HudStream {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val content = prefs.getString(KEY_HUD_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: CarPlayClusterDisplay.Content.MAP
        return HudStream(prefs.getInt(KEY_HUD_K, 4).coerceIn(1, 8), content,
            prefs.getInt(KEY_HUD_MX, HUD_MARKER_X).coerceIn(5, 95), prefs.getInt(KEY_HUD_MY, 50).coerceIn(5, 95),
            prefs.getInt(KEY_HUD_SA, 0).coerceIn(0, 40))
    }

    private fun setHeld(context: Context, held: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HELD, held).apply()
    }

    private data class TurnCard(val guidance: ClusterTurnGuidance?, val x: Int, val y: Int,
        val size: Int, val opacity: Int, val night: Boolean)

    /** Whether this head unit gets the cluster map this way: an ECARX canvas, the DIM SDK and MConfig+. */
    fun available(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        cachedAvailability(available, checkedAt, now)?.let { return it }
        return synchronized(this) {
            cachedAvailability(available, checkedAt, now)
                ?: detect(context.applicationContext ?: context).also {
                    if (it != available) Log.i(TAG, "Geely cluster map ${if (it) "available" else "not available"}")
                    available = it
                    checkedAt = now
                }
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
                    ACTION_HUD_MODE -> setHudMode(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
            addAction(ACTION_TOGGLE)
            addAction(ACTION_LAYOUT)
            addAction(ACTION_HUD_MODE)
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
        activeHud = hudMode(app)
        hudAuto = hudAuto(app)
        main.postDelayed(hudTick, HUD_TICK_MILLIS)
        Log.i(TAG, if (activeHud) "HUD map mode: window ${hudRect(app)}, opens by SRC" +
            if (hudAuto) " and by itself while a route is guided" else ""
            else "waiting for the cluster map to be asked for; window ${layout(app)}")
    }

    /**
     * HUD mode with `auto`: the map window follows the route, open while the iPhone guides one and closed
     * otherwise. Without it SRC (MConfig+ action 38, which sends [ACTION_SHOW] and [ACTION_HIDE]) opens
     * and closes the window, as for the cluster map.
     */
    private val hudTick = object : Runnable {
        override fun run() {
            val app = context ?: return
            if (activeHud && hudAuto) {
                val routeActive = connected && CarPlayGlance.hudSnapshot().maneuverType != null
                val now = SystemClock.elapsedRealtime()
                if (routeActive && !wanted && now >= hudRetryAt) {
                    wanted = true
                    if (!show()) {
                        wanted = false
                        hudRetryAt = now + HUD_RETRY_MILLIS
                    }
                } else if (!routeActive && wanted) {
                    wanted = false
                    control(false)
                    close()
                }
            }
            main.postDelayed(this, HUD_TICK_MILLIS)
        }
    }

    private fun setHudMode(intent: Intent) {
        val app = context ?: return
        val old = hudRect(app)
        val next = clampHudRect(intent.getIntExtra("x", old.x), intent.getIntExtra("y", old.y),
            intent.getIntExtra("w", old.width), intent.getIntExtra("h", old.height))
        val on = if (intent.hasExtra("on")) intent.getBooleanExtra("on", true) else hudMode(app)
        val stream = hudStream(app)
        val content = when (intent.getStringExtra("content")?.lowercase()) {
            "map" -> CarPlayClusterDisplay.Content.MAP
            "instruments" -> CarPlayClusterDisplay.Content.INSTRUMENTS
            else -> stream.content
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HUD_MODE, on)
            .putInt(KEY_HUD_X, next.x).putInt(KEY_HUD_Y, next.y).putInt(KEY_HUD_W, next.width)
            .putInt(KEY_HUD_H, next.height).putInt(KEY_HUD_K, intent.getIntExtra("k", stream.factor))
            .putString(KEY_HUD_CONTENT, content.name)
            .putInt(KEY_HUD_MX, intent.getIntExtra("mx", stream.markerX))
            .putInt(KEY_HUD_MY, intent.getIntExtra("my", stream.markerY))
            .putInt(KEY_HUD_SA, intent.getIntExtra("sa", stream.edge))
            .putBoolean(KEY_HUD_AUTO, if (intent.hasExtra("auto")) intent.getBooleanExtra("auto", false) else hudAuto(app)).apply()
        hudAuto = hudAuto(app)
        Log.i(TAG, "HUD map mode ${if (on) "on" else "off"}, window $old -> $next; it applies at the next connection")
    }

    /** Closes the window when the cluster left map mode without DiPlay asking for it. */
    private val checkNaviMode = Runnable {
        val app = context ?: return@Runnable
        if (activeHud) return@Runnable
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
        if (activeHud) {
            // The HUD map is not announced: mSpeed would clear the cluster's area under a map that is not there.
            if (root == null && !show()) wanted = false
            return
        }
        // Without the window (no overlay permission, no display 2) the iPhone must not keep
        // drawing the map, and MConfig+ gets no answer.
        if (root != null) send(ACTION_SHOWN) else if (!show()) wanted = false
    }

    private fun hideRequested() {
        if (!wanted) return
        wanted = false
        control(false)
        close()
        if (!activeHud) send(ACTION_END)
    }

    private fun relayout(intent: Intent) {
        val app = context ?: return
        if (activeHud) {
            Log.i(TAG, "cluster map layout asked for in HUD mode; ignored (GEELY_HUD_MAP_MODE moves the HUD map)")
            return
        }
        val old = layout(app)
        val next = Layout.sanitize(intent.getIntExtra("x", old.x), intent.getIntExtra("y", old.y),
            intent.getIntExtra("w", old.width), intent.getIntExtra("h", old.height))
        saveLayout(app, next)
        val frame = root
        val manager = window
        if (frame != null && manager != null) {
            runCatching { manager.updateViewLayout(frame, windowParams(next.x, next.y, next.width, next.height)) }
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
        if (next) {
            activeHud = context?.let { hudMode(it) } ?: false
            return
        }
        val shown = wanted
        wanted = false
        close()
        if (shown && !activeHud) send(ACTION_END)
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
            if (activeHud) {
                val rect = hudRect(app)
                manager.addView(frame, windowParams(rect.x, rect.y, rect.width, rect.height, touchable = false))
                window = manager
                root = frame
                ownsDim = false
                Log.i(TAG, "HUD map window $rect shown on display $DISPLAY_ID")
                return@runCatching
            }
            val cardView = ClusterTurnCardView(displayContext).apply { visibility = View.GONE }
            frame.addView(cardView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val layout = layout(app)
            manager.addView(frame, windowParams(layout.x, layout.y, layout.width, layout.height))
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

    internal fun windowParams(x: Int, y: Int, width: Int, height: Int, touchable: Boolean = true) =
        WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    /** The context to build a view for display 2 and the window manager to add it with. */
    internal class DisplayWindow(val context: Context, val manager: WindowManager)

    /** Display 2's overlay context and window manager; throws when the display or its manager is missing. */
    internal fun displayWindow(app: Context): DisplayWindow {
        val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(DISPLAY_ID) ?: error("display $DISPLAY_ID is missing")
        val displayContext = windowContext(app, display)
        val manager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: error("no window manager on display $DISPLAY_ID")
        return DisplayWindow(displayContext, manager)
    }

    /** A context for an overlay window on [display]; shared by the HUD windows. */
    internal fun windowContext(app: Context, display: Display): Context {
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

private const val RECHECK_MILLIS = 10_000L

/**
 * A yes holds for the process. A no is asked again after a while: at boot display 2 or the ECARX
 * classes may not be up yet, and MConfig+ may be installed later.
 */
internal fun cachedAvailability(known: Boolean?, checkedAt: Long, now: Long): Boolean? = when {
    known == true -> true
    known == false && now - checkedAt < RECHECK_MILLIS -> false
    else -> null
}

/** A rectangle of display 2, in its pixels (1920x1080). */
internal data class HudRect(val x: Int, val y: Int, val width: Int, val height: Int)

private const val DISPLAY_WIDTH = 1920
private const val DISPLAY_HEIGHT = 1080
private const val MIN_SIZE = 16

/** The rectangle kept on display 2 and at least [MIN_SIZE] pixels each way. */
internal fun clampHudRect(x: Int, y: Int, width: Int, height: Int): HudRect {
    val w = width.coerceIn(MIN_SIZE, DISPLAY_WIDTH)
    val h = height.coerceIn(MIN_SIZE, DISPLAY_HEIGHT)
    return HudRect(x.coerceIn(0, DISPLAY_WIDTH - w), y.coerceIn(0, DISPLAY_HEIGHT - h), w, h)
}

/**
 * The stream is [requested] times the HUD window, but never larger than display 2: a stream the hardware
 * decoder cannot take would fail the whole session.
 */
internal fun hudStreamFactor(requested: Int, width: Int, height: Int): Int =
    minOf(requested, DISPLAY_WIDTH / width.coerceAtLeast(1), DISPLAY_HEIGHT / height.coerceAtLeast(1)).coerceAtLeast(1)
