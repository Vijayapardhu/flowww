package com.flowww.android.domain.repository

import com.flowww.android.connection.DiscoveredPeer
import com.flowww.android.domain.models.NetworkEnvironment
import com.flowww.android.domain.models.WifiNetwork
import kotlinx.coroutines.flow.Flow

sealed interface DiscoveryResult {
    data class Success(val peers: List<DiscoveredPeer>) : DiscoveryResult
    data class Unavailable(val reason: String) : DiscoveryResult
    data class Error(val message: String) : DiscoveryResult
}

/**
 * Local peer discovery.
 *
 * The interface is shaped for a future implementation that speaks the Flowww
 * peer protocol. Until such an implementation exists the repository reports
 * the real network state and never invents peers.
 */
interface DiscoveryRepository {
    fun discover(): Flow<DiscoveryResult>

    /** Runs a discovery cycle and reports what happened. */
    suspend fun refresh(): DiscoveryResult

    /** Connectivity, transport and Wi-Fi radio state of this phone. */
    suspend fun environment(): NetworkEnvironment

    /** Scans the Wi-Fi networks this phone can see (diagnostics, not peers). */
    suspend fun scanNetworks(): Result<List<WifiNetwork>>
}
