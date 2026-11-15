package com.flowww.android.domain.repository

import com.flowww.android.domain.models.FlowwwSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<Result<FlowwwSettings>>
    suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit>
}
