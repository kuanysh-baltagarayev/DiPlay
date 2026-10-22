package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Switches the ECARX instrument cluster (DIM) between its stock view and the picture of display 2's
 * top 1920x720, as MConfig+ 48.2 and ellusive3/MConfig do. The ECARX SDK is in the head unit's boot
 * classpath (`/system/framework/ecarx.adaptapi.jar`), so it is reached by reflection and DiPlay
 * builds without it.
 *
 * Measured on a Geely Monjaro (KX11, Android 9), 2026-10-22: `switchNaviMode(3)` followed about 100 ms
 * later by the DIM message `(2, 8, 8, {1})` shows display 2 on the cluster, and the cluster keeps
 * drawing its own speed, rpm, gear, fuel and warnings over it at the edges. The mode without the
 * message, or with the message about 1.5 s late, leaves the cluster black; after that a plain
 * `switchNaviMode(1)` did not bring the stock view back, while mode 1 followed by the same message
 * did. So both directions always send the pair.
 */
internal object EcarxDim {
    private const val TAG = "DiPlay-EcarxDim"
    private const val DIM_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val DIM_MENU = "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction"
    private const val DIM_PROTOCOL = "ecarx.dimprotocol.DIMProtocolManager"
    const val NAVI_MODE_OFF = 1
    const val NAVI_MODE_FULL = 3
    /** Delay between the mode switch and the DIM message, as MConfig+ sends them. */
    const val MESSAGE_DELAY_MILLIS = 100L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var menu: Any? = null

    /** Whether the ECARX DIM SDK is on this head unit. */
    fun present(): Boolean = runCatching { Class.forName(DIM_INTERACTION); Class.forName(DIM_PROTOCOL) }.isSuccess

    /** Shows display 2 on the cluster ([show] = true) or gives the cluster its stock view back. */
    fun switch(context: Context, show: Boolean) {
        main.post {
            val app = context.applicationContext ?: context
            val mode = if (show) NAVI_MODE_FULL else NAVI_MODE_OFF
            val switched = runCatching {
                val dimMenu = dimMenu(app)
                Class.forName(DIM_MENU).getMethod("switchNaviMode", Int::class.javaPrimitiveType).invoke(dimMenu, mode)
            }.onFailure { Log.w(TAG, "switchNaviMode($mode) failed", it) }.isSuccess
            main.postDelayed({ message(app) }, MESSAGE_DELAY_MILLIS)
            Log.i(TAG, "cluster ${if (show) "shows display 2" else "back to its stock view"}: naviMode $mode switched=$switched")
        }
    }

    /** The cluster's navigation mode, or null when it cannot be read. */
    fun naviMode(context: Context): Int? = runCatching {
        Class.forName(DIM_MENU).getMethod("getNaviMode").invoke(dimMenu(context.applicationContext ?: context)) as Int
    }.getOrNull()

    private fun dimMenu(app: Context): Any = menu ?: run {
        val dimClass = Class.forName(DIM_INTERACTION)
        val dim = dimClass.getMethod("create", Context::class.java).invoke(null, app)
        dimClass.getMethod("getDimMenuInteraction").invoke(dim)!!.also { menu = it }
    }

    private fun message(app: Context) {
        runCatching {
            val protocol = Class.forName(DIM_PROTOCOL)
            val manager = protocol.getMethod("getInstance", Context::class.java).invoke(null, app)
            val send = protocol.methods.first { it.name == "sendMessageToDIM" && it.parameterTypes.size == 4 }
            send.invoke(manager, 2.toByte(), 8.toByte(), 8.toByte(), byteArrayOf(1))
        }.onFailure { Log.w(TAG, "DIM message (2,8,8,{1}) failed", it) }
    }
}
