package app.soundfire.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.soundfire.R
import app.soundfire.data.ArtworkSize
import app.soundfire.player.PlayerState
import app.soundfire.ui.components.Artwork
import app.soundfire.ui.components.FocusBox
import app.soundfire.ui.components.SfIcon
import app.soundfire.ui.components.SfIcons
import app.soundfire.ui.components.formatDuration
import app.soundfire.ui.theme.SafeArea
import app.soundfire.ui.theme.SfColors
import app.soundfire.ui.theme.sfText
import kotlinx.coroutines.delay

private val Ink = Color(0xFF030303)

/** "Soundfire — TV Playing now" (MagicPath friendly-dusk-4964), opened from the home pill's thumbnail. */
@Composable
fun NowPlayingScreen(
    state: PlayerState,
    searchFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val track = state.current
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // Ambient mode: the title fades away after 3s without remote input; any key brings it back.
    var lastInput by remember { mutableLongStateOf(0L) }
    var showTitle by remember { mutableStateOf(true) }
    LaunchedEffect(lastInput) {
        showTitle = true
        delay(3_000)
        showTitle = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SfColors.PlayingBackground)
            .onPreviewKeyEvent {
                lastInput = System.nanoTime()
                false
            },
    ) {
        // Full-resolution cover, sharp and full-bleed; the gradients below keep the text legible.
        Artwork(track, ArtworkSize.Original, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Ink.copy(0.72f), 0.48f to Ink.copy(0.32f), 1f to Ink.copy(0.64f)),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Ink.copy(0.68f), 0.58f to Ink.copy(0.12f), 1f to Ink.copy(0.9f)),
            ),
        )

        Row(
            Modifier.fillMaxWidth().padding(start = SafeArea.Horizontal, end = SafeArea.Horizontal, top = SafeArea.Vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusBox(onClick = onHome, shape = CircleShape) { focused ->
                Row(
                    Modifier
                        .background(Color.Black.copy(if (focused) 0.32f else 0.18f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    BasicText("SoundFire", style = sfText(18, FontWeight.Medium, Color.White.copy(if (focused) 1f else 0.88f), tracking = -0.03))
                }
            }
            Spacer(Modifier.weight(1f))
            FocusBox(
                onClick = onOpenSearch,
                modifier = Modifier.size(44.dp).focusRequester(searchFocus),
                shape = CircleShape,
                contentAlignment = Alignment.Center,
            ) { focused ->
                Box(Modifier.size(44.dp).background(Color.Black.copy(if (focused) 0.36f else 0.24f), CircleShape))
                SfIcon(SfIcons.Search, Color.White.copy(if (focused) 1f else 0.82f), 20.dp)
            }
        }

        AnimatedVisibility(
            visible = showTitle,
            enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { it / 6 },
            exit = fadeOut(tween(700)) + slideOutVertically(tween(700)) { it / 6 },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = SafeArea.Horizontal, bottom = 172.dp),
        ) {
            Column(Modifier.widthIn(max = 1000.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Equalizer(animating = state.isPlaying)
                    Spacer(Modifier.width(12.dp))
                    BasicText(
                        if (state.isPlaying) "NOW PLAYING" else if (track == null) "IDLE" else "PAUSED",
                        style = sfText(12, FontWeight.Medium, Color.White.copy(0.58f), tracking = 0.32),
                    )
                }
                Spacer(Modifier.height(16.dp))
                BasicText(
                    track?.title ?: "Quiet room",
                    style = sfText(112, FontWeight.Light, Color.White.copy(0.94f), tracking = -0.075, lineHeight = 0.92.em),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
                BasicText(
                    track?.artist ?: "Choose a track from search",
                    style = sfText(28, FontWeight.Light, Color.White.copy(0.7f), tracking = -0.03),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PlayerBar(
            state = state,
            playFocus = playFocus,
            onTogglePlay = onTogglePlay,
            onSeek = onSeek,
            onStop = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = SafeArea.Horizontal, end = SafeArea.Horizontal, bottom = SafeArea.Vertical),
        )
    }
}

@Composable
private fun PlayerBar(
    state: PlayerState,
    playFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val track = state.current
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val bar = RoundedCornerShape(50)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(40.dp, bar, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Color.Black.copy(0.28f), bar)
            .border(1.dp, Color.White.copy(0.1f), bar)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                BasicText(
                    track?.title ?: "Nothing playing",
                    style = sfText(15, FontWeight.Medium, Color.White.copy(0.88f), tracking = -0.02),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    track?.artist ?: "Open search to begin",
                    style = sfText(12, FontWeight.Light, Color.White.copy(0.48f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeLabel(if (track != null) formatDuration(state.positionMs) else "–:––", TextAlign.End)
                TransportButton(SfIcons.Rewind, 16.dp, enabled = track != null) { onSeek(-10_000) }
                FocusBox(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(44.dp).focusRequester(playFocus),
                    shape = CircleShape,
                    ringColor = SfColors.PlayingAccent.copy(0.85f),
                    ringOffset = 2.dp,
                    focusedScale = 1.06f,
                    contentAlignment = Alignment.Center,
                ) { focused ->
                    Box(Modifier.size(44.dp).background(Color.White.copy(if (focused) 1f else 0.86f), CircleShape))
                    SfIcon(if (state.isPlaying) SfIcons.Pause else SfIcons.Play, Color.Black, 20.dp)
                }
                TransportButton(SfIcons.Forward, 16.dp, enabled = track != null) { onSeek(10_000) }
                TransportButton(SfIcons.Stop, 14.dp, enabled = track != null, dim = true, onClick = onStop)
                TimeLabel(if (track != null) formatDuration(state.durationMs) else "–:––", TextAlign.Start)
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                val next = state.upNext
                BasicText(
                    if (next != null) "UP NEXT · ${next.title}" else "Related tracks continue automatically",
                    style = sfText(12, color = Color.White.copy(0.46f), tracking = if (next != null) 0.04 else 0.0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(Color.White.copy(0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(SfColors.PlayingAccent.copy(0.88f), CircleShape),
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String, align: TextAlign) {
    BasicText(text, style = sfText(12, color = Color.White.copy(0.42f)).copy(textAlign = align), modifier = Modifier.width(56.dp))
}

@Composable
private fun TransportButton(icon: ImageVector, iconSize: Dp, enabled: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        enabled = enabled,
        shape = CircleShape,
        ringColor = SfColors.PlayingAccent.copy(0.85f),
        ringOffset = 2.dp,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Box(Modifier.size(36.dp).background(Color.White.copy(if (focused) 0.1f else 0f), CircleShape))
        val base = if (dim) 0.54f else 0.66f
        SfIcon(icon, Color.White.copy(if (!enabled) 0.3f else if (focused) 1f else base), iconSize)
    }
}

/** The three-bar equalizer from the design's `sof-eq` keyframes. */
@Composable
private fun Equalizer(animating: Boolean) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0, 225, 450).forEach { delayMs ->
            val scale by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(450, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMs),
                ),
                label = "bar",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleY = if (animating) scale else 0.5f
                    }
                    .background(SfColors.PlayingAccent, CircleShape),
            )
        }
    }
}
