package app.soundfire.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.soundfire.ui.theme.SfColors

/**
 * A D-pad focusable, clickable box. OK/Enter on the remote clicks it. When focused it draws the
 * designs' `focus-visible:ring` (optionally offset outside the shape) and can scale up, tvOS style.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    enabled: Boolean = true,
    ringColor: Color = SfColors.FocusRing,
    ringWidth: Dp = 2.dp,
    ringOffset: Dp = 0.dp,
    focusedScale: Float = 1f,
    onFocused: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(160), label = "focusScale")
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawWithContent {
                drawContent()
                if (focused && ringColor != Color.Transparent) {
                    val offset = ringOffset.toPx() + ringWidth.toPx() / 2
                    val outline = shape.createOutline(
                        Size(size.width + offset * 2, size.height + offset * 2),
                        layoutDirection,
                        this,
                    )
                    translate(-offset, -offset) {
                        drawOutline(outline, ringColor, style = Stroke(ringWidth.toPx()))
                    }
                }
            }
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}
