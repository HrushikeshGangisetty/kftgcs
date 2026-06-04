package com.example.kftgcs.usersettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    navController: NavController,
    viewModel: UserSettingsViewModel
) {
    val settings by viewModel.settings.collectAsState()
    val textColor = settings.textColor
    val scale = settings.fontSize.scaleFactor

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "User Settings",
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = (20 * scale).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1117))
            )
        },
        containerColor = Color(0xFF161B22)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Section Header ────────────────────────────────────────────────
            Text(
                text = "Display Preferences",
                color = Color(0xFF58A6FF),
                fontSize = (13 * scale).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            // ── Font Size Section ─────────────────────────────────────────────
            SectionCard(
                title = "Font Size",
                icon = Icons.Default.FormatSize,
                accentColor = Color(0xFF58A6FF),
                scale = scale,
                textColor = textColor
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FontSizeOption.entries.forEach { option ->
                        val selected = settings.fontSize == option
                        Button(
                            onClick = { viewModel.setFontSize(option) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF1F6FEB) else Color(0xFF21262D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = if (selected) 6.dp else 0.dp
                            )
                        ) {
                            Text(
                                text = option.label,
                                fontSize = when (option) {
                                    FontSizeOption.SMALL  -> (13 * scale).sp
                                    FontSizeOption.MEDIUM -> (15 * scale).sp
                                    FontSizeOption.LARGE  -> (18 * scale).sp
                                },
                                color = if (selected) Color.White else Color(0xFF8B949E),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Live preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D1117))
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Preview: The quick brown fox jumps over the lazy dog.",
                        color = textColor,
                        fontSize = (15 * scale).sp,
                        lineHeight = (22 * scale).sp
                    )
                }
            }

            // ── Text Colour Section ───────────────────────────────────────────
            SectionCard(
                title = "Text Colour",
                icon = Icons.Default.Palette,
                accentColor = Color(0xFF3FB950),
                scale = scale,
                textColor = textColor
            ) {
                ColorPickerPanel(
                    currentColor = settings.textColor,
                    onColorSelected = { viewModel.setTextColor(it) },
                    scale = scale,
                    textColor = textColor
                )
            }

            // ── Drone Path Colour Section ─────────────────────────────────────
            SectionCard(
                title = "Drone Path Line Colour",
                icon = Icons.Default.Timeline,
                accentColor = Color(0xFFF78166),
                scale = scale,
                textColor = textColor
            ) {
                ColorPickerPanel(
                    currentColor = settings.dronePathColor,
                    onColorSelected = { viewModel.setDronePathColor(it) },
                    scale = scale,
                    textColor = textColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    scale: Float,
    textColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Card header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = title,
                    color = textColor,
                    fontSize = (16 * scale).sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HorizontalDivider(
                color = Color(0xFF30363D),
                modifier = Modifier.padding(vertical = 14.dp)
            )
            content()
        }
    }
}

@Composable
private fun ColorPickerPanel(
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    scale: Float,
    textColor: Color
) {
    val presets = listOf(
        Color.White,
        Color(0xFFE6EDF3),   // off-white
        Color(0xFFFF6B6B),   // vivid red
        Color(0xFFFF8C00),   // vivid orange
        Color(0xFFFFD700),   // vivid gold
        Color(0xFF3FB950),   // vivid green
        Color(0xFF00E5FF),   // vivid cyan
        Color(0xFF58A6FF),   // vivid blue
        Color(0xFF9C5AFF),   // vivid purple
        Color(0xFFFF4081),   // vivid pink
        Color(0xFFFF3D00),   // deep orange
        Color(0xFF00C853),   // deep green
        Color(0xFF0091EA),   // deep blue
        Color(0xFFAA00FF),   // deep purple
        Color(0xFFF06292),   // light pink
        Color(0xFF80DEEA),   // light teal
        Color(0xFFFFCC02),   // amber
        Color(0xFF69F0AE),   // mint
        Color(0xFFFF6E40),   // coral
        Color(0xFFB2FF59),   // lime
    )

    var red   by remember(currentColor) { mutableFloatStateOf(currentColor.red)   }
    var green by remember(currentColor) { mutableFloatStateOf(currentColor.green) }
    var blue  by remember(currentColor) { mutableFloatStateOf(currentColor.blue)  }
    var alpha by remember(currentColor) { mutableFloatStateOf(currentColor.alpha) }

    val pickedColor = Color(red, green, blue, alpha)

    // ── Palette label ─────────────────────────────────────────────────────────
    Text(
        text = "PRESET PALETTE",
        color = Color(0xFF8B949E),
        fontSize = (11 * scale).sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    // Preset grid (5 per row)
    val rows = presets.chunked(5)
    rows.forEach { rowColors ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            rowColors.forEach { color ->
                val isSelected = currentColor == color
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color(0xFF58A6FF) else Color(0xFF30363D),
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (color.red + color.green + color.blue > 1.5f)
                                        Color(0xFF0D1117) else Color.White
                                )
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = "CUSTOM COLOUR  (RGB)",
        color = Color(0xFF8B949E),
        fontSize = (11 * scale).sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    // Colour preview + hex
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(pickedColor)
                .border(2.dp, Color(0xFF30363D), RoundedCornerShape(10.dp))
        )
        Column {
            Text(
                text = "R ${(red * 255).toInt()}   G ${(green * 255).toInt()}   B ${(blue * 255).toInt()}",
                color = textColor,
                fontSize = (13 * scale).sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Opacity ${(alpha * 100).toInt()}%",
                color = Color(0xFF8B949E),
                fontSize = (12 * scale).sp
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    ColorSlider(label = "R", value = red,   trackColor = Color(0xFFFF6B6B), scale = scale, textColor = textColor) {
        red = it; onColorSelected(Color(red, green, blue, alpha))
    }
    ColorSlider(label = "G", value = green, trackColor = Color(0xFF3FB950), scale = scale, textColor = textColor) {
        green = it; onColorSelected(Color(red, green, blue, alpha))
    }
    ColorSlider(label = "B", value = blue,  trackColor = Color(0xFF58A6FF), scale = scale, textColor = textColor) {
        blue = it; onColorSelected(Color(red, green, blue, alpha))
    }
    ColorSlider(label = "A", value = alpha, trackColor = Color(0xFF8B949E), scale = scale, textColor = textColor) {
        alpha = it; onColorSelected(Color(red, green, blue, alpha))
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    trackColor: Color,
    scale: Float,
    textColor: Color,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            color = trackColor,
            fontSize = (14 * scale).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = trackColor,
                activeTrackColor = trackColor,
                inactiveTrackColor = Color(0xFF30363D)
            )
        )
        Text(
            text = "${(value * 255).toInt()}",
            color = textColor,
            fontSize = (13 * scale).sp,
            modifier = Modifier.width(30.dp)
        )
    }
}
