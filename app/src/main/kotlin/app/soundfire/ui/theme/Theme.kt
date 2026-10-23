package app.soundfire.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.soundfire.R

object SfColors {
    // Home screen (MagicPath "SoundFire — TV Home Screen")
    val HomeBackground = Color(0xFF0A0A0A)
    val Wordmark = Color(0xFFF3EEE9)
    val Accent = Color(0xFFE96732)
    val FocusRing = Color(0xFFF16A32)
    val OnAccent = Color(0xFF160D08)
    val Pill = Color(0xFF181818)

    // Playing-now screen (MagicPath "Soundfire — TV Playing now")
    val PlayingAccent = Color(0xFFFF5A1F)
    val PlayingBackground = Color(0xFF030303)
}

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Text style helper mirroring the designs' Tailwind values (size in design px, tracking in em). */
fun sfText(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    tracking: Double = 0.0,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = Inter,
    fontSize = size.sp,
    fontWeight = weight,
    color = color,
    letterSpacing = tracking.em,
    lineHeight = lineHeight,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/**
 * Android TV overscan-safe margins (5% of a 16:9 frame). Layouts fill the screen edge to edge and
 * keep focusable content inside these insets so nothing is cut off on TVs that overscan.
 */
object SafeArea {
    val Horizontal = 96.dp
    val Vertical = 54.dp
}

/**
 * The designs are authored for a 1920×1080 frame. Density is scaled so 1dp is one design pixel on
 * the tighter axis: a 16:9 screen of any resolution (1080p, 4K, a 1080p UI on a 4K panel) maps to
 * exactly 1920×1080dp, and other aspect ratios gain extra room on the looser axis instead of
 * overflowing. Text, icons and spacing all scale uniformly with it.
 */
@Composable
fun SoundFireTheme(content: @Composable () -> Unit) {
    val size = LocalWindowInfo.current.containerSize
    val scale = minOf(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT).takeIf { it > 0f } ?: 1f
    CompositionLocalProvider(
        LocalDensity provides Density(density = scale, fontScale = 1f),
        content = content,
    )
}

private const val DESIGN_WIDTH = 1920f
private const val DESIGN_HEIGHT = 1080f
