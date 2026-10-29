package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeelyHudInfoTest {
    @Test fun aReadingThatIsNotATemperatureIsDropped() {
        assertEquals(-5.5f, hudInfoTemperature(-5.5f))
        assertNull(hudInfoTemperature(null))
        assertNull(hudInfoTemperature(Float.NaN))
        assertNull(hudInfoTemperature(500f))
    }

    @Test fun theRangeIsReadAsKmOrMetres() {
        assertEquals(412, hudInfoRangeKm(412f))
        assertEquals(412, hudInfoRangeKm(412_000f))
        assertNull(hudInfoRangeKm(-1f))
        assertNull(hudInfoRangeKm(Float.NaN))
        assertNull(hudInfoRangeKm(null))
    }

    @Test fun theTemperatureIsMarkedWhenTheRoadMayBeIcy() {
        assertEquals(listOf(HudInfoLine("18°", HudInfoSeverity.NORMAL)), hudInfoLines(HudInfo(18f, 400)))
        assertEquals(listOf(HudInfoLine("4°", HudInfoSeverity.NORMAL)), hudInfoLines(HudInfo(3.6f, 400)))
        assertEquals(listOf(HudInfoLine("3° ЛЁД", HudInfoSeverity.WARNING)), hudInfoLines(HudInfo(3.4f, 400)))
        assertEquals(listOf(HudInfoLine("0° ЛЁД", HudInfoSeverity.ALERT)), hudInfoLines(HudInfo(0.2f, 400)))
        assertEquals(listOf(HudInfoLine("-7° ЛЁД", HudInfoSeverity.ALERT)), hudInfoLines(HudInfo(-7f, 400)))
    }

    @Test fun theRangeShowsOnlyWhenLow() {
        assertEquals(1, hudInfoLines(HudInfo(18f, 100)).size)
        assertEquals(HudInfoLine("99 км", HudInfoSeverity.WARNING), hudInfoLines(HudInfo(18f, 99)).last())
        assertEquals(HudInfoLine("49 км", HudInfoSeverity.ALERT), hudInfoLines(HudInfo(18f, 49)).last())
    }

    @Test fun withoutReadingsNothingShows() {
        assertEquals(emptyList<HudInfoLine>(), hudInfoLines(HudInfo(null, null)))
        assertEquals(listOf(HudInfoLine("12 км", HudInfoSeverity.ALERT)), hudInfoLines(HudInfo(null, 12)))
    }
}
