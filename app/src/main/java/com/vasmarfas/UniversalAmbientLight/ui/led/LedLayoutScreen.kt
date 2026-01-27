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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedLayoutScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    
    var xLed by remember { mutableStateOf(prefs.getInt(R.string.pref_key_x_led)) }
    var yLed by remember { mutableStateOf(prefs.getInt(R.string.pref_key_y_led)) }
    var startCorner by remember { 
        mutableStateOf(prefs.getString(R.string.pref_key_led_start_corner, "top_left") ?: "top_left") 
    }
    var direction by remember { 
        mutableStateOf(prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise") 
    }
    
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Раскладка светодиодов",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Всего LED: ${2 * (xLed + yLed)} (${xLed}×2 + ${yLed}×2)",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
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
                    LegendItem(color = Color(0xFF2196F3), text = "Остальные LED")
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
    
    if (showEditDialog) {
        EditLayoutDialog(
            xLed = xLed,
            yLed = yLed,
            startCorner = startCorner,
            direction = direction,
            onDismiss = { showEditDialog = false },
            onSave = { newX, newY, newCorner, newDir ->
                xLed = newX
                yLed = newY
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

@Composable
fun LedVisualization(
    xLed: Int,
    yLed: Int,
    startCorner: String,
    direction: String,
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
                    width, height, screenPadding
                )
                
                // Draw LEDs
                ledPositions.forEachIndexed { index, position ->
                    val color = if (index == 0) {
                        Color(0xFF4CAF50) // Green for first LED
                    } else {
                        Color(0xFF2196F3) // Blue for others
                    }
                    
                    // Draw LED circle
                    drawCircle(
                        color = color,
                        radius = if (index == 0) 12f else 8f,
                        center = position
                    )
                    
                    // Draw LED number (for first few and last few)
                    if (index < 5 || index >= ledPositions.size - 5 || index % 5 == 0) {
                        val textPaint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.WHITE
                            textSize = if (index == 0) 24f else 20f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = index == 0
                        }
                        
                        // Use native canvas to draw text
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        nativeCanvas.drawText(
                            "${index + 1}",
                            position.x,
                            position.y + (if (index == 0) 8f else 7f),
                            textPaint
                        )
                    }
                }
            }
        }
    }
}

fun calculateLedPositions(
    xLed: Int,
    yLed: Int,
    startCorner: String,
    direction: String,
    width: Float,
    height: Float,
    padding: Float
): List<Offset> {
    val positions = mutableListOf<Offset>()
    val totalLeds = 2 * (xLed + yLed)
    
    val screenWidth = width - padding * 2
    val screenHeight = height - padding * 2
    val stepX = screenWidth / (xLed - 1).coerceAtLeast(1)
    val stepY = screenHeight / (yLed - 1).coerceAtLeast(1)
    
    // Determine edge order
    val edges = getEdgeOrder(startCorner, direction)
    
    for (edge in edges) {
        when (edge) {
            "top_lr" -> {
                // Top edge (left to right)
                for (i in 0 until xLed) {
                    positions.add(Offset(padding + i * stepX, padding))
                }
            }
            "top_rl" -> {
                // Top edge (right to left)
                for (i in 0 until xLed) {
                    positions.add(Offset(padding + (xLed - 1 - i) * stepX, padding))
                }
            }
            "right_tb" -> {
                // Right edge (top to bottom)
                for (i in 0 until yLed) {
                    positions.add(Offset(padding + screenWidth, padding + i * stepY))
                }
            }
            "right_bt" -> {
                // Right edge (bottom to top)
                for (i in 0 until yLed) {
                    positions.add(Offset(padding + screenWidth, padding + (yLed - 1 - i) * stepY))
                }
            }
            "bottom_rl" -> {
                // Bottom edge (right to left)
                for (i in 0 until xLed) {
                    positions.add(Offset(padding + (xLed - 1 - i) * stepX, padding + screenHeight))
                }
            }
            "bottom_lr" -> {
                // Bottom edge (left to right)
                for (i in 0 until xLed) {
                    positions.add(Offset(padding + i * stepX, padding + screenHeight))
                }
            }
            "left_bt" -> {
                // Left edge (bottom to top)
                for (i in 0 until yLed) {
                    positions.add(Offset(padding, padding + (yLed - 1 - i) * stepY))
                }
            }
            "left_tb" -> {
                // Left edge (top to bottom)
                for (i in 0 until yLed) {
                    positions.add(Offset(padding, padding + i * stepY))
                }
            }
        }
    }
    
    return positions
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
    var newXLed by remember { mutableStateOf(xLed.toString()) }
    var newYLed by remember { mutableStateOf(yLed.toString()) }
    var newStartCorner by remember { mutableStateOf(startCorner) }
    var newDirection by remember { mutableStateOf(direction) }
    
    var expandedCorner by remember { mutableStateOf(false) }
    var expandedDirection by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки раскладки") },
        text = {
            Column {
                OutlinedTextField(
                    value = newXLed,
                    onValueChange = { newXLed = it },
                    label = { Text("Горизонтальные LED") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = newYLed,
                    onValueChange = { newYLed = it },
                    label = { Text("Вертикальные LED") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
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
                    val x = newXLed.toIntOrNull() ?: xLed
                    val y = newYLed.toIntOrNull() ?: yLed
                    onSave(x, y, newStartCorner, newDirection)
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
