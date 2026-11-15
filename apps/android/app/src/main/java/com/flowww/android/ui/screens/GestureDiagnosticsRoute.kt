package com.flowww.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowww.android.sensor.GestureDiagnosticsScreen
import com.flowww.android.sensor.GestureDiagnosticsViewModel
import com.flowww.android.ui.viewmodel.AppViewModelFactory

/**
 * Lifecycle-aware route for gesture diagnostics. The ViewModel owns the
 * sensor stack; listeners run only while a session is started and are
 * unregistered in onCleared() regardless of how the route is left.
 */
@Composable
fun GestureDiagnosticsRoute(
    onBack: () -> Unit,
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: GestureDiagnosticsViewModel = viewModel(
        factory = AppViewModelFactory { GestureDiagnosticsViewModel(context) }
    )
    GestureDiagnosticsScreen(
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
        onOpenSession = onOpenSession
    )
}
