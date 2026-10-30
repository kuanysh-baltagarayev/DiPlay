package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.FceNavigationState
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Asks the iPhone for route guidance once more when a route is on but no maneuver arrives.
 *
 * 2GIS sends no maneuvers (0x5202) for a route started before CarPlay connected, only a few route
 * updates (0x5201), until the route is built again; every HUD and cluster output then stays empty.
 * After [waitNanos] with a route and without a maneuver, the route-guidance updates are stopped and
 * started again, once per route: a route counts from the first route update that does not say
 * outright that there is none (whether 2GIS sends a state is not known) until one says so. Whether
 * 2GIS answers the retry is still to be seen in a car; apps that send their maneuvers anyway never
 * reach it.
 */
internal class Iap2RouteGuidanceRetry(
    private val onProgress: (String) -> Unit,
    private val waitNanos: Long = DEFAULT_WAIT_NANOS,
    private val now: () -> Long = System::nanoTime,
) {
    private val navigation = FceNavigationState()
    private var routeSince: Long? = null

    /** A maneuver came or the retry was sent for this route. */
    private var settled = false

    fun accept(messageId: Int, payload: ByteArray) {
        when (messageId) {
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                // A late maneuver of a route that has ended must not use up the next route's retry.
                if (navigation.ended) return
                settled = true
                routeSince = null
            }
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE -> {
                navigation.accept(messageId, payload)
                when {
                    navigation.ended -> {
                        settled = false
                        routeSince = null
                    }
                    // While the route is still loading or the phone is locating itself, no maneuver is due.
                    navigation.state in NO_MANEUVER_YET -> routeSince = null
                    !settled && routeSince == null -> routeSince = now()
                }
            }
        }
    }

    fun tick(send: (Iap2Frame) -> Unit) {
        val since = routeSince ?: return
        val waited = now() - since
        if (waited < waitNanos) return
        settled = true
        routeSince = null
        send(Iap2ControlMessages.stopRouteGuidanceUpdates())
        send(Iap2ControlMessages.startRouteGuidanceUpdates())
        onProgress(
            "iap2 tx=0x5203+0x5200 route guidance asked again: a route for ${waited / NANOS_PER_SECOND} s " +
                "without a maneuver, state=${navigation.state} source=${navigation.source}",
        )
    }

    /** [timeout] cut so that [tick] runs when the wait is over. */
    fun pollTimeout(timeout: Long): Long {
        val since = routeSince ?: return timeout
        val left = (waitNanos - (now() - since)).coerceAtLeast(0L) / NANOS_PER_MILLI + 1
        return minOf(timeout, left)
    }

    companion object {
        const val DEFAULT_WAIT_NANOS = 20_000_000_000L

        /** RouteGuidanceState Loading (3) and Locating (4). */
        private val NO_MANEUVER_YET = setOf(3, 4)
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
