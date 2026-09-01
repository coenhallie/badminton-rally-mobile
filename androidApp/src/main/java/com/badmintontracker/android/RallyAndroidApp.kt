package com.badmintontracker.android

import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import android.app.Application
import android.net.Uri
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.badmintontracker.android.localvideo.skipExactly
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.SupabaseConfig
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
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
    lateinit var localAnalysis:      LocalAnalysisRunner        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE))
        rally       = RallyApp(SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), settings)
        themePrefs  = rally.themePrefs
        localVideos = rally.localVideos
        localAnnotations = rally.localAnnotations
        analyzeCoordinator = rally.analyzeCoordinator(
            scope = appScope,
            openChannel = { uri, offset ->
                // Throwing here surfaces as FAILED(UPLOAD) with this message - the
                // "file missing / permission revoked" state.
                val stream = runCatching { contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()
                    ?: error("Video file is missing or access was revoked")
                stream.skipExactly(offset)
                stream.toByteReadChannel()
            },
            log = { Log.i("AnalyzeCoordinator", it) },
        )
        analyzeCoordinator.reattachToProcessing()

        // The on-device sibling of analyzeCoordinator. Application-scoped
        // for the same reason: an analysis outlives the screen that starts it.
        localAnalysis = LocalAnalysisRunner(
            context = this,
            scope = appScope,
            log = { Log.i("LocalAnalysis", it) },
        )
    }
}
