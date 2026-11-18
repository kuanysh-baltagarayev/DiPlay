package com.shilapi.xcertplay

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyBluetoothMusicTest {
    private val phone = "44:A7:F4:3D:E9:17"

    @Test fun theChosenIphoneComingUpIsDropped() {
        assertTrue(GeelyBluetoothMusic.linkUp(BluetoothProfile.STATE_CONNECTED, phone, phone))
        assertTrue(GeelyBluetoothMusic.linkUp(BluetoothProfile.STATE_CONNECTING, phone.lowercase(), phone))
    }

    @Test fun anotherPhoneOrAGoingLinkIsLeftAlone() {
        assertFalse(GeelyBluetoothMusic.linkUp(BluetoothProfile.STATE_CONNECTED, "11:22:33:44:55:66", phone))
        assertFalse(GeelyBluetoothMusic.linkUp(BluetoothProfile.STATE_DISCONNECTED, phone, phone))
        assertFalse(GeelyBluetoothMusic.linkUp(BluetoothProfile.STATE_CONNECTED, null, phone))
    }

    @Test fun nothingIsDroppedWithoutAChosenIphone() {
        assertFalse(GeelyBluetoothMusic.linkUp(BluetoothProfile.STATE_CONNECTED, phone, null))
    }
}
