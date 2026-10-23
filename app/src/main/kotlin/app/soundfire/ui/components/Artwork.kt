package app.soundfire.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.soundfire.data.ArtworkSize
import app.soundfire.data.Track
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.max

/** Cover art, falling back to the designs' layered hue gradient while loading or when missing. */
@Composable
fun Artwork(
    track: Track?,
    size: ArtworkSize,
    modifier: Modifier = Modifier,
    compactGradient: Boolean = false,
) {
    val hue = track?.hue ?: 18
    Box(modifier.drawBehind { drawCoverGradient(hue, compactGradient) }) {
        val url = track?.artwork(size)
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** `coverStyle()` from the home design: two hue-shifted radial glows over a dark diagonal base. */
fun DrawScope.drawCoverGradient(hue: Int, compact: Boolean = false) {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.linearGradient(
            listOf(hsl(hue + 340, 0.32f, 0.09f), hsl(hue + 20, 0.38f, 0.19f)),
            start = Offset.Zero,
            end = if (compact) Offset(w, h * 1.4f) else Offset(w, h),
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(hsl(hue, 0.76f, 0.58f, 0.92f), Color.Transparent),
            center = Offset(w * 0.16f, h * 0.15f),
            radius = max(w, h) * 0.62f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(hsl(hue + 52, 0.70f, 0.28f, 0.95f), Color.Transparent),
            center = Offset(w * 0.86f, h * 0.86f),
            radius = max(w, h) * 0.66f,
        ),
    )
}

fun hsl(hue: Int, saturation: Float, lightness: Float, alpha: Float = 1f) =
    Color.hsl(Math.floorMod(hue, 360).toFloat(), saturation, lightness, alpha)
