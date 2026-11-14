package com.flowww.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.People
import androidx.compose.ui.graphics.vector.ImageVector

enum class FlowwwDestination(val label: String, val route: String, val icon: ImageVector) {
    Nearby("Nearby", "nearby", Icons.Default.Devices),
    Trusted("Trusted", "trusted", Icons.Default.People),
    Staged("Content", "staged", Icons.Default.ContentPaste),
    Transfers("Transfers", "transfers", Icons.Default.Upload),
    Settings("Settings", "settings", Icons.Default.Settings)
}