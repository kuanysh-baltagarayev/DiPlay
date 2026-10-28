package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayClusterDisplayHudMapTest {
    @Test fun theHudMapStreamHasTheWindowsOwnSizeAndNoSafeAreaCut() {
        val config = CarPlayClusterDisplay.config(
            192, 96, 100,
            content = CarPlayClusterDisplay.Content.MAP,
            markerXPercent = 50,
            markerYPercent = 50,
            baseSafeArea = AirPlayInsets(0, 0, 0, 0),
        )
        assertEquals(192, config.widthPixels)
        assertEquals(96, config.heightPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertEquals(AirPlayInsets(0, 0, 0, 0), config.safeArea)
    }
}
