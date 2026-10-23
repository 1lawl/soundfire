package app.soundfire

import android.content.Context
import androidx.room.Room
import app.soundfire.data.Prefs
import app.soundfire.data.local.SoundFireDatabase
import app.soundfire.data.soundcloud.ClientIdProvider
import app.soundfire.data.soundcloud.SoundCloudApi
import app.soundfire.player.PlayerController
import app.soundfire.taste.TasteEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val prefs = Prefs(context)
    val api = SoundCloudApi(http, ClientIdProvider(http, prefs))

    private val database = Room.databaseBuilder(context, SoundFireDatabase::class.java, "soundfire.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    val taste = TasteEngine(api, database.taste(), prefs)
    val player = PlayerController(context, api, taste, http, scope)

    private companion object {
        // Must look like desktop Chrome: any "Android" UA is redirected to m.soundcloud.com,
        // which doesn't ship the a-v2 bundles the client_id is scraped from.
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    }
}
