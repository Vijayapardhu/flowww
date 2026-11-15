package com.flowww.android.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.flowww.android.domain.models.NetworkEnvironment
import com.flowww.android.domain.models.WifiNetwork
import com.flowww.android.domain.models.WifiPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the real network environment of the device.
 *
 * Everything here is platform state: connectivity, the active transport, the
 * Wi-Fi radio and the Wi-Fi networks the phone can actually see. It never
 * pretends to find a Flowww peer.
 */
class WifiEnvironment(private val context: Context) {

    private val connectivityManager: ConnectivityManager?
        get() = ContextCompat.getSystemService(context, ConnectivityManager::class.java)

    private val wifiManager: WifiManager?
        get() = ContextCompat.getSystemService(context, WifiManager::class.java)

    /** The permission needed to read Wi-Fi scan results on this platform. */
    fun requiredPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /** Whether that permission is currently granted. */
    fun permissionState(): WifiPermissionState {
        val permission = requiredPermission() ?: return WifiPermissionState.NOT_REQUIRED
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) WifiPermissionState.GRANTED else WifiPermissionState.NOT_GRANTED
    }

    /** Connectivity, transport and Wi-Fi radio state. */
    fun environment(): NetworkEnvironment {
        val manager = connectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val connected = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            capabilities == null -> "Offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Connected"
        }
        val wifiEnabled = wifiManager?.isWifiEnabled == true
        return NetworkEnvironment(
            isConnected = connected,
            transport = transport,
            isWifiEnabled = wifiEnabled,
            permissionState = permissionState(),
            requiredPermission = requiredPermission()
        )
    }

    /**
     * Scans for the Wi-Fi networks this phone can see.
     *
     * These are ordinary Wi-Fi networks, not Flowww peers; the screen labels
     * them as diagnostics.
     */
    suspend fun scanNetworks(): Result<List<WifiNetwork>> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = wifiManager ?: throw IllegalStateException("Wi-Fi is unavailable on this device")
            if (!manager.isWifiEnabled) throw IllegalStateException("Wi-Fi is switched off")
            // Guard the scan-result read with an explicit runtime permission
            // check: the platform throws SecurityException without it.
            val permission = requiredPermission()
            if (permission != null &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Wi-Fi scan results need the nearby devices permission")
            }
            @Suppress("DEPRECATION")
            manager.scanResults
                .map { result ->
                    WifiNetwork(
                        ssid = result.SSID?.takeIf { it.isNotBlank() } ?: "<hidden network>",
                        bssid = result.BSSID.orEmpty(),
                        signalLevel = result.level,
                        frequencyMhz = result.frequency,
                        capabilities = result.capabilities.orEmpty()
                    )
                }
                .sortedByDescending { it.signalLevel }
        }
    }
}
