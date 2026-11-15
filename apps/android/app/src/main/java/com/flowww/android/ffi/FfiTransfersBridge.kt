package com.flowww.android.ffi

import com.flowww.android.domain.models.PendingOffer
import com.flowww.android.domain.repository.TransferRepository
import com.flowww.android.uniffi.FlowwwEvent
import com.flowww.android.uniffi.PendingOfferDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Fans Rust transfer/offer events into Android repositories and UI state. */
class FfiTransfersBridge(
    private val bridge: NodeBridge,
    private val transferRepository: TransferRepository,
    private val scope: CoroutineScope
) {
    private val _pendingOffers = MutableStateFlow<List<PendingOffer>>(emptyList())
    val pendingOffers: StateFlow<List<PendingOffer>> = _pendingOffers.asStateFlow()

    fun start() {
        scope.launch(Dispatchers.IO) {
            bridge.start()
            refreshOffers()
            bridge.events.collect { event ->
                when (event) {
                    is FlowwwEvent.TransferUpdated -> {
                        FfiMappers.run { event.record.toDomain() }
                            ?.let { transferRepository.recordTransfer(it) }
                    }
                    is FlowwwEvent.OfferReceived -> addOffer(event.offer.toDomain())
                    is FlowwwEvent.OfferClosed -> removeOffer(event.transferId)
                    else -> Unit
                }
            }
        }
    }

    suspend fun respondToOffer(transferId: String, accept: Boolean): Boolean {
        val result = bridge.call {
            bridge.node().respondToOffer(transferId, accept)
        }.getOrElse { throw it }
        if (result) removeOffer(transferId)
        return result
    }

    private suspend fun refreshOffers() {
        val offers = bridge.call { bridge.node().pendingOffers() }
            .getOrElse { throw it }
            .map { it.toDomain() }
        _pendingOffers.value = offers
    }

    private fun addOffer(offer: PendingOffer) {
        _pendingOffers.value = (_pendingOffers.value.filterNot { it.transferId == offer.transferId } + offer)
    }

    private fun removeOffer(transferId: String) {
        _pendingOffers.value = _pendingOffers.value.filterNot { it.transferId == transferId }
    }

    private fun PendingOfferDto.toDomain() = PendingOffer(
        transferId = transferId,
        sourceDeviceId = sourceDeviceId,
        sourceName = sourceName,
        sourcePlatform = sourcePlatform,
        contentName = contentName,
        mimeType = mimeType,
        kind = kind,
        size = size.toLong(),
        action = action,
        route = route,
        offeredAt = offeredAt.toLong()
    )
}
