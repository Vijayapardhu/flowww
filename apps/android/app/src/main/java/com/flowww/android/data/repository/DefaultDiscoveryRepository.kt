package com.flowww.android.data.repository

import com.flowww.android.connection.ConnectionService
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.DiscoveryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DefaultDiscoveryRepository(private val connectionService: ConnectionService) : DiscoveryRepository {
    override fun discover(): Flow<DiscoveryResult> = flow { emit(refresh()) }

    override suspend fun refresh(): DiscoveryResult = try {
        val peers = connectionService.discoverPeers()
        if (peers.isEmpty()) {
            DiscoveryResult.Unavailable("Discovery service is not connected (pending Rust/FFI integration). No simulated devices are shown.")
        } else {
            DiscoveryResult.Success(peers)
        }
    } catch (e: Exception) {
        DiscoveryResult.Error(e.message ?: "Discovery failed")
    }
}
