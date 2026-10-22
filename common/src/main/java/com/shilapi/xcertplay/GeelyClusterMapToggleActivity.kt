package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle

/**
 * The "Map on cluster" launcher entry: shows or hides CarPlay's map on the Geely/ECARX cluster and
 * closes at once, so a steering-wheel action that starts an app (MConfig+) can switch the map.
 * `adb shell am start -n <package>/com.shilapi.xcertplay.GeelyClusterMapToggleActivity` does the same.
 * The launcher alias is enabled only on head units where [GeelyClusterMap] is available.
 */
class GeelyClusterMapToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeelyClusterMap.toggle()
        finish()
        overridePendingTransition(0, 0)
    }
}
