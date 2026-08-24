package com.gios.lightsync.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gios.lightsync.qr.QrAnalyzer
import com.gios.lightsync.sync.Enrollment
import com.gios.lightsync.ui.theme.Dim
import java.util.concurrent.Executors

/**
 * Point the phone at the setup page and be configured.
 *
 * The camera is open for exactly as long as this screen is composed, and the first code that
 * parses ends it. Frames are analysed and dropped — nothing is written anywhere, and there is no
 * `ImageCapture` bound at all, which is the shape worth keeping in an app that otherwise never
 * asks for a camera.
 *
 * A code that is not one of ours is ignored rather than complained about. A viewfinder that is
 * looking at a wifi QR on the back of a router should say nothing until it sees the right thing.
 */
@Composable
fun ScanScreen(onScanned: (Enrollment) -> Unit, onManual: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasCamera(context)) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasCamera(context)
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) {
                Viewfinder(lifecycleOwner = lifecycleOwner, onScanned = onScanned)
            } else {
                EmptyState(
                    "BrightSync needs the camera to read the setup code.",
                    Modifier.align(Alignment.Center),
                )
            }
        }
        Text(
            "Open http://<basilnet>:8099/enroll/<token> on a computer and point the phone at it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        BigButton(
            "TYPE IT INSTEAD",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = onManual,
        )
        Gap(24)
    }
}

@Composable
private fun Viewfinder(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onScanned: (Enrollment) -> Unit,
) {
    val context = LocalContext.current
    // One thread, and it is shut down with the screen. An analyzer executor that outlives the
    // composable keeps a camera frame pipeline alive behind a screen nobody is looking at.
    val executor = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    // Keep only the newest frame: decoding is slower than the stream, and a
                    // backlog would have the phone reading a code that left the screen a second
                    // ago — which is how a scanner appears to hang.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { stage ->
                        stage.setAnalyzer(
                            executor,
                            QrAnalyzer { text ->
                                val parsed = Enrollment.parse(text)
                                if (parsed != null && !done) {
                                    done = true
                                    view.post { onScanned(parsed) }
                                }
                            },
                        )
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )

    Box(Modifier.fillMaxSize()) {
        Text(
            "Point at the code",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

private fun hasCamera(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
