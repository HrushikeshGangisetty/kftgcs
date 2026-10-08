package com.example.kftgcs.aquaculture.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OperationSelectionScreen(aquacultureAvailable: Boolean, onAgriculture: () -> Unit, onAquaculture: () -> Unit) {
    SelectionLayout("Select Operation", "Choose the operation for this vehicle") {
        Row(Modifier.widthIn(max = if (aquacultureAvailable) 460.dp else 220.dp).align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OperationCard("Agriculture", "Crop operations", Icons.Outlined.Agriculture, onAgriculture, Modifier.weight(1f))
            if (aquacultureAvailable) OperationCard("Aquaculture", "Pond feeding", Icons.Outlined.Water, onAquaculture, Modifier.weight(1f))
        }
    }
}

@Composable
fun AquacultureCategoryScreen(onPrawn: () -> Unit, onBack: () -> Unit) {
    SelectionLayout("Aquaculture", "Select your culture") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OperationCard("Prawn", "Plan pond feeding", AquacultureIcons.Prawn, onPrawn, Modifier.weight(1f))
            OperationCard("Fish", "Coming Soon", Icons.Outlined.SetMeal, {}, Modifier.weight(1f), enabled = false)
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            ButtonIcon(Icons.AutoMirrored.Filled.ArrowBack)
            Text("Back to operations")
        }
    }
}

@Composable
private fun SelectionLayout(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    AquacultureTheme {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0E27), Color(0xFF1A1F3A), Color(0xFF0F1419))))) {
            Column(Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp).widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.FlightTakeoff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

@Composable
private fun OperationCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit,
                          modifier: Modifier, enabled: Boolean = true) {
    OutlinedCard(onClick = onClick, enabled = enabled,
        modifier = modifier.height(170.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFF162235),
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color(0xFF121B2B), disabledContentColor = Color(0xFF63728B)),
        border = BorderStroke(1.dp, if (enabled) Color(0xFF304766) else Color(0xFF233047))) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, if (title == "Prawn") "Prawn" else null, Modifier.size(56.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFF63728B))
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF63728B))
        }
    }
}
