package com.shilapi.xcertplay

import com.shilapi.xcertplay.GeelyClusterMap.Layout
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeelyClusterMapLayoutTest {
    @Test fun theMapFillsTheClusterCanvasByDefault() {
        assertEquals(Layout(0, 0, 1920, 720), Layout.DEFAULT)
        assertEquals(Layout.DEFAULT, Layout.sanitize(0, 0, 1920, 720))
    }

    @Test fun theStreamHasTheWindowSize() {
        val d = Layout.DEFAULT
        val config = CarPlayClusterDisplay.config(d.width, d.height, 100)
        assertEquals(d.width, config.widthPixels)
        assertEquals(d.height, config.heightPixels)
    }

    @Test fun sizesRoundDownToMultiplesOf8AndTheWindowStaysOnTheCanvas() {
        assertEquals(Layout(0, 0, 1200, 432), Layout.sanitize(-5, -1, 1207, 439))
        assertEquals(Layout(1920 - 400, 720 - 200, 400, 200), Layout.sanitize(1900, 700, 400, 200))
        assertEquals(Layout(0, 0, 1920, 720), Layout.sanitize(0, 0, 5000, 5000))
        assertEquals(Layout(10, 10, 320, 120), Layout.sanitize(10, 10, 1, 1))
        assertEquals(Layout(0, 0, 320, 120), Layout.sanitize(0, 0, 64, 64))
    }

    @Test fun aYesIsKeptAndANoIsAskedAgainAfterAWhile() {
        assertEquals(true, cachedAvailability(true, 0, 1_000_000))
        assertNull(cachedAvailability(null, 0, 0))
        assertEquals(false, cachedAvailability(false, 1_000, 5_000))
        assertNull(cachedAvailability(false, 1_000, 11_000))
    }
}
