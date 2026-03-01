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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.serenegiant.usb.USBMonitor
import com.vasmarfas.UniversalAmbientLight.common.UsbCameraEncoder
import com.vasmarfas.UniversalAmbientLight.common.util.UsbCameraUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSetupScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val usbCameraUtil = remember { UsbCameraUtil(context) }

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

    var cameraFacing by remember { mutableIntStateOf(prefs.getInt(R.string.pref_key_camera_facing, CameraSelector.LENS_FACING_BACK)) }
    var selectedUsbVendorId by remember { mutableIntStateOf(prefs.getInt(R.string.pref_key_usb_vendor_id, -1)) }
    var selectedUsbProductId by remember { mutableIntStateOf(prefs.getInt(R.string.pref_key_usb_product_id, -1)) }
    var usbCameraRotation by remember { mutableIntStateOf(prefs.getInt(R.string.pref_key_usb_camera_rotation, 0)) }

    // Check available cameras
    var hasBackCamera by remember { mutableStateOf(false) }
    var hasFrontCamera by remember { mutableStateOf(false) }
    var hasExternalCamera by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var connectedUsbCameras by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
    var usbCheckingState by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                try {
                    val extSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                        .build()
                    hasExternalCamera = provider.hasCamera(extSelector)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("CameraSetup", "Failed to check camera availability", e)
            }
        }, ContextCompat.getMainExecutor(context))

        connectedUsbCameras = usbCameraUtil.getConnectedWebcams()
    }

    if (showCameraDialog) {
        AlertDialog(
            onDismissRequest = { showCameraDialog = false },
            title = { Text(stringResource(R.string.camera_select_camera)) },
            text = {
                Column {
                    // ---- Built-in cameras ----
                    if (hasBackCamera) {
                        TextButton(
                            onClick = {
                                cameraFacing = CameraSelector.LENS_FACING_BACK
                                prefs.putInt(R.string.pref_key_camera_facing, cameraFacing)
                                showCameraDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.camera_facing_back), modifier = Modifier.weight(1f))
                            if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                                Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                    if (hasFrontCamera) {
                        TextButton(
                            onClick = {
                                cameraFacing = CameraSelector.LENS_FACING_FRONT
                                prefs.putInt(R.string.pref_key_camera_facing, cameraFacing)
                                showCameraDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.camera_facing_front), modifier = Modifier.weight(1f))
                            if (cameraFacing == CameraSelector.LENS_FACING_FRONT)
                                Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                    if (hasExternalCamera) {
                        TextButton(
                            onClick = {
                                cameraFacing = CameraSelector.LENS_FACING_EXTERNAL
                                prefs.putInt(R.string.pref_key_camera_facing, cameraFacing)
                                showCameraDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.camera_facing_external), modifier = Modifier.weight(1f))
                            if (cameraFacing == CameraSelector.LENS_FACING_EXTERNAL)
                                Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }

                    // ---- USB/UVC devices via AUSBC userspace driver ----
                    if (connectedUsbCameras.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "USB UVC (userspace driver)",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        connectedUsbCameras.forEach { device ->
                            val isSelected = cameraFacing == UsbCameraEncoder.CAMERA_FACING_USB_UVC &&
                                    device.vendorId == selectedUsbVendorId &&
                                    device.productId == selectedUsbProductId
                            TextButton(
                                onClick = {
                                    showCameraDialog = false
                                    // Request USB permission if needed, then switch to AUSBC mode
                                    if (!usbCameraUtil.hasPermission(device)) {
                                        usbCheckingState = true
                                        usbCameraUtil.requestPermission(device) { granted ->
                                            usbCheckingState = false
                                            if (granted) {
                                                selectedUsbVendorId = device.vendorId
                                                selectedUsbProductId = device.productId
                                                cameraFacing = UsbCameraEncoder.CAMERA_FACING_USB_UVC
                                                prefs.putInt(R.string.pref_key_camera_facing, cameraFacing)
                                                prefs.putInt(R.string.pref_key_usb_vendor_id, selectedUsbVendorId)
                                                prefs.putInt(R.string.pref_key_usb_product_id, selectedUsbProductId)
                                            }
                                        }
                                    } else {
                                        selectedUsbVendorId = device.vendorId
                                        selectedUsbProductId = device.productId
                                        cameraFacing = UsbCameraEncoder.CAMERA_FACING_USB_UVC
                                        prefs.putInt(R.string.pref_key_camera_facing, cameraFacing)
                                        prefs.putInt(R.string.pref_key_usb_vendor_id, selectedUsbVendorId)
                                        prefs.putInt(R.string.pref_key_usb_product_id, selectedUsbProductId)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.Start,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(device.productName ?: "USB Camera")
                                    Text(
                                        "VID:${"%04X".format(device.vendorId)}  PID:${"%04X".format(device.productId)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }
                    } else {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.camera_usb_no_device),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCameraDialog = false }) {
                    Text(stringResource(R.string.scanner_cancel))
                }
            }
        )
    }

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
                    // More visible camera selector
                    val label = when (cameraFacing) {
                        CameraSelector.LENS_FACING_BACK -> stringResource(R.string.camera_facing_back)
                        CameraSelector.LENS_FACING_FRONT -> stringResource(R.string.camera_facing_front)
                        CameraSelector.LENS_FACING_EXTERNAL -> stringResource(R.string.camera_facing_external)
                        UsbCameraEncoder.CAMERA_FACING_USB_UVC -> stringResource(R.string.camera_facing_usb_uvc)
                        else -> "Camera"
                    }
                    FilledTonalButton(
                        onClick = { showCameraDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = stringResource(R.string.camera_select_camera),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

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
                        prefs.putInt(R.string.pref_key_camera_facing, cameraFacing)
                        if (cameraFacing == UsbCameraEncoder.CAMERA_FACING_USB_UVC) {
                            prefs.putInt(R.string.pref_key_usb_vendor_id, selectedUsbVendorId)
                            prefs.putInt(R.string.pref_key_usb_product_id, selectedUsbProductId)
                            prefs.putInt(R.string.pref_key_usb_camera_rotation, usbCameraRotation)
                        }
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
                // Camera Preview — AUSBC or CameraX depending on selected mode
                if (cameraFacing == UsbCameraEncoder.CAMERA_FACING_USB_UVC &&
                    selectedUsbVendorId != -1 && selectedUsbProductId != -1
                ) {
                    UsbCameraPreviewView(
                        vendorId = selectedUsbVendorId,
                        productId = selectedUsbProductId,
                        rotation = usbCameraRotation
                    )
                } else {
                    CameraPreviewView(lifecycleOwner, cameraFacing)
                }

                // USB permission request overlay
                if (usbCheckingState) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.camera_usb_checking), color = Color.White)
                        }
                    }
                }

                // Corner overlay with dragging support
                // Using inline Canvas with direct state access so pointerInput
                // always reads the latest MutableState values (no stale closures).
                Canvas(
                    modifier = Modifier
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
                                    dragCorner = minIdx
                                },
                                onDrag = { change, _ ->
                                    if (dragCorner < 0) return@detectDragGestures
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val pos = change.position
                                    val nx = (pos.x / w).coerceIn(0f, 1f)
                                    val ny = (pos.y / h).coerceIn(0f, 1f)
                                    val newOffset = Offset(nx, ny)
                                    when (dragCorner) {
                                        0 -> topLeft = newOffset
                                        1 -> topRight = newOffset
                                        2 -> bottomRight = newOffset
                                        3 -> bottomLeft = newOffset
                                    }
                                },
                                onDragEnd = { dragCorner = -1 },
                                onDragCancel = { dragCorner = -1 }
                            )
                        }
                ) {
                    drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft, dragCorner)
                }

                // Rotation selector: only shown in USB UVC mode
                if (cameraFacing == UsbCameraEncoder.CAMERA_FACING_USB_UVC) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp)
                            .background(
                                Color.Black.copy(alpha = 0.75f),
                                RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.camera_usb_rotation_label) + ":",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            listOf(0, 90, 180, 270).forEach { deg ->
                                FilterChip(
                                    selected = usbCameraRotation == deg,
                                    onClick = { usbCameraRotation = deg },
                                    label = { Text("${deg}°") }
                                )
                            }
                        }
                    }
                }

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
 * Draws the corner overlay quad + markers.
 * Pure drawing function used inside Canvas DrawScope.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornersOverlay(
    topLeft: Offset,
    topRight: Offset,
    bottomRight: Offset,
    bottomLeft: Offset,
    dragCorner: Int = -1
) {
    val w = size.width
    val h = size.height

    val tl = Offset(topLeft.x * w, topLeft.y * h)
    val tr = Offset(topRight.x * w, topRight.y * h)
    val br = Offset(bottomRight.x * w, bottomRight.y * h)
    val bl = Offset(bottomLeft.x * w, bottomLeft.y * h)

    // Semi-transparent overlay
    val overlayColor = Color.Black.copy(alpha = 0.4f)
    drawRect(overlayColor)

    // Quad path
    val quadPath = Path().apply {
        moveTo(tl.x, tl.y)
        lineTo(tr.x, tr.y)
        lineTo(br.x, br.y)
        lineTo(bl.x, bl.y)
        close()
    }

    // Fill quad lighter
    drawPath(quadPath, Color.White.copy(alpha = 0.3f))

    // Quad border
    val borderColor = Color(0xFF00E676)
    drawPath(quadPath, borderColor, style = Stroke(width = 3f))

    // Corner markers
    val cornerRadius = 18f
    val corners = listOf(tl, tr, br, bl)
    val labels = listOf("TL", "TR", "BR", "BL")

    corners.forEachIndexed { idx, pos ->
        val isActive = dragCorner == idx
        val radius = if (isActive) cornerRadius * 1.5f else cornerRadius

        drawCircle(color = borderColor, radius = radius, center = pos, style = Stroke(width = 3f))
        drawCircle(
            color = if (isActive) borderColor.copy(alpha = 0.8f) else borderColor.copy(alpha = 0.4f),
            radius = radius - 3f,
            center = pos
        )

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

/** No-op USB device listener used when we open a device directly (no register() call needed). */
private val NOOP_DEVICE_LISTENER = object : USBMonitor.OnDeviceConnectListener {
    override fun onAttach(device: UsbDevice?) {}
    override fun onDetach(device: UsbDevice?) {}
    override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
    override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
    override fun onCancel(device: UsbDevice?) {}
}

/**
 * Live preview composable for a USB UVC camera via the AUSBC userspace driver.
 * Opens the device identified by [vendorId]/[productId] directly via USBMonitor.openDevice()
 * without calling register(), avoiding the Android 14+ RECEIVER_NOT_EXPORTED crash.
 *
 * [rotation] is a clockwise rotation in degrees (0, 90, 180, 270) applied to the preview.
 * The preview is displayed with the correct aspect ratio — no stretching.
 */
@Composable
fun UsbCameraPreviewView(vendorId: Int, productId: Int, rotation: Int = 0) {
    val context = LocalContext.current

    // We intentionally do NOT call setAspectRatio() on the view here — AspectRatioTextureView
    // inverts the ratio in portrait mode (designed for a rotated native stream), which would
    // stretch our raw 1280×720 UVC frames. Aspect ratio is handled via Compose modifiers below.
    val textureView = remember { AspectRatioTextureView(context) }

    DisposableEffect(vendorId, productId) {
        var cameraRef: MultiCameraClient.Camera? = null
        var monitorRef: USBMonitor? = null

        // Directly open the USB device without calling register() — avoids the
        // Android 14+ SecurityException ("RECEIVER_NOT_EXPORTED required").
        // Permission was already granted by UsbCameraUtil in the setup dialog.
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.find {
            it.vendorId == vendorId && it.productId == productId
        }

        if (device != null && usbManager.hasPermission(device)) {
            val monitor = USBMonitor(context, NOOP_DEVICE_LISTENER)
            monitorRef = monitor
            try {
                val ctrlBlock = monitor.openDevice(device)
                val cam = MultiCameraClient.Camera(context, device)
                cam.setUsbControlBlock(ctrlBlock)
                cameraRef = cam
                val request = CameraRequest.Builder()
                    .setPreviewWidth(1280)
                    .setPreviewHeight(720)
                    .create()
                cam.openCamera(textureView, request)
            } catch (e: Exception) {
                Log.e("UsbCameraPreview", "Failed to open USB camera for preview", e)
            }
        }

        onDispose {
            cameraRef?.closeCamera()
            cameraRef = null
            monitorRef?.destroy()
            monitorRef = null
        }
    }

    // For 90°/270° rotations the effective visible area is portrait (9:16);
    // for 0°/180° it is the native landscape (16:9).
    val isPortraitRotation = rotation == 90 || rotation == 270

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { textureView },
            update = { view ->
                // Apply SurfaceTexture transform so the content rotates correctly within the
                // measured bounds. Using view.post ensures the view is already laid out.
                view.post {
                    if (view.width > 0 && view.height > 0) {
                        if (rotation != 0) {
                            val mx = android.graphics.Matrix()
                            val cx = view.width / 2f
                            val cy = view.height / 2f
                            mx.postRotate(rotation.toFloat(), cx, cy)
                            // For 90°/270°: scale so the rotated content fills the bounds
                            if (isPortraitRotation) {
                                val scale = view.height.toFloat() / view.width.toFloat()
                                mx.postScale(scale, 1f / scale, cx, cy)
                            }
                            view.setTransform(mx)
                        } else {
                            view.setTransform(null)
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (isPortraitRotation) 9f / 16f else 16f / 9f)
        )
    }
}

/**
 * Camera preview that fills the available space.
 * When cameraLensFacing changes, fully rebinds camera and clears any frozen frame.
 * Shows an error overlay if the camera is unavailable (e.g. no External Camera HAL for USB cameras).
 */
@Composable
fun CameraPreviewView(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner = LocalLifecycleOwner.current,
    cameraLensFacing: Int = CameraSelector.LENS_FACING_BACK
) {
    val context = LocalContext.current
    val cameraErrorState = remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, cameraLensFacing) {
        cameraErrorState.value = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var previewUseCase: Preview? = null
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                boundProvider = provider

                // Always unbind first to clear frozen frame from previous camera
                try { provider.unbindAll() } catch (_: Exception) {}

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                previewUseCase = preview

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(cameraLensFacing)
                    .build()

                try {
                    if (provider.hasCamera(cameraSelector)) {
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        cameraErrorState.value = false
                    } else {
                        Log.e("CameraPreview", "Camera not found (facing=$cameraLensFacing). External Camera HAL may be absent.")
                        cameraErrorState.value = true
                    }
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Camera bind failed", e)
                    cameraErrorState.value = true
                }
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera provider failed", e)
                cameraErrorState.value = true
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            previewUseCase?.let { uc ->
                try {
                    boundProvider?.unbind(uc)
                } catch (_: Exception) {}
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        if (cameraErrorState.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.camera_error_not_available),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}



/**
 * Full-screen camera preview background with read-only corners overlay.
 * Used as a background on MainScreen when camera mode is selected.
 *
 * @param isCapturing When true, the camera is in use by the service (CameraEncoder),
 *   so we show a dark background with corners + pulsing indicator instead of live preview.
 *   When false, we show the live camera preview for calibration.
 */
@Composable
fun CameraPreviewBackground(isCapturing: Boolean = false) {
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
    val cameraFacing = remember { prefs.getInt(R.string.pref_key_camera_facing, CameraSelector.LENS_FACING_BACK) }
    val usbVendorId = remember { prefs.getInt(R.string.pref_key_usb_vendor_id, -1) }
    val usbProductId = remember { prefs.getInt(R.string.pref_key_usb_product_id, -1) }
    val usbRotation = remember { prefs.getInt(R.string.pref_key_usb_camera_rotation, 0) }

    val topLeft = Offset(corners[0], corners[1])
    val topRight = Offset(corners[2], corners[3])
    val bottomRight = Offset(corners[4], corners[5])
    val bottomLeft = Offset(corners[6], corners[7])

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            cameraFacing == UsbCameraEncoder.CAMERA_FACING_USB_UVC && usbVendorId != -1 && usbProductId != -1 -> {
                // In USB mode, foreground service keeps an exclusive handle to the device.
                // Keep previous behavior while capturing to avoid camera-open conflicts.
                if (isCapturing) {
                    Spacer(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else {
                    // USB UVC camera: preview handled by AUSBC, not CameraX
                    UsbCameraPreviewView(vendorId = usbVendorId, productId = usbProductId, rotation = usbRotation)
                }
            }
            hasCameraPermission -> {
                // Built-in cameras can keep preview visible while service is running.
                CameraPreviewView(cameraLensFacing = cameraFacing)
            }
            else -> {
                Spacer(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // Read-only corner overlay (no dragging)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft)
        }

        // Capturing indicator
        if (isCapturing) {
            val infiniteTransition = rememberInfiniteTransition(label = "capturePulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawCircle(Color.Red.copy(alpha = alpha))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.camera_capturing_status),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
