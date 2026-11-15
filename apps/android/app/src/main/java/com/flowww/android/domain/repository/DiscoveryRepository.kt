package com.flowww.android.domain.repository

import com.flowww.android.connection.DiscoveredPeer
import kotlinx.coroutines.flow.Flow

sealed interface DiscoveryResult {
    data class Success(val peers: List<DiscoveredPeer>) : DiscoveryResult
    data class Unavailable(val reason: String) : DiscoveryResult
    data class Error(val message: String) : DiscoveryResult
}

interface DiscoveryRepository {
    fun discover(): Flow<DiscoveryResult>
    suspend fun refresh(): DiscoveryResult
}
