package com.flowww.android.data.repository

import com.flowww.android.domain.repository.PairingRepository
import com.flowww.android.pairing.PairingResult
import com.flowww.android.pairing.PairingService
import com.flowww.android.pairing.PairingUriData

class DefaultPairingRepository(private val service: PairingService) : PairingRepository {
    override suspend fun validate(uri: String): Result<PairingUriData> = runCatching { service.validatePairingUri(uri) }
    override suspend fun pair(uri: String): Result<PairingResult> = runCatching { service.initiatePairing(uri) }
}
