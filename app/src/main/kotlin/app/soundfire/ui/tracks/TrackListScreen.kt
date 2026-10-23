package app.soundfire.ui.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.soundfire.data.ArtworkSize
import app.soundfire.data.Track
import app.soundfire.player.PlayerState
import app.soundfire.ui.components.Artwork
import app.soundfire.ui.components.FocusBox
import app.soundfire.ui.components.NowPlayingPill
import app.soundfire.ui.components.SfIcon
import app.soundfire.ui.components.SfIcons
import app.soundfire.ui.components.compact
import app.soundfire.ui.components.formatDuration
import app.soundfire.ui.theme.SafeArea
import app.soundfire.ui.theme.SfColors
import app.soundfire.ui.theme.sfText

/**
 * Full-screen list of tracks: search results ("Search" key) or a user's complete likes. Pages load
 * as focus nears the end, so the whole result set is reachable by scrolling, as on soundcloud.com.
 */
@Composable
fun TrackListScreen(
    eyebrow: String,
    title: String,
    countLabel: (count: Int, exact: Boolean) -> String,
    viewModel: TrackListViewModel,
    playerState: PlayerState,
    onPlay: (List<Track>, Int) -> Unit,
    onTogglePlay: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    secondaryAction: Pair<String, () -> Unit>? = null,
    /** Exact size when already known locally (the linked user's synced likes). */
    knownTotal: Int? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val firstItem = remember { FocusRequester() }
    val playable = state.tracks.filter { it.playable }

    LaunchedEffect(state.tracks.isNotEmpty()) {
        if (state.tracks.isNotEmpty()) runCatching { firstItem.requestFocus() }
    }
    // Fetch the next page once focus gets within two rows of the end.
    LaunchedEffect(grid, state.tracks.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.tracks.size - 4) viewModel.loadMore() }
    }

    Box(Modifier.fillMaxSize().background(SfColors.HomeBackground)) {
        LazyVerticalGrid(
            state = grid,
            // Two large tiles per row on a 16:9 screen, readable from the couch.
            columns = GridCells.Adaptive(minSize = 780.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SafeArea.Horizontal - 8.dp,
                end = SafeArea.Horizontal - 8.dp,
                top = SafeArea.Vertical,
                bottom = 160.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 28.dp)) {
                    BasicText(eyebrow.uppercase(), style = sfText(11, FontWeight.Medium, SfColors.Accent, tracking = 0.28))
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        title,
                        style = sfText(48, FontWeight.Light, Color.White.copy(0.95f), tracking = -0.06),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Search reports a total up front; likes are counted as pages arrive.
                        val count = knownTotal ?: state.total ?: state.tracks.size
                        val exact = knownTotal != null || state.total != null || state.endReached
                        BasicText(
                            if (state.tracks.isEmpty() && state.loading) "Loading…" else countLabel(count, exact),
                            style = sfText(16, FontWeight.Light, Color.White.copy(0.5f)),
                        )
                        Spacer(Modifier.weight(1f))
                        if (playable.isNotEmpty()) {
                            ActionButton("Play all", SfIcons.Play, primary = true) { onPlay(playable, 0) }
                            Spacer(Modifier.width(12.dp))
                            ActionButton("Shuffle", SfIcons.Shuffle) { onPlay(playable.shuffled(), 0) }
                        }
                        secondaryAction?.let { (label, action) ->
                            Spacer(Modifier.width(12.dp))
                            ActionButton(label, SfIcons.Search, onClick = action)
                        }
                    }
                }
            }
            itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    position = index + 1,
                    isCurrent = playerState.current?.id == track.id,
                    isPlaying = playerState.isPlaying,
                    modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                    onClick = {
                        // Queue from this track onward through the loaded results.
                        val queue = state.tracks.drop(index).filter { it.playable }
                        onPlay(queue, 0)
                    },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.error != null -> FocusBox(onClick = viewModel::loadMore, shape = CircleShape) {
                            BasicText(state.error!!, style = sfText(16, color = Color.White.copy(0.7f)), modifier = Modifier.padding(16.dp))
                        }
                        state.loading -> BasicText("Loading more…", style = sfText(16, color = Color.White.copy(0.45f)))
                        state.tracks.isEmpty() -> BasicText("Nothing here yet.", style = sfText(18, color = Color.White.copy(0.5f)))
                        state.endReached -> BasicText("That's everything.", style = sfText(14, color = Color.White.copy(0.3f)))
                    }
                }
            }
        }

        playerState.current?.let { current ->
            NowPlayingPill(
                track = current,
                isPlaying = playerState.isPlaying,
                onOpen = onOpenNowPlaying,
                onTogglePlay = onTogglePlay,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = SafeArea.Horizontal, bottom = SafeArea.Vertical),
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    position: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().alpha(if (track.playable) 1f else 0.4f),
        enabled = track.playable,
        shape = RoundedCornerShape(20.dp),
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(if (focused) 0.08f else if (isCurrent) 0.05f else 0f), RoundedCornerShape(20.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "$position",
                style = sfText(18, color = Color.White.copy(0.3f)),
                modifier = Modifier.width(56.dp),
            )
            Artwork(track, ArtworkSize.Small, Modifier.size(150.dp).clip(RoundedCornerShape(14.dp)), compactGradient = true)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    track.title,
                    style = sfText(26, color = Color.White.copy(0.92f), tracking = -0.02),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    track.artist,
                    style = sfText(19, FontWeight.Light, Color.White.copy(0.55f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = Color.White.copy(0.4f)
                    when {
                        !track.playable -> BasicText("UNAVAILABLE", style = sfText(13, FontWeight.Medium, meta, tracking = 0.16))
                        isCurrent -> BasicText(
                            if (isPlaying) "NOW PLAYING" else "PAUSED",
                            style = sfText(13, FontWeight.Medium, SfColors.Accent, tracking = 0.16),
                        )
                        else -> BasicText(formatDuration(track.durationMs), style = sfText(15, color = meta))
                    }
                    Spacer(Modifier.width(22.dp))
                    SfIcon(SfIcons.Plays, meta, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicText(compact(track.plays), style = sfText(15, color = meta))
                    Spacer(Modifier.width(18.dp))
                    SfIcon(SfIcons.Heart, meta, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicText(compact(track.likes), style = sfText(15, color = meta))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = CircleShape, ringOffset = 3.dp) { focused ->
        Row(
            Modifier
                .background(
                    when {
                        primary -> SfColors.Accent
                        focused -> Color.White.copy(0.14f)
                        else -> Color.White.copy(0.07f)
                    },
                    CircleShape,
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (primary) SfColors.OnAccent else Color.White.copy(if (focused) 1f else 0.75f)
            SfIcon(icon, ink, 14.dp)
            Spacer(Modifier.width(10.dp))
            BasicText(label, style = sfText(15, FontWeight.Medium, ink))
        }
    }
}
