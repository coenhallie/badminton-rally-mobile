package com.badmintontracker.android

import com.badmintontracker.android.localanalysis.AnalysisFiles
import com.badmintontracker.android.localanalysis.BackgroundWorkMonitor
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.shared.local.DeviceThroughputRepository
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
import kotlinx.coroutines.withContext
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
    lateinit var backgroundWork:     BackgroundWorkMonitor      private set
    lateinit var throughput:         DeviceThroughputRepository private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE))
        rally       = RallyApp(
            config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            settings = settings,
            // The video itself is a content:// handle into the gallery and is
            // not ours to delete. What IS ours is everything the on-device
            // pipeline wrote about it, and until this was passed nothing
            // deleted that.
            onLocalVideoRemoved = { entry -> AnalysisFiles.deleteAll(filesDir, entry.id) },
        )
        themePrefs  = rally.themePrefs
        localVideos = rally.localVideos
        localAnnotations = rally.localAnnotations
        // Before analyzeCoordinator, which now needs it: the cloud pose sink
        // writes through this runner's stores. Application-scoped for the same
        // reason the coordinator is - an analysis outlives the screen that
        // starts it - and the app graph's throughput, not a second one over
        // the same Settings: two instances each carry their own StateFlow and
        // would disagree about the estimate until one of them was rebuilt.
        throughput = rally.deviceThroughput
        localAnalysis = LocalAnalysisRunner(
            context = this,
            scope = appScope,
            throughput = throughput,
            log = { Log.i("LocalAnalysis", it) },
        )

        val cloudPoses = rally.cloudPoseCoordinator(log = { Log.i("CloudPose", it) })
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
            installCloudPoses = { videoId, onProgress ->
                // Off the main thread: a 30-minute stream decodes to roughly
                // 1.8M keypoint objects, which is the same magnitude an
                // on-device run already holds but is not something to do on
                // the thread drawing a frame.
                withContext(Dispatchers.Default) {
                    cloudPoses.install(videoId, onProgress) { outcome ->
                        localAnalysis.saveCloudAnalysis(videoId, outcome)
                    }.getOrThrow()
                }
            },
        )
        analyzeCoordinator.reattachToProcessing()

        // Last: it reads the two coordinators above, so it cannot be built
        // before them.
        backgroundWork = BackgroundWorkMonitor(
            entries = localVideos.entries,
            progress = analyzeCoordinator.progress,
            device = localAnalysis.state,
            scope = appScope,
        )
    }
}
