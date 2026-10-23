package app.soundfire.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Icons ported from the designs' inline SVGs (and the lucide icons the home design uses). */
object SfIcons {
    val Play = filled("play", "M7 4.5v15l13-7.5z")
    val Pause = filled("pause", "M6 4.5h4v15H6zM14 4.5h4v15h-4z")
    val Stop = filled("stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z")
    val Rewind = filled("rewind", "M11 6 3 12l8 6zM21 6l-8 6 8 6z")
    val Forward = filled("forward", "M13 6l8 6-8 6zM3 6l8 6-8 6z")
    val Search = stroked("search", 2.2f, "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M16.5 16.5 21 21")
    val Backspace = stroked("backspace", 2f, "M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7z", "m12 9 6 6M18 9l-6 6")
    val Heart = stroked("heart", 2f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val Plays = stroked("plays", 2f, "M8 5v14l11-7z")
    val ChevronRight = stroked("chevron", 2f, "m9 6 6 6-6 6")
    val Volume = stroked("volume", 1.8f, "M11 4.7 6 9H2v6h4l5 4.3z", "M15.5 8.5a5 5 0 0 1 0 7", "M19 5a10 10 0 0 1 0 14")
    val Shuffle = stroked("shuffle", 2f, "M16 3h5v5", "M4 20 21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")
    val User = stroked("user", 1.8f, "M12 4a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M4 21a8 8 0 0 1 16 0")

    private fun filled(name: String, vararg paths: String) = build(name, paths, stroke = null)
    private fun stroked(name: String, width: Float, vararg paths: String) = build(name, paths, stroke = width)

    private fun build(name: String, paths: Array<out String>, stroke: Float?): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = if (stroke == null) SolidColor(Color.White) else null,
                    stroke = stroke?.let { SolidColor(Color.White) },
                    strokeLineWidth = stroke ?: 0f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}

@Composable
fun SfIcon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
