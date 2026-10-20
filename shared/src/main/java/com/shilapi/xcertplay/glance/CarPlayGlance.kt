package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.BydAppleManeuver
import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) and the song from NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session, whatever the BYD output settings.
 */
object CarPlayGlance {
    /** One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route. */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        val song: String? = null,
        val playing: Boolean = false,
    )

    private const val LANE_GUIDANCE_UPDATE = 0x5204
    private const val TAG = "DiPlay-Lane"

    private val route = BydHudRouteState()
    /**
     * The same guidance for a windshield HUD: the iPhone sends nothing while the car stands, so the
     * turn stays until the route ends or CarPlay disconnects, and the street falls back to the
     * maneuver description.
     */
    private val hudRoute = BydHudRouteState(staleRouteNs = HUD_STALE_ROUTE_NS, descriptionAsRoad = true)
    private val song = ClusterSongState()
    private var connected = false
    private var last = Snapshot()

    /** Called with each new snapshot, on the thread that changed it. */
    @Volatile var listener: ((Snapshot) -> Unit)? = null

    /** Lane guidance from 0x5204, on the iAP2 thread; empty when the lane strip goes away. */
    @Volatile var laneListener: ((List<LaneGuidanceLane>) -> Unit)? = null

    /** The last strip written to the log: Apple Maps repeats it while the overlap lasts. */
    private var loggedLanes: List<LaneGuidanceLane>? = null

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let { listener?.invoke(it) }
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == LANE_GUIDANCE_UPDATE) {
            // Lane guidance is a side channel: log it, hand it to whoever draws lanes, and leave
            // the route/now-playing state alone.
            val lanes = runCatching { Iap2LaneGuidance.parse(frame.payload) }.getOrDefault(emptyList())
            if (lanes != loggedLanes) {
                loggedLanes = lanes
                android.util.Log.i(TAG, "0x5204 " + describeLanes(lanes, frame.payload))
            }
            laneListener?.invoke(lanes)
            return
        }
        val changed = synchronized(this) {
            when (frame.messageId) {
                BydHudRouteState.ROUTE_GUIDANCE_UPDATE, BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                    runCatching { route.accept(frame.messageId, frame.payload) }
                    runCatching { hudRoute.accept(frame.messageId, frame.payload) }
                }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                else -> return
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (!next) {
                route.clear()
                hudRoute.clear()
                song.clear()
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    /**
     * One line per lane strip for the diagnostic report: the parsed fields, and the raw payload once,
     * so a report from a car we cannot test still shows what the phone actually sent.
     */
    private fun describeLanes(lanes: List<LaneGuidanceLane>, payload: ByteArray): String {
        if (lanes.isEmpty()) return "lane strip cleared"
        val strip = lanes.joinToString("; ") {
            "i=${it.index} st=${it.status} a=${it.angle} h=${it.highlightAngle}"
        }
        return "lanes=${lanes.size} [$strip] raw=" + payload.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    /** Like [snapshot], with the guidance kept for a windshield HUD (see [hudRoute]). */
    fun hudSnapshot(): Snapshot = synchronized(this) { snapshotLocked(hudRoute.currentApple()) }

    private fun publishLocked(): Snapshot? {
        val next = snapshotLocked(route.currentApple())
        if (next == last) return null
        last = next
        return next
    }

    private fun snapshotLocked(maneuver: BydAppleManeuver?): Snapshot {
        val current = song.current()
        return Snapshot(
            connected = connected,
            maneuverType = maneuver?.type,
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            song = current?.text,
            playing = current?.playing ?: false,
        )
    }

    private const val HUD_STALE_ROUTE_NS = 30 * 60 * 1_000_000_000L
}
