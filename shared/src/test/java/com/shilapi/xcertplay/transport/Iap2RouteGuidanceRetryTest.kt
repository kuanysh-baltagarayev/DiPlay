package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RouteGuidanceRetryTest {
    private var clock = 0L
    private val sent = mutableListOf<Iap2Frame>()
    private val progress = mutableListOf<String>()
    private val retry = Iap2RouteGuidanceRetry(progress::add, waitNanos = 20 * SECOND, now = { clock })

    /** A RouteGuidanceUpdate with only RouteGuidanceState (0x01). */
    private fun routeState(state: Int) = byteArrayOf(0, 5, 0, 1, state.toByte())

    private fun tickAt(seconds: Long) {
        clock = seconds * SECOND
        retry.tick(sent::add)
    }

    @Test
    fun aRouteWithoutManeuversIsAskedForAgainOnce() {
        retry.accept(0x5201, routeState(1))
        tickAt(19)
        assertTrue(sent.isEmpty())

        tickAt(20)
        assertEquals(listOf(0x5203, 0x5200), sent.map { it.messageId })
        assertArrayEquals(byteArrayOf(0, 6, 0, 0, 0, 42), sent[0].payload)
        assertEquals(1, progress.size)

        retry.accept(0x5201, routeState(1))
        tickAt(100)
        assertEquals(2, sent.size)
    }

    @Test
    fun aManeuverMeansNothingIsAsked() {
        retry.accept(0x5201, routeState(1))
        retry.accept(0x5202, ByteArray(0))
        tickAt(60)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun noRouteMeansNothingIsAsked() {
        retry.accept(0x5201, routeState(1))
        clock = 5 * SECOND
        retry.accept(0x5201, routeState(0))
        tickAt(60)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aNewRouteWithoutManeuversIsAskedForAgainAfterAnEarlierRouteHadThem() {
        retry.accept(0x5201, routeState(1))
        retry.accept(0x5202, ByteArray(0))
        clock = 60 * SECOND
        retry.accept(0x5201, routeState(2))
        retry.accept(0x5201, routeState(1))
        tickAt(79)
        assertTrue(sent.isEmpty())
        tickAt(80)
        assertEquals(listOf(0x5203, 0x5200), sent.map { it.messageId })
    }

    @Test
    fun eachRouteGetsItsOwnRetry() {
        retry.accept(0x5201, routeState(1))
        tickAt(20)
        retry.accept(0x5201, routeState(0))
        retry.accept(0x5201, routeState(1))
        tickAt(40)
        assertEquals(4, sent.size)
        assertEquals(2, progress.size)
    }

    @Test
    fun anActiveStateCountsAlthoughTheSourceIsEmpty() {
        // RouteGuidanceState 1 and an empty navigation source name (0x13).
        retry.accept(0x5201, routeState(1) + byteArrayOf(0, 4, 0, 0x13))
        tickAt(20)
        assertEquals(2, sent.size)
    }

    @Test
    fun theWaitStartsAtTheFirstRouteUpdate() {
        clock = 10 * SECOND
        retry.accept(0x5201, ByteArray(0))
        assertEquals(20_001L, retry.pollTimeout(60_000L))
        assertEquals(500L, retry.pollTimeout(500L))
        tickAt(29)
        assertTrue(sent.isEmpty())
        tickAt(30)
        assertEquals(2, sent.size)
        assertEquals(60_000L, retry.pollTimeout(60_000L))
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
