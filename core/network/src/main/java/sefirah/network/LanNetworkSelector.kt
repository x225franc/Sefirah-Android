package sefirah.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import sefirah.domain.util.LanAddressPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** Chooses Wi-Fi/Ethernet only for peers on their directly attached private subnet. */
@Singleton
class LanNetworkSelector @Inject constructor(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun forPeer(address: String): Network? = manager.allNetworks.firstOrNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@firstOrNull false
        manager.getLinkProperties(network)?.linkAddresses?.any { local ->
            LanAddressPolicy.isDirectLanPeer(address, local.address.hostAddress.orEmpty(), local.prefixLength)
        } == true
    }
}
