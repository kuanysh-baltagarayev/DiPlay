package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
 * CarPlay's dashboard map (stream 111) on the Geely/ECARX instrument cluster, switched by MConfig+
 * (`plus.monjaro`). Display 2 (1920x1080) feeds the windshield HUD from its lower part; in the DIM's
 * map mode the cluster shows its top 1920x720. MConfig+ 48.2's action "DiAuto/DiPlay карта на
 * приборке" sends [ACTION_SHOW]: DiPlay puts the stream into an overlay window there and answers
 * [ACTION_SHOWN], and MConfig+ then switches the DIM to its map mode. [ACTION_HIDE] removes the
 * window; a session that ends while the map is shown sends [ACTION_END]. While the window is not
 * shown the iPhone is asked not to draw the cluster map (stopUI).
 */
internal object GeelyClusterMap {
    private const val TAG = "DiPlay-GeelyClusterMap"
    const val ACTION_SHOW = "hud.AAMAP_SHOW"
    const val ACTION_HIDE = "hud.AAMAP_HIDE"
    const val ACTION_SHOWN = "hud.AAMAP_SHOWN"
    const val ACTION_END = "hud.AAMAP_END"
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720
    /** ECARX names every extra display "HDMI", so the canvas that reaches the cluster is found by id. */
    private const val DISPLAY_ID = 2
    /** BYD's own cluster display; DiPlay's BYD cluster output owns those head units. */
    private const val BYD_CLUSTER_DISPLAY = "fission_bg"
    private const val MIRROR_KEY = "geely-cluster"
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

    private data class TurnCard(val guidance: ClusterTurnGuidance?, val x: Int, val y: Int,
        val size: Int, val opacity: Int, val night: Boolean)

    /** Whether this head unit gets the cluster map this way: an ECARX canvas and MConfig+ to switch the DIM. */
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
        BydNavigationOutputs.clusterUiFilter = { requested -> requested && wanted }
        CarPlayGlance.connectionListener = { next -> main.post { onConnected(next) } }
        MapMirrors.addStreamListener { active -> if (active && !wanted) control(false) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SHOW -> if (connected) {
                        wanted = true
                        if (root == null) show() else send(ACTION_SHOWN)
                    }
                    ACTION_HIDE -> if (wanted) {
                        wanted = false
                        control(false)
                        close()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "waiting for MConfig+ to show the cluster map")
    }

    /** The turn card DiPlay draws over the dashboard map, as on the other cluster outputs. */
    fun setTurnCard(guidance: ClusterTurnGuidance?, x: Int, y: Int, size: Int, opacity: Int, night: Boolean) {
        main.post {
            card = TurnCard(guidance, x, y, size, opacity, night)
            applyTurnCard()
        }
    }

    private fun detect(context: Context): Boolean = runCatching {
        if (!GeelyHudBroadcasts.installed(context)) return false
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

    private fun show() {
        val app = context ?: return
        if (root != null) return
        runCatching {
            val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(DISPLAY_ID) ?: return
            val displayContext = windowContext(app, display)
            val manager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val frame = FrameLayout(displayContext).apply { setBackgroundColor(Color.BLACK) }
            frame.addView(TextureView(displayContext).apply { surfaceTextureListener = MirrorSurface() },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val cardView = ClusterTurnCardView(displayContext).apply { visibility = View.GONE }
            frame.addView(cardView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val params = WindowManager.LayoutParams(STREAM_WIDTH, STREAM_HEIGHT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            manager.addView(frame, params)
            window = manager
            root = frame
            turnCard = cardView
            applyTurnCard()
            send(ACTION_SHOWN)
            Log.i(TAG, "cluster map window shown on display $DISPLAY_ID")
        }.onFailure { Log.w(TAG, "cluster map window failed", it) }
    }

    private fun windowContext(app: Context, display: Display): Context {
        val displayContext = app.createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
    }

    private fun close() {
        val frame = root
        val manager = window
        root = null
        window = null
        turnCard = null
        if (frame == null || manager == null) return
        runCatching { manager.removeViewImmediate(frame) }
        Log.i(TAG, "cluster map window closed")
    }

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

    private fun send(action: String) {
        runCatching { context?.sendBroadcast(Intent(action).setPackage(GeelyHudBroadcasts.MCONFIG_PACKAGE)) }
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
