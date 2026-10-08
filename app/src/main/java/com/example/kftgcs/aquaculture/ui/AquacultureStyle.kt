package com.example.kftgcs.aquaculture.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Matches the navy/blue selection screens without changing the application theme. */
@Composable
internal fun AquacultureTheme(content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF42A5F5), onPrimary = Color(0xFF061525),
            secondary = Color(0xFF4DD0C6), background = Color(0xFF0A0E27),
            surface = Color(0xFF111B2C), surfaceVariant = Color(0xFF1C2A40),
            onSurface = Color(0xFFEAF0F8), onSurfaceVariant = Color(0xFF9CAEC5),
            outline = Color(0xFF354760), error = Color(0xFFFF8C8C)
        ),
        typography = typography.copy(
            headlineMedium = typography.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = typography.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = typography.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
            bodySmall = typography.bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp),
            labelLarge = typography.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        ), content = content
    )
}

@Composable
internal fun AquacultureSection(title: String, icon: ImageVector, subtitle: String? = null,
                                content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
internal fun AquacultureMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ButtonIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.size(22.dp))
    Spacer(Modifier.width(7.dp))
}
