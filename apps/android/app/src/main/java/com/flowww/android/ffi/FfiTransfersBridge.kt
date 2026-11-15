package com.flowww.android.ffi

import com.flowww.android.domain.models.PendingOffer
import com.flowww.android.domain.repository.TransferRepository
import com.flowww.android.transfer.IncomingFileLocator
import com.flowww.android.transfer.ReceiveCommitter
import com.flowww.android.uniffi.FlowwwEvent
import com.flowww.android.uniffi.PendingOfferDto
import com.flowww.android.uniffi.TransferRecordDto
import java.io.File
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
    private val receiveCommitter: ReceiveCommitter,
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
                        enqueueIncomingFile(event.record)
                    }
                    is FlowwwEvent.OfferReceived -> addOffer(event.offer.toDomain())
                    is FlowwwEvent.OfferClosed -> removeOffer(event.transferId)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Queues a finished incoming file for commit into the user-chosen SAF
     * folder. Only the COMPLETED terminal state enqueues; the Rust core has
     * already verified the transfer digest by then, and the committer
     * re-verifies against the bytes it writes.
     */
    private fun enqueueIncomingFile(record: TransferRecordDto) {
        if (record.direction != "incoming" || record.status != "COMPLETED") return
        val offeredName = record.filename?.takeIf { it.isNotBlank() } ?: return
        // Never trust a wire name as a path: the file lives directly inside
        // the app-private download directory.
        val fileName = offeredName.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank() || fileName == "." || fileName == "..") return
        val expectedSha256 = record.sha256?.takeIf { it.isNotBlank() }
        val source = resolveSource(fileName, expectedSha256) ?: return
        receiveCommitter.enqueue(
            transferId = record.id,
            fileName = fileName,
            mimeType = record.mimeType,
            sizeBytes = record.size.toLong(),
            expectedSha256 = expectedSha256,
            sourcePath = source.absolutePath,
        )
        // Save it now if a destination is already configured; otherwise the
        // entry waits for the user to pick a folder.
        receiveCommitter.requestSweep()
    }

    /**
     * Locates the downloaded file for a finished transfer.
     *
     * The Rust core dedupes name collisions inside the download directory
     * ("report (1).pdf"), so the offered name alone is ambiguous when the
     * same file arrives twice. Candidates are therefore resolved by the
     * digest the transfer reported: the file whose SHA-256 matches is the
     * one this transfer downloaded. Only when no digest is known does the
     * newest candidate win.
     */
    private fun resolveSource(fileName: String, expectedSha256: String?): File? =
        IncomingFileLocator(bridge.downloadDirectory).resolve(fileName, expectedSha256)

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
