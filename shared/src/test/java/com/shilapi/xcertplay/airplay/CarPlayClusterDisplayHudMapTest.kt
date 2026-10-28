package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayClusterDisplayHudMapTest {
    // The HUD map asks for a stream 4 times its 192x96 window, map only, the car 30 % from the left.
    @Test fun theHudMapStreamIsAFewTimesTheWindowWithTheCarToTheLeft() {
        val config = CarPlayClusterDisplay.config(
            192 * 4, 96 * 4, 100,
            content = CarPlayClusterDisplay.Content.MAP,
            markerXPercent = 30,
            markerYPercent = 50,
            baseSafeArea = AirPlayInsets(0, 0, 0, 0),
        )
        assertEquals(768, config.widthPixels)
        assertEquals(384, config.heightPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertEquals(AirPlayInsets(top = 0, bottom = 0, left = 0, right = 307), config.safeArea)
    }
}
