package app.soundfire.ui.search

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.soundfire.data.ArtworkSize
import app.soundfire.data.Track
import app.soundfire.data.soundcloud.HttpStatusException
import app.soundfire.taste.NoPublicLikesException
import app.soundfire.taste.TasteEngine
import app.soundfire.ui.components.Artwork
import app.soundfire.ui.components.Blur
import app.soundfire.ui.components.FocusBox
import app.soundfire.ui.components.SfIcon
import app.soundfire.ui.components.SfIcons
import app.soundfire.ui.components.compact
import app.soundfire.ui.components.formatCount
import app.soundfire.ui.theme.SafeArea
import app.soundfire.ui.theme.SfColors
import app.soundfire.ui.theme.sfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class KeyboardMode { Search, Username }

private sealed interface ImportStatus {
    data object Idle : ImportStatus
    data class Running(val count: Int) : ImportStatus
    data class Done(val count: Int) : ImportStatus
    data class Failed(val message: String) : ImportStatus
}

private val PanelShape = RoundedCornerShape(34.dp)
private val KeyShape = RoundedCornerShape(16.dp)
private const val ALPHA_KEYS = "abcdefghijklmnopqrstuvwxyz-'"
private const val NUMERIC_KEYS = "1234567890&#()@!?:._\""

/**
 * Floating frosted-glass keyboard. It hugs the bottom of the screen and leaves the top half of
 * the current screen visible. Fire OS 8 can't blur live content, so the glass is a blurred
 * snapshot of [backdrop] taken when the panel opens, drawn behind a dark tint for legibility.
 */
@Composable
fun KeyboardOverlay(
    mode: KeyboardMode,
    backdrop: GraphicsLayer,
    searchViewModel: SearchViewModel,
    taste: TasteEngine,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    onImported: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClose: () -> Unit,
) {
    val search by searchViewModel.state.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf<ImportStatus>(ImportStatus.Idle) }
    var numeric by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val query = if (mode == KeyboardMode.Search) search.query else username
    // Edits transform the latest value, never the one captured at the last recomposition:
    // keys from a paired keyboard can arrive faster than frames.
    val editQuery: ((String) -> String) -> Unit = { transform ->
        if (mode == KeyboardMode.Search) searchViewModel.editQuery(transform) else username = transform(username).lowercase()
    }
    val setQuery: (String) -> Unit = { value -> editQuery { value } }
    val startImport: () -> Unit = {
        if (username.isNotBlank() && importStatus !is ImportStatus.Running) {
            importStatus = ImportStatus.Running(0)
            scope.launch {
                importStatus = runCatching { taste.importLikes(username) { count -> importStatus = ImportStatus.Running(count) } }.fold(
                    onSuccess = { count ->
                        onImported()
                        ImportStatus.Done(count)
                    },
                    onFailure = { e ->
                        Log.w("KeyboardOverlay", "Likes import failed", e)
                        ImportStatus.Failed(
                            when {
                                e is NoPublicLikesException -> "@$username has no public likes to import."
                                e is HttpStatusException && e.code == 404 -> "No SoundCloud user called “$username”."
                                else -> "Import failed. Check the username and connection."
                            },
                        )
                    },
                )
            }
        }
    }
    val onKey: (String) -> Unit = { key ->
        when (key) {
            KEY_BACK -> editQuery { it.dropLast(1) }
            KEY_CLEAR -> setQuery("")
            KEY_MODE -> numeric = !numeric
            KEY_IMPORT -> startImport()
            KEY_SEARCH -> searchViewModel.state.value.query.trim().takeIf { it.isNotEmpty() }?.let(onSubmitSearch)
            else -> editQuery { it + key }
        }
    }

    // Frosted glass source: blur a downscaled snapshot of whatever screen is underneath.
    var glass by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val shot = backdrop.toImageBitmap()
        glass = withContext(Dispatchers.Default) {
            Blur.blur(shot.asAndroidBitmap(), downscale = 8, radius = 5).asImageBitmap()
        }
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }

    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { } // keys must be laid out before they can take focus
        runCatching { firstKey.requestFocus() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Trap D-pad focus inside the panel; Back or CLOSE dismisses it.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                // A paired Bluetooth/USB keyboard types straight into the field.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Backspace -> onKey(KEY_BACK)
                    event.key == Key.Escape -> onClose()
                    event.key == Key.Enter -> onKey(if (mode == KeyboardMode.Username) KEY_IMPORT else KEY_SEARCH)
                    event.utf16CodePoint in 32..126 && event.key != Key.Enter && event.key != Key.DirectionCenter ->
                        onKey(event.utf16CodePoint.toChar().lowercase())
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
        // Scrim only darkens the bottom, where the panel sits; the top of the screen stays readable.
        Box(
            Modifier
                .fillMaxSize()
                .alpha(appear.value)
                .background(Brush.verticalGradient(0f to Color.Black.copy(0.05f), 0.45f to Color.Black.copy(0.25f), 1f to Color.Black.copy(0.6f))),
        )

        var panelOrigin by remember { mutableStateOf(Offset.Zero) }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = SafeArea.Horizontal, end = SafeArea.Horizontal, bottom = SafeArea.Vertical)
                .widthIn(max = 1120.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 48.dp.toPx()
                }
                .onGloballyPositioned { panelOrigin = it.positionInRoot() }
                .shadow(48.dp, PanelShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(PanelShape)
                .drawBehind {
                    glass?.let { image ->
                        drawImage(
                            image,
                            dstOffset = IntOffset(-panelOrigin.x.roundToInt(), -panelOrigin.y.roundToInt()),
                            dstSize = screen,
                            filterQuality = FilterQuality.Low,
                        )
                    }
                    // Tint for contrast, plus the faint top sheen of Apple's materials.
                    drawRect(Color(0xFF141416).copy(alpha = if (glass == null) 0.9f else 0.62f))
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(0.09f), Color.Transparent), endY = size.height * 0.3f))
                }
                .border(1.dp, Color.White.copy(0.12f), PanelShape)
                .padding(24.dp),
        ) {
            QueryField(mode, query, loading = search.loading && mode == KeyboardMode.Search, onClose = onClose)
            Spacer(Modifier.height(16.dp))
            if (mode == KeyboardMode.Search) {
                SearchResults(search, currentTrackId, isPlaying, onSuggestion = setQuery, onPlay = onPlay)
            } else {
                ImportInfo(importStatus)
            }
            Spacer(Modifier.height(16.dp))
            KeyGrid(
                keys = if (numeric) NUMERIC_KEYS else ALPHA_KEYS,
                numeric = numeric,
                usernameMode = mode == KeyboardMode.Username,
                firstKey = firstKey,
                onKey = onKey,
            )
        }
    }
}

