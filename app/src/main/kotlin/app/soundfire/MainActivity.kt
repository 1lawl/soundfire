package app.soundfire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.soundfire.ui.SoundFireRoot
import app.soundfire.ui.theme.SoundFireTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SoundFireApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoundFireTheme {
                SoundFireRoot(container)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Like the original app: leaving SoundFire (Home button, screensaver) pauses playback.
        container.player.pause()
    }
}
