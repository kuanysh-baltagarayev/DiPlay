package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyHudInfoTest {
    private fun lines(info: HudInfo, icy: Boolean = false, alert: Boolean = false) =
        hudInfoLines(info, icy, alert, "ЛЁД", "км")

    @Test fun aReadingThatIsNotATemperatureIsDropped() {
        assertEquals(-5.5f, hudInfoTemperature(-5.5f))
        assertNull(hudInfoTemperature(null))
        assertNull(hudInfoTemperature(Float.NaN))
        assertNull(hudInfoTemperature(500f))
    }

    @Test fun theRangeIsTheRawValueOverTheDivisor() {
        assertEquals(412, hudInfoRangeKm(412f))
        assertEquals(412, hudInfoRangeKm(41_200f, 100))
        assertNull(hudInfoRangeKm(41_200f)) // a wrong unit shows nothing rather than a false alert
        assertEquals(0, hudInfoRangeKm(0f))
        assertNull(hudInfoRangeKm(-1f))
        assertNull(hudInfoRangeKm(Float.NaN))
        assertNull(hudInfoRangeKm(null))
        assertNull(hudInfoRangeKm(10f, 0))
    }

    @Test fun theIceMarkHasAHysteresis() {
        assertTrue(hudInfoIcy(3, false))
        assertTrue(hudInfoIcy(4, true))
        assertFalse(hudInfoIcy(4, false))
        assertFalse(hudInfoIcy(5, true))
        assertTrue(hudInfoIcy(-7, false))
    }

    @Test fun theTemperatureIsMarkedWhenTheRoadMayBeIcy() {
        assertEquals(listOf(HudInfoLine("18°", HudInfoSeverity.NORMAL)), lines(HudInfo(18f, 400)))
        assertEquals(listOf(HudInfoLine("3° ЛЁД", HudInfoSeverity.WARNING)), lines(HudInfo(3.4f, 400), icy = true))
        assertEquals(listOf(HudInfoLine("4° ЛЁД", HudInfoSeverity.WARNING)), lines(HudInfo(3.6f, 400), icy = true))
        assertEquals(listOf(HudInfoLine("0° ЛЁД", HudInfoSeverity.ALERT)), lines(HudInfo(0.2f, 400), icy = true))
        assertEquals(listOf(HudInfoLine("-7° ЛЁД", HudInfoSeverity.ALERT)), lines(HudInfo(-7f, 400), icy = true))
    }

    @Test fun theRangeShowsWhenGivenAndIsRedOnAlert() {
        assertEquals(listOf(HudInfoLine("99 км", HudInfoSeverity.WARNING)), lines(HudInfo(null, 99)))
        assertEquals(listOf(HudInfoLine("49 км", HudInfoSeverity.ALERT)), lines(HudInfo(null, 49), alert = true))
        assertEquals(1, lines(HudInfo(18f, null)).size)
    }

    @Test fun theLowRangeAndItsRedHaveAHysteresis() {
        assertTrue(hudInfoRangeShown(99, false))
        assertTrue(hudInfoRangeShown(105, true))
        assertFalse(hudInfoRangeShown(105, false))
        assertFalse(hudInfoRangeShown(110, true))
        assertTrue(hudInfoRangeAlert(49, false))
        assertTrue(hudInfoRangeAlert(52, true))
        assertFalse(hudInfoRangeAlert(52, false))
        assertFalse(hudInfoRangeAlert(55, true))
    }

    @Test fun withoutReadingsNothingShows() {
        assertEquals(emptyList<HudInfoLine>(), lines(HudInfo(null, null)))
        assertEquals(listOf(HudInfoLine("12 км", HudInfoSeverity.ALERT)), lines(HudInfo(null, 12), alert = true))
    }
}
