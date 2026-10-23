package app.soundfire.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundfire.data.ArtworkSize
import app.soundfire.data.Track
import app.soundfire.ui.theme.SfColors
import app.soundfire.ui.theme.sfText

/** Floating now-playing pill from the home design; its thumbnail opens the playing-now screen. */
@Composable
fun NowPlayingPill(
    track: Track,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .width(360.dp)
            .shadow(32.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .background(SfColors.Pill.copy(0.92f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(0.09f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The thumbnail opens the playing-now screen.
        FocusBox(onClick = onOpen, shape = RoundedCornerShape(12.dp), ringOffset = 2.dp) {
            Artwork(track, ArtworkSize.Small, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), compactGradient = true)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText("NOW PLAYING", style = sfText(10, FontWeight.Medium, SfColors.Accent, tracking = 0.22))
            Spacer(Modifier.height(4.dp))
            BasicText(track.title, style = sfText(15, FontWeight.Light, Color.White.copy(0.9f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(track.artist, style = sfText(12, color = Color.White.copy(0.42f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        FocusBox(
            onClick = onTogglePlay,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            ringOffset = 2.dp,
            focusedScale = 1.05f,
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(Color.White, CircleShape))
            SfIcon(if (isPlaying) SfIcons.Pause else SfIcons.Play, Color.Black, 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        SfIcon(SfIcons.Volume, Color.White.copy(0.25f), 16.dp, Modifier.padding(end = 4.dp))
    }
}
