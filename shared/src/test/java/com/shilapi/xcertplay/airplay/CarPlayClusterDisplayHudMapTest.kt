package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayClusterDisplayHudMapTest {
    @Test fun theHudMapAsksForTheClustersViewAtTheWindowsOwnSize() {
        val config = CarPlayClusterDisplay.config(
            192, 96, 100,
            content = CarPlayClusterDisplay.Content.INSTRUMENTS,
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        )
        assertEquals(192, config.widthPixels)
        assertEquals(96, config.heightPixels)
        assertEquals("maps:/car/instrumentcluster", config.initialUrl)
        val area = config.safeArea!!
        assertTrue(area.left + area.right < config.widthPixels)
        assertTrue(area.top + area.bottom < config.heightPixels)
    }
}
