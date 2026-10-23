package app.soundfire.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.soundfire.R
import app.soundfire.data.ArtworkSize
import app.soundfire.data.Track
import app.soundfire.player.PlayerState
import app.soundfire.ui.components.Artwork
import app.soundfire.ui.components.FocusBox
import app.soundfire.ui.components.NowPlayingPill
import app.soundfire.ui.components.SfIcon
import app.soundfire.ui.components.SfIcons
import app.soundfire.ui.theme.SafeArea
import app.soundfire.ui.theme.SfColors
import app.soundfire.ui.theme.sfText

/** "SoundFire — TV Home Screen" (MagicPath freshly-castle-2892), backed by live data. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    playerState: PlayerState,
    searchFocus: FocusRequester,
    onPlay: (List<Track>, Int) -> Unit,
    onTogglePlay: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenUsername: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(state.featured.isNotEmpty()) {
        if (state.featured.isNotEmpty()) runCatching { firstCard.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(SfColors.HomeBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            item {
                Header(
                    username = state.username,
                    likeCount = state.likeCount,
                    searchFocus = searchFocus,
                    onOpenSearch = onOpenSearch,
                    onOpenUsername = onOpenUsername,
                )
            }
            item {
                Section(top = 36) {
                    Padded {
                        Eyebrow(if (state.featuredIsPersonal) "Your mix · tuned to your taste" else "Curated for tonight", SfColors.Accent)
                        BasicText("Find your frequency.", style = sfText(48, FontWeight.Light, Color.White.copy(0.95f), tracking = -0.06))
                        Spacer(Modifier.height(28.dp))
                        // Card size stays constant; wider screens show more of them.
                        BoxWithConstraints {
                            val columns = ((maxWidth + CARD_GAP) / (FEATURED_CARD_WIDTH + CARD_GAP)).toInt().coerceIn(4, 6)
                            when {
                                state.error != null -> Message(state.error!!)
                                state.loading -> FeaturedPlaceholder(columns)
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                    val visible = state.featured.take(columns)
                                    visible.forEachIndexed { index, track ->
                                        FeaturedCard(
                                            track = track,
                                            index = index,
                                            isCurrent = playerState.current?.id == track.id,
                                            isPlaying = playerState.isPlaying,
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier),
                                            onClick = { onPlay(visible, index) },
                                        )
                                    }
                                    // Keep card proportions when there are fewer tracks than columns.
                                    repeat(columns - visible.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
            state.because?.let { (seed, tracks) ->
                item {
                    Section(top = 64) {
                        Padded {
                            Eyebrow("From your listening", Color.White.copy(0.35f))
                            BasicText(
                                "Because you played “${seed.title}”",
                                style = sfText(30, FontWeight.Light, Color.White.copy(0.9f), tracking = -0.05),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                        CompactRow(tracks, playerState, onPlay)
                    }
                }
            }
            if (state.trending.isNotEmpty()) {
                item {
                    Section(top = 64) {
                        Padded {
                            Eyebrow("What the room is playing", Color.White.copy(0.35f))
                            BasicText("Trending now", style = sfText(30, FontWeight.Light, Color.White.copy(0.9f), tracking = -0.05))
                            Spacer(Modifier.height(24.dp))
                        }
                        CompactRow(state.trending, playerState, onPlay)
                    }
                }
            }
            item {
                Section(top = 56) {
                    Padded {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText("EXPLORE A MOOD", style = sfText(14, FontWeight.Light, Color.White.copy(0.45f), tracking = 0.23))
                            Spacer(Modifier.width(16.dp))
                            Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(0.08f)))
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            state.moods.forEach { mood ->
                                MoodChip(mood, selected = mood == state.selectedMood, onClick = { viewModel.selectMood(mood) })
                            }
                        }
                    }
                    if (state.moodTracks.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        CompactRow(state.moodTracks, playerState, onPlay)
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

private val FEATURED_CARD_WIDTH = 300.dp
private val CARD_GAP = 20.dp

/** Full-width section; rows inside scroll edge to edge, headings use [Padded]. */
@Composable
private fun Section(top: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = top.dp)) { content() }
}

/** Keeps static content inside the TV safe area. */
@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SafeArea.Horizontal)) { content() }
}

