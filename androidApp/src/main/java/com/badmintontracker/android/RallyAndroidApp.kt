package com.badmintontracker.android

import android.app.Application
import android.net.Uri
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.badmintontracker.android.data.ThemePreferenceRepository
import com.badmintontracker.android.localvideo.AnalyzeCoordinator
import com.badmintontracker.android.localvideo.LocalAnnotationsRepository
import com.badmintontracker.android.localvideo.LocalVideoRepository
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.SupabaseConfig
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RallyAndroidApp : Application(), SingletonImageLoader.Factory {

    /** App-wide Coil loader that can also decode video frames (local thumbnails). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

    lateinit var rally:              RallyApp                   private set
    lateinit var themePrefs:         ThemePreferenceRepository  private set
    lateinit var localVideos:        LocalVideoRepository       private set
    lateinit var localAnnotations:   LocalAnnotationsRepository private set
    lateinit var analyzeCoordinator: AnalyzeCoordinator         private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE))
        rally       = RallyApp(SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), settings)
        themePrefs  = ThemePreferenceRepository(settings)
        localVideos = LocalVideoRepository(settings)
        localAnnotations = LocalAnnotationsRepository(settings)
        analyzeCoordinator = AnalyzeCoordinator(
            localVideos = localVideos,
            videos = rally.videos,
            clips = rally.clips,
            scope = appScope,
            openChannel = { uri, offset ->
                // Throwing here surfaces as FAILED(UPLOAD) with this message — the
                // "file missing / permission revoked" state.
                val stream = runCatching { contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()
                    ?: error("Video file is missing or access was revoked")
                stream.skip(offset)
                stream.toByteReadChannel()
            },
            log = { Log.i("AnalyzeCoordinator", it) },
            localAnnotations = localAnnotations,
        )
        analyzeCoordinator.reattachToProcessing()
    }
}
