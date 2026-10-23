package com.shilapi.xcertplay.update

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GeelyUpdatesTest {
    @After fun upstreamAgain() = GeelyUpdates.configure(null, null)

    @Test fun withoutRepositoryAndTokenTheUpstreamSourceStays() {
        GeelyUpdates.configure("owner/builds", "")
        assertFalse(GeelyUpdates.active)
        assertEquals(UpdateClient.RELEASES_URL, GeelyUpdates.releasesUrl())
        assertNull(GeelyUpdates.isNewer("v0.2.21-geely.2", "0.2.21-geely.1"))
        assertTrue(GeelyUpdates.headers("https://api.github.com/repos/owner/builds/releases").isEmpty())
        GeelyUpdates.configure("not a repo", "token")
        assertFalse(GeelyUpdates.active)
    }

    @Test fun theBuildNumberOrdersBuildsOfOneVersion() {
        GeelyUpdates.configure("owner/builds", "token")
        assertTrue(GeelyUpdates.isNewer("v0.2.21-geely.2", "0.2.21-geely.1")!!)
        assertTrue(GeelyUpdates.isNewer("v0.2.21-geely.1", "0.2.21-geely")!!)
        assertTrue(GeelyUpdates.isNewer("v0.2.22-geely.1", "0.2.21-geely.9")!!)
        assertFalse(GeelyUpdates.isNewer("v0.2.21-geely.3", "0.2.21-geely.3")!!)
        assertFalse(GeelyUpdates.isNewer("v0.2.21-geely.10", "0.2.22-geely.1")!!)
        // Through the shared check too, so the cached banner and the background check agree.
        assertTrue(UpdateVersion.isNewer("v0.2.21-geely.2", "0.2.21-geely.1"))
    }

    @Test fun theTokenGoesOnlyToTheOwnRepository() {
        GeelyUpdates.configure("owner/builds", "secret")
        assertEquals("https://api.github.com/repos/owner/builds/releases?per_page=3", GeelyUpdates.releasesUrl())
        val list = GeelyUpdates.headers("https://api.github.com/repos/owner/builds/releases?per_page=3")
        assertEquals("Bearer secret", list["Authorization"])
        assertNull(list["Accept"])
        val asset = GeelyUpdates.headers("https://api.github.com/repos/owner/builds/releases/assets/7")
        assertEquals("application/octet-stream", asset["Accept"])
        assertTrue(GeelyUpdates.headers("https://api.github.com/repos/other/builds/releases").isEmpty())
        assertTrue(GeelyUpdates.headers("https://objects.githubusercontent.com/x").isEmpty())
    }

    @Test fun aReleaseCachedFromUpstreamIsNotOffered() {
        assertTrue(GeelyUpdates.ownRelease("https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.22/a.apk"))
        GeelyUpdates.configure("owner/builds", "secret")
        assertFalse(GeelyUpdates.ownRelease("https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.22/a.apk"))
        assertTrue(GeelyUpdates.ownRelease("https://api.github.com/repos/owner/builds/releases/assets/7"))
    }

    @Test fun privateFilesDownloadThroughTheApi() {
        val asset = JSONObject()
            .put("url", "https://api.github.com/repos/owner/builds/releases/assets/7")
            .put("browser_download_url", "https://github.com/owner/builds/releases/download/v1/a.apk")
        assertEquals("https://github.com/owner/builds/releases/download/v1/a.apk", GeelyUpdates.assetUrl(asset))
        GeelyUpdates.configure("owner/builds", "secret")
        assertEquals("https://api.github.com/repos/owner/builds/releases/assets/7", GeelyUpdates.assetUrl(asset))
    }

    @Test fun theScriptInstallsRestoresTheServiceAndReopensDiPlay() {
        val script = GeelyUpdates.script(
            "com.shihab.diplay", File("/sdcard/update/DiPlay-0.2.21-geely.2.apk"), File("/sdcard/update/install.log"),
        )
        val lines = script.lines()
        val install = lines.indexOfFirst {
            it.startsWith("cp '/sdcard/update/DiPlay-0.2.21-geely.2.apk' /data/local/tmp/diplay-update.apk && pm install -r ")
        }
        val service = lines.indexOfFirst { "com.shihab.diplay/com.shilapi.xcertplay.UsbAutoConfirmService" in it }
        val reopen = lines.indexOfFirst { it.startsWith("am start -n 'com.shihab.diplay/com.shilapi.xcertplay.DiPlayActivity'") }
        assertTrue(install >= 0 && service > install && reopen > service)
    }
}