@Composable
private fun Header(
    username: String?,
    likeCount: Int,
    searchFocus: FocusRequester,
    onOpenSearch: () -> Unit,
    onOpenUsername: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = SafeArea.Horizontal, end = SafeArea.Horizontal, top = SafeArea.Vertical, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        BasicText(
            buildAnnotatedString {
                append("Sound")
                withStyle(SpanStyle(color = SfColors.Accent)) { append("Fire") }
            },
            style = sfText(23, FontWeight.SemiBold, SfColors.Wordmark, tracking = -0.06),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(6.dp).alpha(0.8f).background(SfColors.Accent, CircleShape))
        Spacer(Modifier.weight(1f))
        FocusBox(onClick = onOpenUsername, shape = CircleShape, ringOffset = 2.dp) { focused ->
            Row(
                Modifier
                    .background(Color.White.copy(if (focused) 0.1f else 0.06f), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SfIcon(SfIcons.User, Color.White.copy(if (focused) 0.9f else 0.55f), 16.dp)
                Spacer(Modifier.width(10.dp))
                BasicText(
                    if (username == null) "Import your likes" else "@$username · $likeCount likes",
                    style = sfText(14, FontWeight.Light, Color.White.copy(if (focused) 0.95f else 0.6f)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        FocusBox(
            onClick = onOpenSearch,
            modifier = Modifier.size(48.dp).focusRequester(searchFocus),
            shape = CircleShape,
            contentAlignment = Alignment.Center,
        ) { focused ->
            Box(Modifier.size(48.dp).background(Color.White.copy(if (focused) 0.07f else 0f), CircleShape))
            SfIcon(SfIcons.Search, Color.White.copy(if (focused) 1f else 0.6f), 20.dp)
        }
    }
}

@Composable
private fun Eyebrow(text: String, color: Color) {
    BasicText(text.uppercase(), style = sfText(11, FontWeight.Medium, color, tracking = 0.28))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun FeaturedCard(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(4.dp), ringOffset = 4.dp, ringColor = Color.Transparent) { focused ->
        Column(Modifier.alpha(if (focused || isCurrent) 1f else 0.8f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.83f)
                    .shadow(24.dp, RoundedCornerShape(4.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(4.dp))
                    // The ring hugs the cover rather than the whole card, which reads better from a couch.
                    .then(if (focused) Modifier.border(2.dp, SfColors.FocusRing, RoundedCornerShape(4.dp)) else Modifier),
            ) {
                Artwork(track, ArtworkSize.Large, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(0.35f),
                            0.2f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(0.72f),
                        ),
                    ),
                )
                BasicText(
                    "%02d".format(index + 1),
                    style = sfText(10, FontWeight.Medium, Color.White.copy(0.7f), tracking = 0.24),
                    modifier = Modifier.padding(20.dp),
                )
                BasicText(
                    track.title,
                    style = sfText(31, FontWeight.Light, Color.White.copy(0.9f), tracking = -0.06, lineHeight = 0.95.em),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 72.dp, bottom = 20.dp),
                )
                if (focused || isCurrent) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                            .size(40.dp)
                            .background(Color.White.copy(0.9f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = if (isCurrent && isPlaying) SfIcons.Pause else SfIcons.Play
                        SfIcon(icon, Color.Black, 16.dp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BasicText(
                track.artist,
                style = sfText(16, FontWeight.Light, Color.White.copy(0.88f), tracking = -0.02),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                track.genre.ifBlank { "SoundCloud" }.uppercase(),
                style = sfText(12, color = Color.White.copy(0.35f), tracking = 0.16),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FeaturedPlaceholder(columns: Int) {
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = 0.04f,
        targetValue = 0.09f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        repeat(columns) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(0.83f)
                    .background(Color.White.copy(pulse), RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.White.copy(0.04f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = sfText(18, FontWeight.Light, Color.White.copy(0.6f)))
    }
}

@Composable
private fun CompactRow(tracks: List<Track>, playerState: PlayerState, onPlay: (List<Track>, Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = SafeArea.Horizontal - 8.dp),
    ) {
        items(tracks.size, key = { tracks[it].id }) { index ->
            CompactTrack(
                track = tracks[index],
                isCurrent = playerState.current?.id == tracks[index].id,
                onClick = { onPlay(tracks, index) },
            )
        }
    }
}

@Composable
private fun CompactTrack(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.width(275.dp), shape = RoundedCornerShape(16.dp)) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(if (focused || isCurrent) 0.055f else 0f), RoundedCornerShape(16.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, ArtworkSize.Small, Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)), compactGradient = true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    track.title,
                    style = sfText(15, FontWeight.Light, Color.White.copy(0.9f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    track.artist,
                    style = sfText(13, color = Color.White.copy(0.42f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                BasicText(
                    (if (isCurrent) "Now playing" else track.genre.ifBlank { "SoundCloud" }).uppercase(),
                    style = sfText(10, color = if (isCurrent) SfColors.Accent else Color.White.copy(0.25f), tracking = 0.17),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MoodChip(mood: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = CircleShape) { focused ->
        BasicText(
            mood,
            style = sfText(
                14,
                FontWeight.Light,
                when {
                    selected -> SfColors.OnAccent
                    focused -> Color.White
                    else -> Color.White.copy(0.55f)
                },
            ),
            modifier = Modifier
                .background(
                    when {
                        selected -> SfColors.Accent
                        focused -> Color.White.copy(0.1f)
                        else -> Color.White.copy(0.06f)
                    },
                    CircleShape,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
