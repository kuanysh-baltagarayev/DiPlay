package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class GeelyHudRectTest {
    @Test fun theRectangleStaysOnDisplay2() {
        assertEquals(HudRect(0, 762, 728, 96), clampHudRect(0, 762, 728, 96))
        assertEquals(HudRect(1920 - 400, 1080 - 100, 400, 100), clampHudRect(1900, 1070, 400, 100))
        assertEquals(HudRect(0, 0, 1920, 1080), clampHudRect(-5, -5, 9000, 9000))
    }

    @Test fun aTinyRectangleGrowsToTheMinimum() {
        assertEquals(HudRect(10, 10, 16, 16), clampHudRect(10, 10, 1, 1))
    }

    @Test fun theWindowRemovesItselfWithinTenMinutes() {
        assertEquals(120_000L, hudTestHideMillis(120))
        assertEquals(5_000L, hudTestHideMillis(0))
        assertEquals(600_000L, hudTestHideMillis(86_400))
    }

    @Test fun theStreamIsNeverLargerThanDisplay2() {
        assertEquals(4, hudStreamFactor(4, 192, 96))
        assertEquals(2, hudStreamFactor(8, 728, 190)) // 1920 / 728 = 2
        assertEquals(1, hudStreamFactor(4, 1920, 1080))
        assertEquals(1, hudStreamFactor(4, 1500, 100))
    }
}
