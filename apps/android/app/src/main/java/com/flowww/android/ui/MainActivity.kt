package com.flowww.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.navigation.FlowwwDestination
import com.flowww.android.ui.screens.ActiveTransfersScreen
import com.flowww.android.ui.screens.NearbyDevicesScreen
import com.flowww.android.ui.screens.SettingsScreen
import com.flowww.android.ui.screens.StagedContentScreen
import com.flowww.android.ui.screens.TrustedDevicesScreen
import com.flowww.android.ui.theme.FlowwwOrangeAccent
import com.flowww.android.ui.theme.FlowwwTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FlowwwApp() }
    }
}

@Composable
fun FlowwwApp() {
    var selected by remember { mutableStateOf(FlowwwDestination.Nearby) }

    FlowwwTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "flowww",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFFF5F5F5)
                        )
                        Text(
                            text = selected.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA1A1A1)
                        )
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF141414), tonalElevation = 0.dp) {
                        FlowwwDestination.entries.forEach { destination ->
                            val isSelected = selected == destination
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selected = destination },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(destination.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = FlowwwOrangeAccent,
                                    selectedTextColor = FlowwwOrangeAccent,
                                    indicatorColor = Color(0xFF2A211A),
                                    unselectedIconColor = Color(0xFF8A8A8A),
                                    unselectedTextColor = Color(0xFF8A8A8A)
                                )
                            )
                        }
                    }
                }
            ) { innerPadding ->
                val modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
                when (selected) {
                    FlowwwDestination.Nearby -> NearbyDevicesScreen(modifier = modifier)
                    FlowwwDestination.Trusted -> TrustedDevicesScreen(modifier = modifier)
                    FlowwwDestination.Staged -> StagedContentScreen(modifier = modifier)
                    FlowwwDestination.Transfers -> ActiveTransfersScreen(modifier = modifier)
                    FlowwwDestination.Settings -> SettingsScreen(modifier = modifier)
                }
            }
        }
    }
}