package com.example.kftgcs.aquaculture.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Prawn line icon that takes the same tint as the application's Material icons. */
internal object AquacultureIcons {
    val Prawn: ImageVector by lazy {
        ImageVector.Builder("Prawn", 48.dp, 48.dp, 48f, 48f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Head and curved, segmented body.
                moveTo(28f, 13f)
                curveTo(35f, 10f, 40f, 13f, 40f, 20f)
                curveTo(40f, 28f, 33f, 35f, 23f, 36f)
                curveTo(13f, 37f, 7f, 31f, 8f, 24f)
                curveTo(8f, 19f, 12f, 16f, 17f, 16f)
                curveTo(14f, 21f, 15f, 27f, 20f, 28f)
                curveTo(26f, 31f, 32f, 26f, 32f, 21f)
                curveTo(32f, 18f, 29f, 17f, 28f, 13f)
                close()
                moveTo(34f, 24f); lineTo(39f, 25f)
                moveTo(30f, 29f); lineTo(34f, 33f)
                moveTo(23f, 29f); lineTo(23f, 36f)
                moveTo(17f, 26f); lineTo(13f, 32f)
                // Tail fan, antennae and legs.
                moveTo(8f, 23f); lineTo(2f, 18f); lineTo(3f, 27f); lineTo(8f, 29f)
                moveTo(8f, 29f); lineTo(2f, 30f); lineTo(7f, 35f); lineTo(13f, 33f)
                moveTo(33f, 12f); curveTo(34f, 5f, 39f, 3f, 43f, 5f)
                moveTo(37f, 13f); curveTo(41f, 8f, 47f, 9f, 46f, 14f)
                moveTo(30f, 24f); lineTo(27f, 22f)
                moveTo(27f, 28f); lineTo(24f, 25f)
                moveTo(22f, 28f); lineTo(20f, 24f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(36f, 17f)
                arcTo(1.4f, 1.4f, 0f, false, true, 33.2f, 17f)
                arcTo(1.4f, 1.4f, 0f, false, true, 36f, 17f)
                close()
            }
        }.build()
    }
}
