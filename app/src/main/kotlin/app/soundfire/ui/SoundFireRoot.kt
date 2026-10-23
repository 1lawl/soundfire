package app.soundfire.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.soundfire.AppContainer
import app.soundfire.data.soundcloud.Page
import app.soundfire.ui.home.HomeScreen
import app.soundfire.ui.home.HomeViewModel
import app.soundfire.ui.nowplaying.NowPlayingScreen
import app.soundfire.ui.search.KeyboardMode
import app.soundfire.ui.search.KeyboardOverlay
import app.soundfire.ui.search.SearchViewModel
import app.soundfire.ui.theme.sfText
import app.soundfire.ui.tracks.TrackListScreen
import app.soundfire.ui.tracks.TrackListViewModel
import app.soundfire.ui.components.formatCount
import kotlinx.coroutines.delay

private sealed interface Screen {
    data object Home : Screen
    data object NowPlaying : Screen
    data class SearchResults(val query: String) : Screen
    data class Likes(val username: String) : Screen
}

private const val SEEK_STEP_MS = 10_000L

@Composable
fun SoundFireRoot(container: AppContainer) {
    val player = container.player
    val playerState by player.state.collectAsStateWithLifecycle()
    val homeViewModel = viewModel { HomeViewModel(container.api, container.taste, container.prefs) }
    val searchViewModel = viewModel { SearchViewModel(container.api) }
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()

    // Back stack: Back pops one screen; Home is always at the bottom.
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val screen = backStack.last()
    val navigate: (Screen) -> Unit = { target ->
        backStack.remove(target)
        backStack.add(target)
    }
    val goHome: () -> Unit = { backStack.removeAll { it != Screen.Home } }
    var keyboard by remember { mutableStateOf<KeyboardMode?>(null) }
    val searchButtonFocus = remember { FocusRequester() }
    // Live snapshot source for the keyboard's frosted glass.
    val backdrop = rememberGraphicsLayer()

    val view = LocalView.current
    DisposableEffect(playerState.isPlaying) {
        view.keepScreenOn = playerState.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = keyboard != null) { keyboard = null }
    BackHandler(enabled = keyboard == null && backStack.size > 1) { backStack.removeAt(backStack.lastIndex) }

    var keyboardWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(keyboard) {
        if (keyboard == null && keyboardWasOpen) runCatching { searchButtonFocus.requestFocus() }
        keyboardWasOpen = keyboard != null
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> player.togglePlayPause()
                    Key.MediaFastForward -> player.seekBy(SEEK_STEP_MS)
                    Key.MediaRewind -> player.seekBy(-SEEK_STEP_MS)
                    // The remote's ☰ button jumps to Playing Now from anywhere.
                    Key.Menu -> if (keyboard == null && playerState.current != null) navigate(Screen.NowPlaying) else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    backdrop.record { this@drawWithContent.drawContent() }
                    drawLayer(backdrop)
                },
        ) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    viewModel = homeViewModel,
                    playerState = playerState,
                    searchFocus = searchButtonFocus,
                    onPlay = player::play,
                    onTogglePlay = player::togglePlayPause,
                    onOpenNowPlaying = { navigate(Screen.NowPlaying) },
                    onOpenSearch = { keyboard = KeyboardMode.Search },
                    onOpenUsername = { keyboard = KeyboardMode.Username },
                    onOpenLikes = { username -> navigate(Screen.Likes(username)) },
                )
                Screen.NowPlaying -> NowPlayingScreen(
                    state = playerState,
                    searchFocus = searchButtonFocus,
                    onTogglePlay = player::togglePlayPause,
                    onSeek = player::seekBy,
                    onStop = {
                        player.stop()
                        goHome()
                    },
                    onHome = goHome,
                    onOpenSearch = { keyboard = KeyboardMode.Search },
                )
                is Screen.SearchResults -> TrackListScreen(
                    eyebrow = "Search results",
                    title = "“${screen.query}”",
                    countLabel = { count, exact -> "${formatCount(count)}${if (exact) "" else "+"} tracks" },
                    viewModel = viewModel(key = "search:${screen.query}") {
                        TrackListViewModel { next -> container.api.searchTracksPage(screen.query, next) }
                    },
                    playerState = playerState,
                    onPlay = player::play,
                    onTogglePlay = player::togglePlayPause,
                    onOpenNowPlaying = { navigate(Screen.NowPlaying) },
                    secondaryAction = "New search" to { keyboard = KeyboardMode.Search },
                )
                is Screen.Likes -> TrackListScreen(
                    knownTotal = homeState.likeCount.takeIf { homeState.username.equals(screen.username, ignoreCase = true) && it > 0 },
                    eyebrow = "Liked tracks",
                    title = "@${screen.username}",
                    countLabel = { count, exact -> "${formatCount(count)}${if (exact) "" else "+"} liked tracks" },
                    viewModel = viewModel(key = "likes:${screen.username}") {
                        var userId: Long? = null
                        TrackListViewModel { next ->
                            val id = userId ?: container.api.resolveUser(screen.username).id.also { userId = it }
                            container.api.likesPage(id, next).let { page -> Page(page.items.map { it.first }, page.next) }
                        }
                    },
                    playerState = playerState,
                    onPlay = player::play,
                    onTogglePlay = player::togglePlayPause,
                    onOpenNowPlaying = { navigate(Screen.NowPlaying) },
                )
            }
        }

        playerState.error?.let { message ->
            LaunchedEffect(message) {
                delay(4_000)
                player.clearError()
            }
            BasicText(
                message,
                style = sfText(16, color = Color.White.copy(0.92f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(Color(0xE6181818), CircleShape)
                    .border(1.dp, Color.White.copy(0.1f), CircleShape)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        keyboard?.let { mode ->
            KeyboardOverlay(
                mode = mode,
                backdrop = backdrop,
                searchViewModel = searchViewModel,
                taste = container.taste,
                currentTrackId = playerState.current?.id,
                isPlaying = playerState.isPlaying,
                onPlay = { tracks, index ->
                    player.play(tracks, index)
                    keyboard = null
                },
                onImported = homeViewModel::reload,
                onSubmitSearch = { query ->
                    keyboard = null
                    navigate(Screen.SearchResults(query))
                },
                onClose = { keyboard = null },
            )
        }
    }
}
