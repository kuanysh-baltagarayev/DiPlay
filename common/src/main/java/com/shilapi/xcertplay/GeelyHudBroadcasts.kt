package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One turn as MConfig+ draws it on the HUD; null fields are not sent. */
internal data class GeelyHudTurn(
    val arrow: Int,
    val distance: String,
    val road: String,
    val arrival: String?,
    val remainingDistance: String?,
    val remainingTime: String?,
)

/**
 * Sends CarPlay's next turn to MConfig+ (`plus.monjaro`), which draws it on the Geely/ECARX
 * windshield HUD. MConfig+ takes navigation from the broadcasts of the patched Yandex Navigator
 * (`com.yandex.*`); from 48.1 it also takes `hud.NAVI_ALIVE` as a watchdog and `hud.NAVI_END` to
 * clear the HUD. Runs only where MConfig+ is installed; the turn comes from [CarPlayGlance].
 */
internal object GeelyHudBroadcasts {
    private const val TAG = "DiPlay-GeelyHud"
    const val MCONFIG_PACKAGE = "plus.monjaro"
    const val ACTION_MANEUVER = "com.yandex.MANEUVER"
    const val ACTION_DISTANCE_TO_TURN = "com.yandex.NIXT"
    const val ACTION_STREET = "com.yandex.NEXTSTREET"
    const val ACTION_ARRIVAL = "com.yandex.ARRIVAL"
    const val ACTION_REMAINING_DISTANCE = "com.yandex.DISTANCE"
    const val ACTION_REMAINING_TIME = "com.yandex.TIME"
    const val ACTION_ALIVE = "hud.NAVI_ALIVE"
    const val ACTION_END = "hud.NAVI_END"
    private const val TICK_MILLIS = 1_000L
    /** MConfig+ 48.1 clears the HUD after 10 s without `hud.NAVI_ALIVE`. */
    private const val RESEND_MILLIS = 3_000L
    private const val ARROW_PX = 128

    private val worker by lazy { Handler(HandlerThread("diplay-geely-hud").apply { start() }.looper) }
    @Volatile private var context: Context? = null
    private var sent: GeelyHudTurn? = null // worker thread
    private var sentAt = 0L // worker thread
    private var arrowRes = 0 // worker thread
    private var arrowBitmap: Bitmap? = null // worker thread

    private val tick = object : Runnable {
        override fun run() {
            runCatching { update() }.onFailure { Log.w(TAG, "HUD update failed", it) }
            worker.postDelayed(this, TICK_MILLIS)
        }
    }

    fun attach(appContext: Context) {
        if (context != null) return
        val app = appContext.applicationContext
        if (!installed(app)) return
        context = app
        worker.post(tick)
        Log.i(TAG, "sending CarPlay guidance to MConfig+")
    }

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MCONFIG_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun update() {
        val app = context ?: return
        val turn = turn(app, CarPlayGlance.hudSnapshot())
        val now = SystemClock.elapsedRealtime()
        if (turn == null) {
            if (sent != null) {
                send(app, Intent(ACTION_END))
                sent = null
                Log.i(TAG, "route ended")
            }
            return
        }
        if (turn == sent && now - sentAt < RESEND_MILLIS) return
        if (turn != sent) Log.i(TAG, "turn ${turn.distance} '${turn.road}'")
        sendTurn(app, turn)
        sent = turn
        sentAt = now
    }

    private fun sendTurn(context: Context, turn: GeelyHudTurn) {
        arrow(context, turn.arrow)?.let { send(context, Intent(ACTION_MANEUVER).putExtra("maneuver_bitmap", it)) }
        send(context, Intent(ACTION_DISTANCE_TO_TURN).putExtra("next_text", turn.distance))
        if (turn.road.isNotEmpty()) send(context, Intent(ACTION_STREET).putExtra("next_street", turn.road))
        turn.arrival?.let { send(context, Intent(ACTION_ARRIVAL).putExtra("Arrival_text", it)) }
        turn.remainingDistance?.let { send(context, Intent(ACTION_REMAINING_DISTANCE).putExtra("Distance_text", it)) }
        turn.remainingTime?.let { send(context, Intent(ACTION_REMAINING_TIME).putExtra("Time_text", it)) }
        send(context, Intent(ACTION_ALIVE))
    }

    private fun send(context: Context, intent: Intent) {
        context.sendBroadcast(intent.setPackage(MCONFIG_PACKAGE))
    }

    fun turn(context: Context, glance: CarPlayGlance.Snapshot): GeelyHudTurn? {
        val type = glance.maneuverType ?: return null
        if (!glance.connected) return null
        return GeelyHudTurn(
            arrow = NavigationWidgetUpdater.arrow(type, glance.drivingSide),
            distance = distance(context, glance.distanceMeters.toLong()),
            road = glance.road,
            arrival = glance.arrivalEpochSeconds?.takeIf { it > 0 }?.let {
                SimpleDateFormat("HH:mm", Locale.US).format(Date(it * 1000))
            },
            remainingDistance = glance.remainingMeters?.let { distance(context, it) },
            remainingTime = glance.remainingSeconds?.let { duration(context, it) },
        )
    }

    private fun arrow(context: Context, res: Int): Bitmap? {
        if (res == arrowRes) return arrowBitmap
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
        val bitmap = Bitmap.createBitmap(ARROW_PX, ARROW_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ARROW_PX, ARROW_PX)
        drawable.draw(Canvas(bitmap))
        arrowRes = res
        arrowBitmap = bitmap
        return bitmap
    }

    // As the navigation widget shows them.
    private fun distance(context: Context, meters: Long): String = when {
        meters < 1_000 -> context.getString(R.string.widget_distance_meters, ((meters + 5) / 10 * 10).toInt())
        meters < 10_000 -> context.getString(R.string.widget_distance_km_decimal, meters / 1000.0)
        else -> context.getString(R.string.widget_distance_km, ((meters + 500) / 1000).toInt())
    }

    private fun duration(context: Context, seconds: Long): String {
        val minutes = ((seconds + 30) / 60).toInt()
        return if (minutes < 60) context.getString(R.string.widget_minutes, minutes)
        else context.getString(R.string.widget_hours_minutes, minutes / 60, minutes % 60)
    }
}
