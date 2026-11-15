package com.flowww.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.flowww.android.FlowwwApplication
import com.flowww.android.di.AppContainer

@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current.applicationContext as FlowwwApplication
    return remember { context.container }
}
