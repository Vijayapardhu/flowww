package com.flowww.android.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** On-device QR decoder; frames are not uploaded or persisted. */
@Composable
fun QrScannerView(onScanned: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }
    if (denied) {
        Text("Camera permission was denied. Paste the pairing URI instead.")
        return
    }
    if (granted) {
        AndroidView(
            modifier = modifier,
            factory = { view ->
                DecoratedBarcodeView(view).also { barcode ->
                    barcode.decodeContinuous { result ->
                        result.text?.takeIf { it.startsWith("flowww://pair/v1/") }?.let(onScanned)
                    }
                    barcode.resume()
                }
            },
            update = { it.resume() }
        )
    }
}
