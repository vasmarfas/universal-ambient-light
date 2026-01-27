package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import android.content.Context
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedLayoutScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    
    var xLedText by remember { mutableStateOf(prefs.getInt(R.string.pref_key_x_led).toString()) }
    var yLedText by remember { mutableStateOf(prefs.getInt(R.string.pref_key_y_led).toString()) }
    var bottomGapText by remember { mutableStateOf(prefs.getInt(R.string.pref_key_bottom_gap, 0).toString()) }
    
    val xLed = xLedText.toIntOrNull() ?: 1
    val yLed = yLedText.toIntOrNull() ?: 1
    val bottomGap = bottomGapText.toIntOrNull() ?: 0
    
    var startCorner by remember { 
        mutableStateOf(prefs.getString(R.string.pref_key_led_start_corner, "top_left") ?: "top_left") 
    }
    var direction by remember { 
        mutableStateOf(prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise") 
    }
    
    var sideTop by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_top, "enabled") ?: "enabled") }
    var sideRight by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_right, "enabled") ?: "enabled") }
    var sideBottom by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_bottom, "enabled") ?: "enabled") }
    var sideLeft by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_left, "enabled") ?: "enabled") }
    
    var showEditDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LED Layout") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Left Side: Visualization (Fixed)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Раскладка светодиодов",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val totalLeds = remember(xLed, yLed, sideTop, sideRight, sideBottom, sideLeft) {
                    var total = 0
                    if (sideTop != "not_installed") total += xLed
                    if (sideRight != "not_installed") total += yLed
                    if (sideBottom != "not_installed") total += xLed
                    if (sideLeft != "not_installed") total += yLed
                    total
                }
                
                Text(
                    text = "Всего LED: $totalLeds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // LED visualization
                LedVisualization(
                    xLed = xLed,
                    yLed = yLed,
                    startCorner = startCorner,
                    direction = direction,
                    sideTop = sideTop,
                    sideRight = sideRight,
                    sideBottom = sideBottom,
                    sideLeft = sideLeft,
                    bottomGap = bottomGap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .weight(1f, fill = false)
                )
            }

            // Right Side: Settings (Scrollable)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // LED count inputs
                OutlinedTextField(
                    value = xLedText,
                    onValueChange = { newText ->
                        xLedText = newText
                        newText.toIntOrNull()?.let { value ->
                            if (value > 0) {
                                prefs.putString(R.string.pref_key_x_led, value.toString())
                            }
                        }
                    },
                    label = { Text("Горизонтальные LED") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = xLedText.isNotEmpty() && xLedText.toIntOrNull() == null
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = yLedText,
                    onValueChange = { newText ->
                        yLedText = newText
                        newText.toIntOrNull()?.let { value ->
                            if (value > 0) {
                                prefs.putString(R.string.pref_key_y_led, value.toString())
                            }
                        }
                    },
                    label = { Text("Вертикальные LED") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = yLedText.isNotEmpty() && yLedText.toIntOrNull() == null
                )

                Spacer(modifier = Modifier.height(24.dp))

                // LED sides configuration
                Text(
                    text = "Активные стороны",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                SideSelectorCard(
                    title = "Верх",
                    selectedMode = sideTop,
                    onModeSelected = { mode ->
                        sideTop = mode
                        prefs.putString(R.string.pref_key_led_side_top, mode)
                        sendClearOnce(context)
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                SideSelectorCard(
                    title = "Право",
                    selectedMode = sideRight,
                    onModeSelected = { mode ->
                        sideRight = mode
                        prefs.putString(R.string.pref_key_led_side_right, mode)
                        sendClearOnce(context)
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                SideSelectorCard(
                    title = "Низ",
                    selectedMode = sideBottom,
                    onModeSelected = { mode ->
                        sideBottom = mode
                        prefs.putString(R.string.pref_key_led_side_bottom, mode)
                        sendClearOnce(context)
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                SideSelectorCard(
                    title = "Лево",
                    selectedMode = sideLeft,
                    onModeSelected = { mode ->
                        sideLeft = mode
                        prefs.putString(R.string.pref_key_led_side_left, mode)
                        sendClearOnce(context)
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Bottom gap
                OutlinedTextField(
                    value = bottomGapText,
                    onValueChange = { newText ->
                        bottomGapText = newText
                        newText.toIntOrNull()?.let { value ->
                            if (value >= 0) {
                                prefs.putInt(R.string.pref_key_bottom_gap, value)
                                sendClearOnce(context)
                            }
                        }
                    },
                    label = { Text("Пропуск снизу (для саундбара)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = bottomGapText.isNotEmpty() && bottomGapText.toIntOrNull() == null
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Settings cards
                SettingCard(
                    title = "Начальный угол",
                    value = getCornerName(startCorner),
                    onClick = { showEditDialog = true }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                SettingCard(
                    title = "Направление",
                    value = getDirectionName(direction),
                    onClick = { showEditDialog = true }
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Legend
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Легенда:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LegendItem(color = Color(0xFF4CAF50), text = "LED #1 (первый)")
                        LegendItem(color = Color(0xFF2196F3), text = "Активные LED")
                        LegendItem(color = Color.Gray.copy(alpha = 0.4f), text = "Отключенные LED")
                        LegendItem(color = Color.Gray, text = "Экран")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Изменить настройки")
                }
            }
        }
    }
    
    if (showEditDialog) {
        EditLayoutDialog(
            xLed = xLed,
            yLed = yLed,
            startCorner = startCorner,
            direction = direction,
            onDismiss = { showEditDialog = false },
            onSave = { newX, newY, newCorner, newDir ->
                xLedText = newX.toString()
                yLedText = newY.toString()
                startCorner = newCorner
                direction = newDir
                
                // Save to preferences
                prefs.putString(R.string.pref_key_x_led, newX.toString())
                prefs.putString(R.string.pref_key_y_led, newY.toString())
                prefs.putString(R.string.pref_key_led_start_corner, newCorner)
                prefs.putString(R.string.pref_key_led_direction, newDir)
                
                showEditDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideSelectorCard(
    title: String,
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedMode == "enabled",
                    onClick = { onModeSelected("enabled") },
                    label = { Text("Вкл", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == "disabled",
                    onClick = { onModeSelected("disabled") },
                    label = { Text("Откл", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == "not_installed",
                    onClick = { onModeSelected("not_installed") },
                    label = { Text("Нет", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun LedVisualization(
    xLed: Int,
    yLed: Int,
    startCorner: String,
    direction: String,
    sideTop: String,
    sideRight: String,
    sideBottom: String,
    sideLeft: String,
    bottomGap: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                
                // Draw screen rectangle
                val screenPadding = 60f
                drawRect(
                    color = Color.Gray.copy(alpha = 0.3f),
                    topLeft = Offset(screenPadding, screenPadding),
                    size = androidx.compose.ui.geometry.Size(
                        width - screenPadding * 2,
                        height - screenPadding * 2
                    ),
                    style = Stroke(width = 2f)
                )
                
                // Calculate LED positions
                val ledPositions = calculateLedPositions(
                    xLed, yLed, startCorner, direction,
                    sideTop, sideRight, sideBottom, sideLeft, bottomGap,
                    width, height, screenPadding
                )
                
                // Draw LEDs
                ledPositions.forEachIndexed { index, ledData ->
                    val color = when {
                        !ledData.enabled -> Color.Gray.copy(alpha = 0.4f) // Disabled LED
                        index == 0 -> Color(0xFF4CAF50) // Green for first LED
                        else -> Color(0xFF2196F3) // Blue for others
                    }
                    
                    // Draw LED circle
                    drawCircle(
                        color = color,
                        radius = if (index == 0) 12f else 8f,
                        center = ledData.position
                    )
                    
                    // Draw LED number (for first few and last few)
                    if (index < 5 || index >= ledPositions.size - 5 || index % 5 == 0) {
                        val textPaint = android.graphics.Paint().apply {
                            this.color = if (ledData.enabled) {
                                android.graphics.Color.WHITE
                            } else {
                                android.graphics.Color.GRAY
                            }
                            textSize = if (index == 0) 24f else 20f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = index == 0
                        }
                        
                        // Use native canvas to draw text
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        nativeCanvas.drawText(
                            "${index + 1}",
                            ledData.position.x,
                            ledData.position.y + (if (index == 0) 8f else 7f),
                            textPaint
                        )
                    }
                }
            }
        }
    }
}

data class LedData(
    val position: Offset,
    val enabled: Boolean
)

fun calculateLedPositions(
    xLed: Int,
    yLed: Int,
    startCorner: String,
    direction: String,
    sideTop: String,
    sideRight: String,
    sideBottom: String,
    sideLeft: String,
    bottomGap: Int,
    width: Float,
    height: Float,
    padding: Float
): List<LedData> {
    val positions = mutableListOf<LedData>()
    
    val screenWidth = width - padding * 2
    val screenHeight = height - padding * 2
    val stepX = screenWidth / (xLed - 1).coerceAtLeast(1)
    val stepY = screenHeight / (yLed - 1).coerceAtLeast(1)
    
    // Determine edge order
    val edges = getEdgeOrder(startCorner, direction)
    
    // Calculate gap range for bottom edge
    val gapStart = if (bottomGap > 0) (xLed - bottomGap) / 2 else -1
    val gapEnd = if (bottomGap > 0) gapStart + bottomGap else -1
    
    for (edge in edges) {
        val sideMode = when {
            edge.startsWith("top_") -> sideTop
            edge.startsWith("right_") -> sideRight
            edge.startsWith("bottom_") -> sideBottom
            edge.startsWith("left_") -> sideLeft
            else -> "enabled"
        }
        
        // Skip if not installed
        if (sideMode == "not_installed") continue
        
        when (edge) {
            "top_lr" -> {
                // Top edge (left to right)
                for (i in 0 until xLed) {
                    positions.add(LedData(
                        position = Offset(padding + i * stepX, padding),
                        enabled = sideMode == "enabled"
                    ))
                }
            }
            "top_rl" -> {
                // Top edge (right to left)
                for (i in 0 until xLed) {
                    positions.add(LedData(
                        position = Offset(padding + (xLed - 1 - i) * stepX, padding),
                        enabled = sideMode == "enabled"
                    ))
                }
            }
            "right_tb" -> {
                // Right edge (top to bottom)
                for (i in 0 until yLed) {
                    positions.add(LedData(
                        position = Offset(padding + screenWidth, padding + i * stepY),
                        enabled = sideMode == "enabled"
                    ))
                }
            }
            "right_bt" -> {
                // Right edge (bottom to top)
                for (i in 0 until yLed) {
                    positions.add(LedData(
                        position = Offset(padding + screenWidth, padding + (yLed - 1 - i) * stepY),
                        enabled = sideMode == "enabled"
                    ))
                }
            }
            "bottom_rl" -> {
                // Bottom edge (right to left)
                for (i in 0 until xLed) {
                    val ledIndex = xLed - 1 - i
                    val isInGap = bottomGap > 0 && ledIndex >= gapStart && ledIndex < gapEnd
                    positions.add(LedData(
                        position = Offset(padding + ledIndex * stepX, padding + screenHeight),
                        enabled = sideMode == "enabled" && !isInGap
                    ))
                }
            }
            "bottom_lr" -> {
                // Bottom edge (left to right)
                for (i in 0 until xLed) {
                    val isInGap = bottomGap > 0 && i >= gapStart && i < gapEnd
                    positions.add(LedData(
                        position = Offset(padding + i * stepX, padding + screenHeight),
                        enabled = sideMode == "enabled" && !isInGap
                    ))
                }
            }
            "left_bt" -> {
                // Left edge (bottom to top)
                for (i in 0 until yLed) {
                    positions.add(LedData(
                        position = Offset(padding, padding + (yLed - 1 - i) * stepY),
                        enabled = sideMode == "enabled"
                    ))
                }
            }
            "left_tb" -> {
                // Left edge (top to bottom)
                for (i in 0 until yLed) {
                    positions.add(LedData(
                        position = Offset(padding, padding + i * stepY),
                        enabled = sideMode == "enabled"
                    ))
                }
            }
        }
    }
    
    return positions
}

private fun sendClearOnce(context: Context) {
    val intent = android.content.Intent(context, ScreenGrabberService::class.java).apply {
        action = ScreenGrabberService.ACTION_CLEAR
    }
    context.startService(intent)
}

fun getEdgeOrder(startCorner: String, direction: String): List<String> {
    return when (startCorner) {
        "top_left" -> {
            if (direction == "clockwise") {
                listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
            } else {
                listOf("left_tb", "bottom_lr", "right_bt", "top_rl")
            }
        }
        "top_right" -> {
            if (direction == "clockwise") {
                listOf("right_tb", "bottom_rl", "left_bt", "top_lr")
            } else {
                listOf("top_rl", "left_tb", "bottom_lr", "right_bt")
            }
        }
        "bottom_right" -> {
            if (direction == "clockwise") {
                listOf("bottom_rl", "left_bt", "top_lr", "right_tb")
            } else {
                listOf("right_bt", "top_rl", "left_tb", "bottom_lr")
            }
        }
        "bottom_left" -> {
            if (direction == "clockwise") {
                listOf("left_bt", "top_lr", "right_tb", "bottom_rl")
            } else {
                listOf("bottom_lr", "right_bt", "top_rl", "left_tb")
            }
        }
        else -> listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
    }
}

@Composable
fun SettingCard(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditLayoutDialog(
    xLed: Int,
    yLed: Int,
    startCorner: String,
    direction: String,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String, String) -> Unit
) {
    var newStartCorner by remember { mutableStateOf(startCorner) }
    var newDirection by remember { mutableStateOf(direction) }
    
    var expandedCorner by remember { mutableStateOf(false) }
    var expandedDirection by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки раскладки") },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = expandedCorner,
                    onExpandedChange = { expandedCorner = !expandedCorner }
                ) {
                    OutlinedTextField(
                        value = getCornerName(newStartCorner),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Начальный угол") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCorner) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expandedCorner,
                        onDismissRequest = { expandedCorner = false }
                    ) {
                        listOf("top_left", "top_right", "bottom_right", "bottom_left").forEach { corner ->
                            DropdownMenuItem(
                                text = { Text(getCornerName(corner)) },
                                onClick = {
                                    newStartCorner = corner
                                    expandedCorner = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                ExposedDropdownMenuBox(
                    expanded = expandedDirection,
                    onExpandedChange = { expandedDirection = !expandedDirection }
                ) {
                    OutlinedTextField(
                        value = getDirectionName(newDirection),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Направление") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDirection) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expandedDirection,
                        onDismissRequest = { expandedDirection = false }
                    ) {
                        listOf("clockwise", "counterclockwise").forEach { dir ->
                            DropdownMenuItem(
                                text = { Text(getDirectionName(dir)) },
                                onClick = {
                                    newDirection = dir
                                    expandedDirection = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(xLed, yLed, newStartCorner, newDirection)
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

fun getCornerName(corner: String): String {
    return when (corner) {
        "top_left" -> "Верхний левый"
        "top_right" -> "Верхний правый"
        "bottom_right" -> "Нижний правый"
        "bottom_left" -> "Нижний левый"
        else -> corner
    }
}

fun getDirectionName(direction: String): String {
    return when (direction) {
        "clockwise" -> "По часовой стрелке"
        "counterclockwise" -> "Против часовой стрелки"
        else -> direction
    }
}
