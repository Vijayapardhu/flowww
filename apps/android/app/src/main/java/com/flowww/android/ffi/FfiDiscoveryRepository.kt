package com.flowww.android.ffi

import com.flowww.android.data.local.WifiEnvironment
import com.flowww.android.domain.models.NetworkEnvironment
import com.flowww.android.domain.models.WifiNetwork
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.DiscoveryResult
import com.flowww.android.uniffi.FlowwwEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** LAN discovery backed by the Rust mDNS node. */
class FfiDiscoveryRepository(
    private val wifiEnvironment: WifiEnvironment,
    private val bridge: NodeBridge
) : DiscoveryRepository {
    override fun discover(): Flow<DiscoveryResult> = flow {
        emit(refresh())
        bridge.events
            .filter { it is FlowwwEvent.PeerFound || it is FlowwwEvent.PeerLost }
            .collect { emit(refresh()) }
    }

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
                else -> {
                    bridge.start()
                    bridge.call { bridge.node().startDiscovery() }
                        .getOrElse { throw it }
                    val peers = bridge.call { bridge.node().listPeers() }
                        .getOrElse { throw it }
                        .map { FfiMappers.run { it.toDomain() } }
                    DiscoveryResult.Success(peers)
                }
            }
        } catch (error: Throwable) {
            DiscoveryResult.Error(error.message ?: "Discovery failed")
        }
    }

    override suspend fun environment(): NetworkEnvironment = withContext(Dispatchers.IO) {
        wifiEnvironment.environment()
    }

    override suspend fun scanNetworks(): Result<List<WifiNetwork>> =
        wifiEnvironment.scanNetworks()
}
