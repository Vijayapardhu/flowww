package com.flowww.android.data.repository

import com.flowww.android.data.local.WifiEnvironment
import com.flowww.android.domain.models.NetworkEnvironment
import com.flowww.android.domain.models.WifiNetwork
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.DiscoveryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Discovery for the standalone build.
 *
 * The repository reports the genuine network situation and explains why no
 * Flowww peer can be listed yet. It never returns invented peers: the Flowww
 * peer protocol is not implemented in this build.
 */
class DefaultDiscoveryRepository(
    private val wifiEnvironment: WifiEnvironment
) : DiscoveryRepository {

    override fun discover(): Flow<DiscoveryResult> = flow { emit(refresh()) }

    override suspend fun refresh(): DiscoveryResult = withContext(Dispatchers.IO) {
        try {
            val environment = wifiEnvironment.environment()
            when {
                !environment.isConnected -> DiscoveryResult.Unavailable(
                    "No network connection. Connect to Wi-Fi before looking for local peers."
                )
                !environment.isWifiEnabled -> DiscoveryResult.Unavailable(
                    "Wi-Fi is switched off. Turn it on to look for peers on the local network."
                )
                else -> DiscoveryResult.Unavailable(
                    "Peer discovery is unavailable: this build has no Flowww peer protocol yet. " +
                        "The Wi-Fi diagnostics below show the networks this phone can actually see."
                )
            }
        } catch (e: Exception) {
            DiscoveryResult.Error(e.message ?: "Discovery failed")
        }
    }

    override suspend fun environment(): NetworkEnvironment = withContext(Dispatchers.IO) {
        wifiEnvironment.environment()
    }

    override suspend fun scanNetworks(): Result<List<WifiNetwork>> =
        wifiEnvironment.scanNetworks()
}