@Composable
private fun QueryField(mode: KeyboardMode, query: String, loading: Boolean, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.07f), CircleShape)
            .border(1.dp, Color.White.copy(0.1f), CircleShape)
            .padding(start = 22.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SfIcon(if (mode == KeyboardMode.Search) SfIcons.Search else SfIcons.User, Color.White.copy(0.5f), 22.dp)
        Spacer(Modifier.width(14.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (query.isEmpty()) {
                BasicText(
                    if (mode == KeyboardMode.Search) "Search SoundFire" else "Your SoundCloud username",
                    style = sfText(28, FontWeight.Light, Color.White.copy(0.38f), tracking = -0.04),
                )
            } else {
                BasicText(
                    query,
                    style = sfText(28, FontWeight.Light, Color.White.copy(0.95f), tracking = -0.04),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Caret()
        }
        if (loading) Spinner()
        Spacer(Modifier.width(12.dp))
        FocusBox(onClick = onClose, shape = CircleShape) { focused ->
            BasicText(
                "CLOSE",
                style = sfText(13, FontWeight.Medium, if (focused) Color.Black else Color.White.copy(0.55f), tracking = 0.18),
                modifier = Modifier
                    .background(if (focused) Color.White.copy(0.92f) else Color.Transparent, CircleShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onSuggestion: (String) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.suggestions.isEmpty()) {
                Hint(if (state.query.isBlank()) "Type with the remote, or a paired keyboard." else "No suggestions")
            }
            state.suggestions.take(3).forEach { suggestion ->
                FocusBox(onClick = { onSuggestion(suggestion) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { focused ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(if (focused) 0.14f else 0f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SfIcon(SfIcons.Search, SfColors.PlayingAccent.copy(0.75f), 15.dp)
                        Spacer(Modifier.width(10.dp))
                        BasicText(
                            suggestion,
                            style = sfText(16, color = Color.White.copy(if (focused) 1f else 0.72f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                state.error != null -> Hint(state.error)
                state.results.isEmpty() && state.query.isNotBlank() && !state.loading -> Hint("No matching tracks.")
                state.results.isEmpty() -> Hint("Results appear here as you type.")
                // Padding leaves room for the focused card's lift so it isn't clipped by the row.
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    itemsIndexed(state.results, key = { _, t -> t.id }) { index, track ->
                        ResultCard(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying,
                            onClick = { onPlay(state.results, index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(track: Track, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = Modifier.width(300.dp).alpha(if (track.playable) 1f else 0.45f),
        enabled = track.playable,
        shape = RoundedCornerShape(18.dp),
        ringColor = Color.Transparent,
        focusedScale = 1.04f,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(if (focused) Color.White.copy(0.92f) else Color.White.copy(0.06f), RoundedCornerShape(18.dp))
                .border(1.dp, if (isCurrent) SfColors.PlayingAccent.copy(0.6f) else Color.White.copy(0.08f), RoundedCornerShape(18.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, ArtworkSize.Small, Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)), compactGradient = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val ink = if (focused) Color(0xFF0A0A0A) else Color.White
                BasicText(
                    when {
                        !track.playable -> "UNAVAILABLE"
                        isCurrent -> if (isPlaying) "PLAYING" else "PAUSED"
                        else -> track.genre.ifBlank { "Track" }.uppercase()
                    },
                    style = sfText(11, FontWeight.Medium, if (isCurrent) SfColors.PlayingAccent else ink.copy(0.45f), tracking = 0.16),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    track.title,
                    style = sfText(17, FontWeight.Medium, ink.copy(0.92f), tracking = -0.02),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    "${track.artist} · ${compact(track.plays)} plays",
                    style = sfText(13, color = ink.copy(0.55f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImportInfo(status: ImportStatus) {
    Box(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Column {
            BasicText(
                when (status) {
                    ImportStatus.Idle -> "Import your public SoundCloud likes"
                    is ImportStatus.Running -> if (status.count == 0) "Importing your likes…" else "Importing your likes… ${formatCount(status.count)}"
                    is ImportStatus.Done -> "Imported ${formatCount(status.count)} likes"
                    is ImportStatus.Failed -> status.message
                },
                style = sfText(22, FontWeight.Light, if (status is ImportStatus.Failed) SfColors.PlayingAccent else Color.White.copy(0.92f)),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                when (status) {
                    is ImportStatus.Done -> "Your home screen is now tuned to them. Press Back to see it."
                    else -> "They seed SoundFire's recommendations once; after that your taste is learned on this TV. " +
                        "Only public profile data is read; no password needed."
                },
                style = sfText(15, color = Color.White.copy(0.55f)),
            )
        }
    }
}

@Composable
private fun KeyGrid(keys: String, numeric: Boolean, usernameMode: Boolean, firstKey: FocusRequester, onKey: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(10).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, char ->
                    KeyCap(
                        label = if (numeric) "$char" else char.uppercase(),
                        modifier = if (row == 0 && col == 0) Modifier.focusRequester(firstKey) else Modifier,
                        onClick = { onKey("$char") },
                    )
                }
                // Keep the grid's columns aligned on a short last row.
                repeat(10 - chunk.length) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyCap(if (numeric) "ABC" else "&123", span = 2f, secondary = true, onClick = { onKey(KEY_MODE) })
            KeyCap("SPACE", span = 3f, secondary = true, onClick = { onKey(" ") })
            KeyCap("DELETE", span = 1f, secondary = true, icon = SfIcons.Backspace, onClick = { onKey(KEY_BACK) })
            KeyCap("CLEAR", span = 2f, secondary = true, onClick = { onKey(KEY_CLEAR) })
            if (usernameMode) {
                KeyCap("IMPORT", span = 2f, primary = true, onClick = { onKey(KEY_IMPORT) })
            } else {
                KeyCap("SEARCH", span = 2f, primary = true, onClick = { onKey(KEY_SEARCH) })
            }
        }
    }
}

/** tvOS-style key: frosted at rest, lifts to an opaque white tile with dark text when focused. */
@Composable
private fun RowScope.KeyCap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    span: Float = 1f,
    secondary: Boolean = false,
    primary: Boolean = false,
    icon: ImageVector? = null,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.weight(span).height(54.dp),
        shape = KeyShape,
        ringColor = Color.Transparent,
        focusedScale = 1.1f,
        contentAlignment = Alignment.Center,
    ) { focused ->
        val background = when {
            focused -> Color.White.copy(0.95f)
            primary -> SfColors.PlayingAccent.copy(0.9f)
            secondary -> Color.White.copy(0.05f)
            else -> Color.White.copy(0.085f)
        }
        val ink = when {
            focused -> Color(0xFF0A0A0A)
            primary -> Color.Black
            secondary -> Color.White.copy(0.6f)
            else -> Color.White.copy(0.85f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (focused) Modifier.shadow(16.dp, KeyShape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
                .background(background, KeyShape),
        )
        if (icon != null) {
            SfIcon(icon, ink, 22.dp)
        } else {
            BasicText(
                label,
                style = if (secondary || primary) sfText(13, FontWeight.Medium, ink, tracking = 0.15) else sfText(20, FontWeight.Medium, ink),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = sfText(15, color = Color.White.copy(0.45f)), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
private fun Caret() {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(550, easing = { if (it < 0.5f) 0f else 1f }), RepeatMode.Reverse),
        label = "blink",
    )
    Box(
        Modifier
            .padding(start = 3.dp)
            .width(2.dp)
            .height(28.dp)
            .alpha(blink)
            .background(SfColors.PlayingAccent, CircleShape),
    )
}

@Composable
private fun Spinner() {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = { it })),
        label = "rotation",
    )
    Box(
        Modifier
            .size(20.dp)
            .graphicsLayer { rotationZ = rotation }
            .border(2.dp, Brush.sweepGradient(listOf(Color.Transparent, SfColors.PlayingAccent)), CircleShape),
    )
}

private const val KEY_BACK = "\u0008"
private const val KEY_CLEAR = "\u0018"
private const val KEY_MODE = "\u000E"
private const val KEY_IMPORT = "\u000D"
private const val KEY_SEARCH = "\u000A"
