package com.vasmarfas.UniversalAmbientLight.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.CameraEncoder
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSetupScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Dynamic camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Request permission on first composition if not granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Corner positions (normalized 0..1)
    var topLeft by remember { mutableStateOf(Offset(0.1f, 0.1f)) }
    var topRight by remember { mutableStateOf(Offset(0.9f, 0.1f)) }
    var bottomRight by remember { mutableStateOf(Offset(0.9f, 0.9f)) }
    var bottomLeft by remember { mutableStateOf(Offset(0.1f, 0.9f)) }

    // Load saved corners
    LaunchedEffect(Unit) {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        val corners = CameraEncoder.parseCornersString(saved)
        topLeft = Offset(corners[0], corners[1])
        topRight = Offset(corners[2], corners[3])
        bottomRight = Offset(corners[4], corners[5])
        bottomLeft = Offset(corners[6], corners[7])
    }

    // Currently dragged corner index (0-3) or -1
    var dragCorner by remember { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Reset button
                    IconButton(onClick = {
                        topLeft = Offset(0.1f, 0.1f)
                        topRight = Offset(0.9f, 0.1f)
                        bottomRight = Offset(0.9f, 0.9f)
                        bottomLeft = Offset(0.1f, 0.9f)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.camera_setup_reset))
                    }
                    // Save button
                    IconButton(onClick = {
                        val cornersArray = floatArrayOf(
                            topLeft.x, topLeft.y,
                            topRight.x, topRight.y,
                            bottomRight.x, bottomRight.y,
                            bottomLeft.x, bottomLeft.y
                        )
                        prefs.putString(
                            R.string.pref_key_camera_corners,
                            CameraEncoder.cornersToString(cornersArray)
                        )
                        onBackClick()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.camera_setup_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasCameraPermission) {
                // Show permission request UI
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text(stringResource(R.string.camera_grant_permission))
                    }
                }
            } else {
                // Camera Preview
                CameraPreviewView(lifecycleOwner)

                // Corner overlay: drag handlers + drawing
                CornerOverlayCanvas(
                    topLeft = topLeft,
                    topRight = topRight,
                    bottomRight = bottomRight,
                    bottomLeft = bottomLeft,
                    dragCorner = dragCorner,
                    onDragCornerChanged = { dragCorner = it },
                    onCornerMoved = { idx, offset ->
                        when (idx) {
                            0 -> topLeft = offset
                            1 -> topRight = offset
                            2 -> bottomRight = offset
                            3 -> bottomLeft = offset
                        }
                    }
                )

                // Instruction text at bottom
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(
                            Color.Black.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.camera_setup_instruction),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Camera preview that fills the available space.
 * Used on CameraSetupScreen and MainScreen.
 */
@Composable
fun CameraPreviewView(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Camera bind failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

/**
 * Corner overlay canvas with draggable corners and quad visualization.
 * If dragging is not needed, pass onDragCornerChanged = {} and onCornerMoved = {_,_->}.
 */
@Composable
fun CornerOverlayCanvas(
    topLeft: Offset,
    topRight: Offset,
    bottomRight: Offset,
    bottomLeft: Offset,
    dragCorner: Int = -1,
    draggable: Boolean = true,
    onDragCornerChanged: (Int) -> Unit = {},
    onCornerMoved: (Int, Offset) -> Unit = { _, _ -> }
) {
    val modifier = if (draggable) {
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()

                        val corners = listOf(
                            Offset(topLeft.x * w, topLeft.y * h),
                            Offset(topRight.x * w, topRight.y * h),
                            Offset(bottomRight.x * w, bottomRight.y * h),
                            Offset(bottomLeft.x * w, bottomLeft.y * h)
                        )

                        val threshold = 80f
                        var minDist = Float.MAX_VALUE
                        var minIdx = -1
                        corners.forEachIndexed { idx, pos ->
                            val dx = startOffset.x - pos.x
                            val dy = startOffset.y - pos.y
                            val dist = sqrt(dx * dx + dy * dy)
                            if (dist < threshold && dist < minDist) {
                                minDist = dist
                                minIdx = idx
                            }
                        }
                        onDragCornerChanged(minIdx)
                    },
                    onDrag = { change, _ ->
                        if (dragCorner < 0) return@detectDragGestures
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val pos = change.position
                        val nx = (pos.x / w).coerceIn(0f, 1f)
                        val ny = (pos.y / h).coerceIn(0f, 1f)
                        onCornerMoved(dragCorner, Offset(nx, ny))
                    },
                    onDragEnd = { onDragCornerChanged(-1) },
                    onDragCancel = { onDragCornerChanged(-1) }
                )
            }
    } else {
        Modifier.fillMaxSize()
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val tl = Offset(topLeft.x * w, topLeft.y * h)
        val tr = Offset(topRight.x * w, topRight.y * h)
        val br = Offset(bottomRight.x * w, bottomRight.y * h)
        val bl = Offset(bottomLeft.x * w, bottomLeft.y * h)

        // Semi-transparent overlay outside the quad
        val overlayColor = Color.Black.copy(alpha = 0.4f)
        drawRect(overlayColor)

        // Cut-out the quad with "clear" (draw the quad area lighter)
        val quadPath = Path().apply {
            moveTo(tl.x, tl.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bl.x, bl.y)
            close()
        }

        // Draw filled quad to brighten the area
        drawPath(quadPath, Color.White.copy(alpha = 0.3f))

        // Draw quad border
        val borderColor = Color(0xFF00E676) // Bright green
        val borderStroke = Stroke(width = 3f)
        drawPath(quadPath, borderColor, style = borderStroke)

        // Draw corner markers
        val cornerRadius = 18f
        val corners = listOf(tl, tr, br, bl)
        val labels = listOf("TL", "TR", "BR", "BL")

        corners.forEachIndexed { idx, pos ->
            val isActive = dragCorner == idx
            val radius = if (isActive) cornerRadius * 1.5f else cornerRadius

            // Outer circle
            drawCircle(
                color = borderColor,
                radius = radius,
                center = pos,
                style = Stroke(width = 3f)
            )
            // Inner filled circle
            drawCircle(
                color = if (isActive) borderColor.copy(alpha = 0.8f) else borderColor.copy(alpha = 0.4f),
                radius = radius - 3f,
                center = pos
            )

            // Label
            drawContext.canvas.nativeCanvas.drawText(
                labels[idx],
                pos.x - 10f,
                pos.y + 5f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    isAntiAlias = true
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }
    }
}

/**
 * Full-screen camera preview background with corners overlay.
 * Used as a background on MainScreen when camera mode is selected.
 * Shows live preview when camera is available, otherwise dark background with corners.
 */
@Composable
fun CameraPreviewBackground() {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    // Load saved corners
    val corners = remember {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        CameraEncoder.parseCornersString(saved)
    }
    val topLeft = Offset(corners[0], corners[1])
    val topRight = Offset(corners[2], corners[3])
    val bottomRight = Offset(corners[4], corners[5])
    val bottomLeft = Offset(corners[6], corners[7])

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            // Live camera preview
            CameraPreviewView()
        } else {
            // Dark background when no permission
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Read-only corner overlay (no dragging — user should use Camera Setup for adjusting)
        CornerOverlayCanvas(
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft,
            draggable = false
        )
    }
}
