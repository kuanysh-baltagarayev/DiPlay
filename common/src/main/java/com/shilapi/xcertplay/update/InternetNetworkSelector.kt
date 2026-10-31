package com.shilapi.xcertplay.update

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.shilapi.xcertplay.network.CarPlayVpnService

/** Finds an internet route without changing the process-wide network used by CarPlay. */
internal object InternetNetworkSelector {
    fun select(connectivity: ConnectivityManager, ownVpnOpen: Boolean = CarPlayVpnService.tunnelOpen): Network? {
        val active = connectivity.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            connectivity.allNetworks.forEach { if (it != active) add(it) }
        }
        return candidates.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                usable(isValidatedInternet(it), vpn = !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN), ownVpnOpen)
            } == true
        }
    }

    /**
     * DiPlay's own VPN carries only the iPhone's USB link and has no DNS, so it is skipped while it is up.
     * Another app's VPN is kept: when it does not allow a bypass, a socket bound to the network under it
     * is refused, and the VPN is the only way out.
     */
    internal fun usable(validatedInternet: Boolean, vpn: Boolean, ownVpnOpen: Boolean): Boolean =
        validatedInternet && !(vpn && ownVpnOpen)

    fun isValidatedInternet(capabilities: NetworkCapabilities): Boolean =
        isValidatedInternet(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )

    internal fun isValidatedInternet(hasInternet: Boolean, validated: Boolean): Boolean =
        hasInternet && validated
}
