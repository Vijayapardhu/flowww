package com.flowww.android.ui.navigation

sealed class Screen(val route: String) {
    data object Nearby : Screen("nearby")
    data object Trusted : Screen("trusted")
    data object Staged : Screen("staged")
    data object Transfers : Screen("transfers")
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object Pairing : Screen("pairing")
    data object TransferDetails : Screen("transfer/{transferId}") {
        fun create(transferId: String) = "transfer/$transferId"
    }
    data object IncomingApproval : Screen("incoming/{transferId}") {
        fun create(transferId: String) = "incoming/$transferId"
    }
    data object GestureSession : Screen("gesture/session")
    data object GestureDiagnostics : Screen("gesture/diagnostics")
    data object About : Screen("about")

    companion object {
        val bottomDestinations = listOf(Nearby, Trusted, Staged, Transfers, Settings)
    }
}
