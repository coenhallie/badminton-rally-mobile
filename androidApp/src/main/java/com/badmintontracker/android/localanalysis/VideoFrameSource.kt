package com.badmintontracker.android.localanalysis

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.badmintontracker.analysis.shuttle.backgroundSampleIndices
import java.io.File

/**
 * Decoding for the local analysis pass.
 *
 * Two passes, not one, and not three. The design's section 5.4 says "one decode
 * pass" and gives per-frame seeking as the reason, but the shuttle path cannot
 * be a single pass: production's step 1 is a median background computed from up
 * to 300 frames sampled evenly across the WHOLE video (`max_bg_samples: int =
 * 300`, inference.py:111), and that background is an input to the very first
 * inference of the main pass. It cannot be computed lazily as the main pass
 * goes.
 *
 * So: one bounded seek pre-pass of at most 300 frames, then one sequential
 * frame-by-frame pass with no seeking at all. 300 seeks is not what section 5.4
 * objects to; tens of thousands is.
 */
class VideoFrameSource(private val file: File) {

    /** What the container says about the track, read once. */
    data class Metadata(val frameCount: Int, val width: Int, val height: Int, val fps: Double)

    /**
     * Reads the track format directly from [MediaExtractor] rather than
     * [MediaMetadataRetriever].
     *
     * [MediaExtractor] is what [countSamples] and [forEachFrame] actually use
     * to read and decode this file, so what this method reports and what
     * gets decoded agree by construction - there is only one code path
     * parsing the container, not two that could quietly disagree.
     * [MediaMetadataRetriever] is a second, separate path: it can delegate
     * to an out-of-process service (`mediaserver` / `mediaprovider_app`) to
     * open the file rather than reading it in the calling process, and on
     * the arm64 emulator that service is denied read access (by SELinux) to
     * files staged under /data/local/tmp. That is not a frame-count-only
     * gap: every key the retriever reports for such a file comes back null,
     * width and height included, regardless of whether it is given a path
     * or an already-open file descriptor. The extractor has no such
     * dependency: it is this app's own process reading a file its own
     * process opened.
     */
    fun metadata(): Metadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = videoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                format.getInteger(MediaFormat.KEY_WIDTH)
            } else {
                error("no width in ${file.name}")
            }
            val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                format.getInteger(MediaFormat.KEY_HEIGHT)
            } else {
                error("no height in ${file.name}")
            }
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000.0
            } else {
                null
            }
            extractor.selectTrack(track)
            val frames = countSamplesOn(extractor)
            return Metadata(
                frameCount = frames,
                width = width,
                height = height,
                // Frames over duration rather than CAPTURE_FRAMERATE, which is
                // absent on most files and reports the recording rate rather than
                // the playback rate when present.
                fps = if (durationMs != null && durationMs > 0) frames * 1000.0 / durationMs else 0.0,
            )
        } finally {
            extractor.release()
        }
    }

    /** Total frames the container reports. */
    fun frameCount(): Int = metadata().frameCount

    /**
     * The number of samples on the video track, by walking the container.
     *
     * One sample is one encoded frame for every codec this app decodes, so
     * this is the frame count. It decodes nothing.
     */
    fun countSamples(): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(videoTrack(extractor))
            return countSamplesOn(extractor)
        } finally {
            extractor.release()
        }
    }

    /** Walks an already-selected track to the end, counting samples. Decodes nothing. */
    private fun countSamplesOn(extractor: MediaExtractor): Int {
        var count = 0
        // sampleTrackIndex is -1 once there is no current sample - the
        // container's own end-of-stream signal, read explicitly rather than
        // inferring it from sampleTime also being -1 there.
        while (extractor.sampleTrackIndex >= 0) {
            count++
            if (!extractor.advance()) break
        }
        return count
    }

    /** The index of the first video track, or a clear error naming the file. */
    private fun videoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("no video track in ${file.name}")

    /**
     * Decode the sampled frames for the median background, one at a time.
     *
     * Frame-indexed rather than time-indexed on purpose. Production seeks by
     * frame (`cap.set(CAP_PROP_POS_FRAMES, idx)`), and on a
     * variable-frame-rate source an index converted to a timestamp lands on a
     * different frame.
     *
     * `getFrameAtIndex` needs API 28. This app's minSdk is 26, so API 26 and 27
     * have no implementation here yet - a real gap to close before release,
     * not something to paper over with a time-based approximation that would
     * silently sample different frames.
     *
     * Streamed through [transform] rather than collected into a `List<Bitmap>`
     * first: 300 decoded 1920x1080 bitmaps held at once is about 2.5 GB, which
     * kills the process on a 2 GB device - and would on a 4 GB one too - well
     * before anything gets to resize them down to what the model actually
     * needs. This decodes one frame, hands it to [transform], recycles it,
     * and only then decodes the next, so peak memory is one source-resolution
     * bitmap plus whatever [transform] keeps, which in practice is a much
     * smaller resized frame. Same indices, same frames, same output per
     * frame as before; only what is held in memory at once changes.
     *
     * The frame count driving [backgroundSampleIndices] comes from
     * [metadata], not from asking the retriever directly: the retriever's own
     * `METADATA_KEY_VIDEO_FRAME_COUNT` is exactly the field [metadata] no
     * longer trusts.
     */
    fun <T> sampleFramesForBackground(maxSamples: Int = 300, transform: (Bitmap) -> T): List<T> {
        check(android.os.Build.VERSION.SDK_INT >= 28) {
            "frame-indexed sampling needs API 28; API 26-27 has no implementation yet"
        }
        val total = metadata().frameCount
        return MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            backgroundSampleIndices(total, maxSamples).mapNotNull { index ->
                r.getFrameAtIndex(index)?.let { bitmap ->
                    try {
                        transform(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    /**
     * One sequential pass over every frame, no seeking.
     *
     * [body] receives the frame index, the buffer's presentation timestamp in
     * seconds, and the decoded image. The timestamp comes from the container
     * rather than `frame / fps`, per section 5.2: on a variable-frame-rate
     * source those disagree, and the cloud's own timestamps are container
     * timestamps too.
     *
     * The image is only valid for the duration of the call.
     *
     * [maxFrames] stops the pass early. Present for bounded measurement on a
     * device, not for production use: a partial track would silently produce
     * rallies for part of a match.
     */
    fun forEachFrame(maxFrames: Int = Int.MAX_VALUE, body: (Int, Double, android.media.Image) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = videoTrack(extractor)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        // Flexible YUV rather than a Surface: this pass feeds tensors, not a
        // display, and a ByteBuffer output keeps the pixels reachable without
        // a GPU round trip.
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        codec.configure(format, null, null, 0)
        codec.start()

        var frameIndex = 0
        var sawInputEos = false
        var sawOutputEos = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        codec.getOutputImage(outIndex)?.let { image ->
                            body(frameIndex, info.presentationTimeUs / 1_000_000.0, image)
                            frameIndex++
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    if (frameIndex >= maxFrames) sawOutputEos = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }
}
