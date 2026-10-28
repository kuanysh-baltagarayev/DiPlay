package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class GeelyHudTestRectTest {
    @Test fun theRectangleStaysOnDisplay2() {
        assertEquals(HudTestRect(0, 762, 728, 96), hudTestRect(0, 762, 728, 96))
        assertEquals(HudTestRect(1920 - 400, 1080 - 100, 400, 100), hudTestRect(1900, 1070, 400, 100))
        assertEquals(HudTestRect(0, 0, 1920, 1080), hudTestRect(-5, -5, 9000, 9000))
    }

    @Test fun aTinyRectangleGrowsToTheMinimum() {
        assertEquals(HudTestRect(10, 10, 16, 16), hudTestRect(10, 10, 1, 1))
    }

    @Test fun theWindowRemovesItselfWithinTenMinutes() {
        assertEquals(120_000L, hudTestHideMillis(120))
        assertEquals(5_000L, hudTestHideMillis(0))
        assertEquals(600_000L, hudTestHideMillis(86_400))
    }
}
