package com.flowww.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavItem(val screen: Screen, val label: String, val icon: ImageVector)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Nearby, "Nearby", Icons.Outlined.Devices),
    BottomNavItem(Screen.Trusted, "Trusted", Icons.Outlined.People),
    BottomNavItem(Screen.Staged, "Staged", Icons.Outlined.ContentPaste),
    BottomNavItem(Screen.Transfers, "Transfers", Icons.Outlined.SwapHoriz),
    BottomNavItem(Screen.Settings, "Settings", Icons.Outlined.Settings)
)
