package com.plan.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Web-faithful palette (from web/plan.html :root CSS variables)
val Bg0 = Color(0xFF070A10)
val Bg1 = Color(0xFF0B1020)
val Cyan = Color(0xFF6CE7FF)
val Purple = Color(0xFFB88CFF)
val OkGreen = Color(0xFF2EF2A8)
val DangerRed = Color(0xFFFF4D4D)

val Surface0 = Color(0xFF0E1422)        // base card
val Surface1 = Color(0xFF131A2C)        // raised surface
val SurfaceSticky = Color(0xFF0B1020)   // sticky panel — opaque so meals don't bleed through
val StrokeSoft = Color(0x24FFFFFF)      // 14% white
val StrokeFaint = Color(0x14FFFFFF)
val TextHi = Color(0xEBFFFFFF)          // 92%
val TextMid = Color(0xADFFFFFF)         // 68%

private val PlanColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF001821),
    secondary = Purple,
    onSecondary = Color(0xFF1A0E2A),
    tertiary = OkGreen,
    onTertiary = Color(0xFF002112),
    background = Bg1,
    onBackground = TextHi,
    surface = Surface0,
    onSurface = TextHi,
    surfaceVariant = Surface1,
    onSurfaceVariant = TextMid,
    outline = StrokeSoft,
    outlineVariant = StrokeFaint,
    error = DangerRed,
    onError = Color(0xFF1A0000),
    errorContainer = Color(0x33FF4D4D),
    onErrorContainer = TextHi
)

@Composable
fun PlanTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = PlanColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(planBackground())
        ) {
            content()
        }
    }
}

@Composable
private fun planBackground(): Brush =
    Brush.verticalGradient(
        0f to Bg0,
        1f to Bg1
    )
