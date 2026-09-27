package com.badmintontracker.android.localanalysis

import android.content.Context
import java.io.File

/**
 * Stages the corpus video where the app actually reads video from.
 *
 * `/data/local/tmp` is where the fixture is pushed, and this app's own process
 * can read it directly - but `MediaMetadataRetriever` cannot, on at least one
 * real device (the attached arm64 emulator): it denies `mediaserver` and
 * `mediaprovider_app` read access to files staged there, by SELinux policy,
 * for both a path and an already-open file descriptor. `MediaExtractor` and
 * `MediaCodec` are unaffected - they read entirely in this app's own process -
 * which is why `VideoFrameSource.metadata()` no longer uses the retriever at
 * all. But `VideoFrameSource.sampleFramesForBackground()` still does, because
 * `getFrameAtIndex` has no extractor-based equivalent, so an instrumented
 * test that wants that retriever call to actually succeed needs the file
 * where production puts video before decoding it: app storage, copied there
 * the same way `LocalAnalysisRunner.materialise` stages a `content://` video.
 * Copied once and reused, since re-staging seventy megabytes on every test is
 * wasted time. [name] is the file pushed to `/data/local/tmp`: the default is
 * the static-camera corpus, and `CameraFollowedRunTest` stages its handheld one.
 */
internal fun stagedCorpusVideo(context: Context, name: String = "corpus-743d7fb1.mp4"): File? {
    val source = File("/data/local/tmp/$name")
    if (!source.isFile || !source.canRead()) return null
    val dest = File(context.filesDir, "corpus/$name")
    if (dest.isFile && dest.length() == source.length()) return dest
    dest.parentFile?.mkdirs()
    source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
    return dest
}
