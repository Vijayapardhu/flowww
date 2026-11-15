package com.flowww.android.data.repository

import com.flowww.android.data.local.FlowwwSettingsDataSource
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

class DefaultSettingsRepository(private val dataSource: FlowwwSettingsDataSource) : SettingsRepository {
    override val settings: Flow<Result<FlowwwSettings>> = dataSource.settings
    override suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit> = dataSource.update(transform)
}
