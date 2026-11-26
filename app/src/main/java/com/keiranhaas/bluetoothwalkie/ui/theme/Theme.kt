package com.keiranhaas.bluetoothwalkie.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val TerminalColors =
    darkColorScheme(
        primary = Color(0xFF33FF66),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF0A3D1A),
        onPrimaryContainer = Color(0xFF33FF66),
        secondary = Color(0xFF22C55E),
        onSecondary = Color(0xFF000000),
        tertiary = Color(0xFFFFD84D),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF3D3405),
        onTertiaryContainer = Color(0xFFFFD84D),
        error = Color(0xFFFF5555),
        onError = Color(0xFF000000),
        errorContainer = Color(0xFF4A0A0A),
        onErrorContainer = Color(0xFFFF7777),
        background = Color(0xFF000000),
        onBackground = Color(0xFF33FF66),
        surface = Color(0xFF000000),
        onSurface = Color(0xFF33FF66),
        surfaceVariant = Color(0xFF0F1F14),
        onSurfaceVariant = Color(0xFF1FB84A),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF050A06),
        surfaceContainer = Color(0xFF08110A),
        surfaceContainerHigh = Color(0xFF0B170E),
        surfaceContainerHighest = Color(0xFF0B170E),
        outline = Color(0xFF1F7A35),
        outlineVariant = Color(0xFF14502A)
    )

private val TerminalShapes =
    Shapes(
        extraSmall = CutCornerShape(2.dp),
        small = CutCornerShape(4.dp),
        medium = CutCornerShape(4.dp),
        large = CutCornerShape(6.dp),
        extraLarge = CutCornerShape(8.dp)
    )

private fun TextStyle.mono(): TextStyle =
    copy(fontFamily = FontFamily.Monospace)

private val BaseTypography = Typography()

private val TerminalTypography =
    Typography(
        displayLarge = BaseTypography.displayLarge.mono(),
        displayMedium = BaseTypography.displayMedium.mono(),
        displaySmall = BaseTypography.displaySmall.mono(),
        headlineLarge = BaseTypography.headlineLarge.mono(),
        headlineMedium = BaseTypography.headlineMedium.mono(),
        headlineSmall = BaseTypography.headlineSmall.mono(),
        titleLarge = BaseTypography.titleLarge.mono(),
        titleMedium = BaseTypography.titleMedium.mono(),
        titleSmall = BaseTypography.titleSmall.mono(),
        bodyLarge = BaseTypography.bodyLarge.mono(),
        bodyMedium = BaseTypography.bodyMedium.mono(),
        bodySmall = BaseTypography.bodySmall.mono(),
        labelLarge = BaseTypography.labelLarge.mono(),
        labelMedium = BaseTypography.labelMedium.mono(),
        labelSmall = BaseTypography.labelSmall.mono()
    )

@Composable
fun MeshWalkieTheme(
    content:
    @Composable () -> Unit
) {

    val view =
        LocalView.current

    if (!view.isInEditMode) {

        SideEffect {

            val window =
                (view.context as Activity)
                    .window

            WindowCompat
                .getInsetsController(
                    window,
                    view
                )
                .isAppearanceLightStatusBars =
                false
        }
    }

    MaterialTheme(
        colorScheme = TerminalColors,
        shapes = TerminalShapes,
        typography = TerminalTypography,
        content = content
    )
}