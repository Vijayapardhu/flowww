package com.flowww.android.domain.repository

import com.flowww.android.pairing.PairingResult
import com.flowww.android.pairing.PairingUriData

interface PairingRepository {
    suspend fun validate(uri: String): Result<PairingUriData>
    suspend fun pair(uri: String): Result<PairingResult>
}
