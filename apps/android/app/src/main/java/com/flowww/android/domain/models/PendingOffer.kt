package com.flowww.android.domain.models

/** An incoming transfer offer that still requires an explicit user decision. */
data class PendingOffer(
    val transferId: String,
    val sourceDeviceId: String,
    val sourceName: String,
    val sourcePlatform: String,
    val contentName: String,
    val mimeType: String,
    val kind: String,
    val size: Long,
    val action: String,
    val route: String,
    val offeredAt: Long
)
