package com.flowww.android.di

import android.content.Context
import com.flowww.android.connection.ConnectionService
import com.flowww.android.data.local.FlowwwSettingsDataSource
import com.flowww.android.data.local.LocalOperationStore
import com.flowww.android.data.local.TransferHistoryStore
import com.flowww.android.data.local.TrustedDeviceStore
import com.flowww.android.data.local.WifiEnvironment
import com.flowww.android.data.repository.DefaultLocalContentGateway
import com.flowww.android.data.repository.DefaultOperationRepository
import com.flowww.android.data.repository.DefaultPairingRepository
import com.flowww.android.data.repository.DefaultSettingsRepository
import com.flowww.android.data.repository.DefaultStagedContentRepository
import com.flowww.android.data.repository.DefaultTransferRepository
import com.flowww.android.data.repository.DefaultTrustedDeviceRepository
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.LocalContentGateway
import com.flowww.android.domain.repository.OperationRepository
import com.flowww.android.domain.repository.PairingRepository
import com.flowww.android.domain.repository.SettingsRepository
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.TransferRepository
import com.flowww.android.domain.repository.TrustedDeviceRepository
import com.flowww.android.pairing.PairingService
import com.flowww.android.ffi.FfiConnectionService
import com.flowww.android.ffi.FfiDiscoveryRepository
import com.flowww.android.ffi.FfiPairingService
import com.flowww.android.ffi.FfiTransfersBridge
import com.flowww.android.ffi.NodeBridge
import com.flowww.android.storage.StagedStore
import com.flowww.android.transfer.LocalContentHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The application graph.
 *
 * Everything is constructed by hand: the app has no annotation processing and
 * no framework dependency, so the wiring stays explicit and readable.
 */
interface AppContainer {
    val appScope: CoroutineScope
    val connectionService: ConnectionService
    val pairingService: PairingService
    val stagedStore: StagedStore
    val stagedContentRepository: StagedContentRepository
    val settingsRepository: SettingsRepository
    val discoveryRepository: DiscoveryRepository
    val trustedDeviceRepository: TrustedDeviceRepository
    val pairingRepository: PairingRepository
    val transferRepository: TransferRepository
    val operationRepository: OperationRepository
    val localContentGateway: LocalContentGateway
    val contentHandoff: LocalContentHandoff
    val wifiEnvironment: WifiEnvironment
    val transfersBridge: FfiTransfersBridge
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val nodeBridge: NodeBridge by lazy { NodeBridge(appContext) }

    override val connectionService: ConnectionService by lazy {
        FfiConnectionService(nodeBridge, stagedStore, appScope)
    }
    override val pairingService: PairingService by lazy {
        FfiPairingService(nodeBridge)
    }

    override val stagedStore: StagedStore by lazy {
        StagedStore(File(appContext.filesDir, "staged_store"))
    }

    private val settingsDataSource by lazy { FlowwwSettingsDataSource(appContext) }

    private val transferHistoryStore by lazy { TransferHistoryStore(File(appContext.filesDir, "transfer_history")) }

    private val localOperationStore by lazy { LocalOperationStore(File(appContext.filesDir, "local_operations")) }

    private val trustedDeviceStore by lazy { TrustedDeviceStore(File(appContext.filesDir, "trusted_devices")) }

    override val wifiEnvironment: WifiEnvironment by lazy { WifiEnvironment(appContext) }

    override val stagedContentRepository: StagedContentRepository by lazy {
        DefaultStagedContentRepository(stagedStore, appContext, connectionService)
    }
    override val settingsRepository: SettingsRepository by lazy {
        DefaultSettingsRepository(settingsDataSource)
    }
    override val discoveryRepository: DiscoveryRepository by lazy {
        FfiDiscoveryRepository(wifiEnvironment, nodeBridge)
    }
    override val trustedDeviceRepository: TrustedDeviceRepository by lazy {
        DefaultTrustedDeviceRepository(trustedDeviceStore)
    }
    override val pairingRepository: PairingRepository by lazy {
        DefaultPairingRepository(pairingService)
    }
    override val transferRepository: TransferRepository by lazy {
        DefaultTransferRepository(connectionService, transferHistoryStore, appScope)
    }
    override val transfersBridge: FfiTransfersBridge by lazy {
        FfiTransfersBridge(nodeBridge, transferRepository, appScope)
    }
    override val operationRepository: OperationRepository by lazy {
        DefaultOperationRepository(localOperationStore)
    }
    override val localContentGateway: LocalContentGateway by lazy {
        DefaultLocalContentGateway(stagedStore, appContext)
    }
    override val contentHandoff: LocalContentHandoff by lazy {
        LocalContentHandoff(localContentGateway) { appContext }
    }

    init {
        appScope.launch {
            nodeBridge.start()
            transfersBridge.start()
        }
    }
}
