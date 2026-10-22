package com.shilapi.xcertplay

import com.shilapi.xcertplay.GeelyClusterMap.Layout
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class GeelyClusterMapLayoutTest {
    @Test fun theDefaultWindowSitsBetweenTheMonjaroClusterReadings() {
        val d = Layout.DEFAULT
        assertEquals(Layout(310, 115, 1200, 440), d)
        // Clear of the car silhouette (x <= 280), the gear block (x >= 1830), the top strip (y <= 112)
        // and the bottom arcs (y >= 556), measured on photos of the cluster.
        assert(d.x > 280 && d.x + d.width < 1830 && d.y > 112 && d.y + d.height < 556)
        assertEquals(d, Layout.sanitize(d.x, d.y, d.width, d.height))
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
        assertEquals(Layout(10, 10, 64, 64), Layout.sanitize(10, 10, 1, 1))
    }
}
