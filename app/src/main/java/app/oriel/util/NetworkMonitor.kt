package app.oriel.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the default network: whether it's metered, so quality can follow
 * Wi-Fi vs mobile data, and whether it's usable at all.
 *
 * Online means the default network has internet and isn't blocked for this
 * app (Data Saver, a background restriction). It doesn't wait for Android's
 * "validated" check, which a VPN can withhold from a network that works fine.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(cm.isActiveNetworkMetered)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()
    private val _online = MutableStateFlow(cm.activeNetwork != null)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * Called when the default network becomes a different one (Wi-Fi to
     * mobile data, say), on a system thread. Connections opened on the old
     * one are dead even if their sockets haven't noticed yet.
     */
    @Volatile
    var onSwitch: (() -> Unit)? = null

    private var current: Network? = null
    private var seen: Network? = null
    private var internet = true
    private var blocked = false

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = seen
                current = network
                seen = network
                internet = true
                blocked = false
                update()
                if (previous != null && previous != network) onSwitch?.invoke()
            }

            override fun onLost(network: Network) {
                // Only the default network's loss counts; the next default arrives in onAvailable.
                if (network != current) return
                current = null
                update()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != current) return
                _metered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                update()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (network != current) return
                this@NetworkMonitor.blocked = blocked
                update()
            }
        })
    }

    private fun update() {
        _online.value = current != null && internet && !blocked
    }
}
