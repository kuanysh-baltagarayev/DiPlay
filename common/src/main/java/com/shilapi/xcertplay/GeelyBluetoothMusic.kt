package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * On the Geely/ECARX head unit the car's own Bluetooth music (A2DP sink) stays connected to the iPhone
 * while CarPlay plays. When DiPlay takes the audio focus, the car's player sends the iPhone a pause over
 * AVRCP, and the iPhone pauses CarPlay music (seen 2026-10-18 in wireless mode). While a CarPlay session
 * is connected this drops the car's Bluetooth music link to the chosen iPhone, and again each time the car
 * brings it back. Calls (HFP) and the pairing stay. Nothing brings the link back after the session: the car
 * or the iPhone connects it again on its own, at the latest when Bluetooth reconnects. Wired sessions are
 * covered too, as a bonded iPhone keeps the link over the cable as well.
 */
internal object GeelyBluetoothMusic {
    private const val TAG = "DiPlay-GeelyBtMusic"
    /** BluetoothProfile.A2DP_SINK, hidden from the public SDK. */
    private const val PROFILE_A2DP_SINK = 11
    /** BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED, hidden from the public SDK. */
    private const val ACTION_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    /** The car may report the link several times in a row; one try per burst. */
    private const val DEBOUNCE_MILLIS = 500L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    private var connected = false // main thread
    private var proxy: BluetoothProfile? = null // main thread
    private var receiver: BroadcastReceiver? = null // main thread
    private var unavailable = false // main thread: the platform has no disconnect(); not asked again in this process
    private val drop = Runnable { dropLink() }

    @Synchronized
    fun attach(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        CarPlayGlance.addConnectionListener { next -> main.post { onConnected(next) } }
        main.post { if (CarPlayGlance.snapshot().connected) onConnected(true) }
    }

    /** Whether a state change of [device] means the car's music link to [phone] is (coming) up. */
    fun linkUp(state: Int, device: String?, phone: String?): Boolean =
        phone != null && device.equals(phone, ignoreCase = true) &&
            (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING)

    private fun onConnected(next: Boolean) {
        if (next == connected) return
        connected = next
        if (next) start() else stop()
    }

    private fun start() {
        val app = context ?: return
        if (unavailable) return
        if (DiPlayPreferences.phoneAddress(app) == null) {
            Log.i(TAG, "No iPhone chosen in DiPlay; the car's Bluetooth music is left alone and may pause CarPlay")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (connected && linkUp(state, device?.address, DiPlayPreferences.phoneAddress(app))) {
                    main.removeCallbacks(drop)
                    main.postDelayed(drop, DEBOUNCE_MILLIS)
                }
            }
        }
        ContextCompat.registerReceiver(app, stateReceiver, IntentFilter(ACTION_STATE), ContextCompat.RECEIVER_EXPORTED)
        receiver = stateReceiver
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, connectedProxy: BluetoothProfile) {
                main.post {
                    if (!connected || proxy != null) {
                        if (proxy !== connectedProxy) adapter.closeProfileProxy(profile, connectedProxy)
                        return@post
                    }
                    proxy = connectedProxy
                    dropLink()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                main.post { proxy = null }
            }
        }
        val asked = try {
            adapter.getProfileProxy(app, listener, PROFILE_A2DP_SINK)
        } catch (error: SecurityException) {
            Log.w(TAG, "Bluetooth music profile not readable", error)
            false
        }
        if (!asked) Log.i(TAG, "No Bluetooth music profile on this head unit")
    }

    private fun stop() {
        main.removeCallbacks(drop)
        receiver?.let { registered -> runCatching { context?.unregisterReceiver(registered) } }
        receiver = null
        proxy?.let { open -> runCatching { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(PROFILE_A2DP_SINK, open) } }
        proxy = null
    }

    /** Drops the car's music link to the chosen iPhone if it is up; nothing else is touched. */
    private fun dropLink() {
        val app = context ?: return
        val open = proxy ?: return
        if (!connected || unavailable) return
        val phone = DiPlayPreferences.phoneAddress(app) ?: return
        try {
            val device = open.connectedDevices.orEmpty().firstOrNull { it.address.equals(phone, ignoreCase = true) } ?: return
            val accepted = open.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(open, device)
            Log.i(TAG, "Car Bluetooth music dropped for the CarPlay iPhone accepted=$accepted")
        } catch (error: NoSuchMethodException) {
            unavailable = true
            Log.w(TAG, "Car Bluetooth music cannot be dropped here; CarPlay music may pause", error)
        } catch (error: Exception) {
            // A Bluetooth service restart or a refused call: tried again when the car brings the link back.
            Log.w(TAG, "Car Bluetooth music drop failed this time", error)
        }
    }
}
