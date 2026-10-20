package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, qualifiers = "ru")
class GeelyHudBroadcastsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun noTurnWithoutARouteOrAConnection() {
        assertNull(GeelyHudBroadcasts.turn(context, CarPlayGlance.Snapshot(connected = true)))
        assertNull(GeelyHudBroadcasts.turn(context, CarPlayGlance.Snapshot(connected = false, maneuverType = 2)))
    }

    @Test fun aTurnCarriesArrowDistanceRoadAndTripAsTheWidgetShowsThem() {
        val turn = GeelyHudBroadcasts.turn(
            context,
            CarPlayGlance.Snapshot(
                connected = true, maneuverType = 2, distanceMeters = 247, road = "Ахмедьярова",
                remainingSeconds = 14 * 60L + 10, remainingMeters = 5_040,
            ),
        )!!
        assertEquals(R.drawable.ic_maneuver_right, turn.arrow)
        assertEquals("250 м", turn.distance)
        assertEquals("Ахмедьярова", turn.road)
        assertEquals("5,0 км", turn.remainingDistance)
        assertEquals("14 мин", turn.remainingTime)
        assertNull(turn.arrival)
    }

    @Test fun longDistancesAndDurations() {
        val turn = GeelyHudBroadcasts.turn(
            context,
            CarPlayGlance.Snapshot(
                connected = true, maneuverType = 1, distanceMeters = 12_400,
                remainingSeconds = 2 * 3600L + 5 * 60, remainingMeters = 125_600,
            ),
        )!!
        assertEquals("12 км", turn.distance)
        assertEquals("126 км", turn.remainingDistance)
        assertEquals("2 ч 05 мин", turn.remainingTime)
    }

    @Test fun theNextManeuverIsAddedToTheStreet() {
        val withRoad = GeelyHudBroadcasts.turn(
            context,
            CarPlayGlance.Snapshot(connected = true, maneuverType = 2, road = "Ахмедьярова", nextManeuverType = 1),
        )!!
        assertEquals("Ахмедьярова · затем налево", withRoad.road)
        val noRoad = GeelyHudBroadcasts.turn(
            context, CarPlayGlance.Snapshot(connected = true, maneuverType = 2, nextManeuverType = 30),
        )!!
        assertEquals("Затем по кольцу, 3-й съезд", noRoad.road)
    }

    @Test fun theStartStepHasNoArrowAndARoundaboutCarriesItsExit() {
        val start = GeelyHudBroadcasts.turn(
            context, CarPlayGlance.Snapshot(connected = true, maneuverType = 11, road = "ул. Ахмедьярова"),
        )!!
        assertEquals(0, start.arrow)
        assertEquals("0 м", start.distance)
        val roundabout = GeelyHudBroadcasts.turn(
            context, CarPlayGlance.Snapshot(connected = true, maneuverType = 29, distanceMeters = 300),
        )!!
        assertEquals(R.drawable.ic_maneuver_roundabout, roundabout.arrow)
        assertEquals(2, roundabout.exit)
    }

    @Test fun roundingPicksTheUnitAndTheLastMinuteIsOne() {
        fun turn(meters: Int, seconds: Long) = GeelyHudBroadcasts.turn(
            context, CarPlayGlance.Snapshot(connected = true, maneuverType = 2, distanceMeters = meters, remainingSeconds = seconds),
        )!!
        assertEquals("990 м", turn(994, 600).distance)
        assertEquals("1,0 км", turn(997, 600).distance)
        assertEquals("10 км", turn(9_960, 600).distance)
        assertEquals("1 мин", turn(100, 20).remainingTime)
    }
}
