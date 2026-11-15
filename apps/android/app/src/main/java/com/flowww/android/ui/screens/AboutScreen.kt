package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.BuildConfig
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color(0xFFF5F5F5)) }
                FlowwwSectionHeader(title = "About Flowww", subtitle = "Android companion application")
            }
        }
        item {
            FlowwwCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = Color(0xFFFF7A1D))
                    Text("Flowww for Android", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color(0xFFA1A1A1))
                    Spacer(Modifier.height(4.dp))
                    Text("Security and transfer authority: Rust core (integration pending in this milestone).", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                    Text("Local features in this build: staged-content persistence, settings persistence, navigation, honest service-state UI.", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
