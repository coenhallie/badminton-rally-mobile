package com.badmintontracker.android.localanalysis

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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

    /** Total frames the container reports. */
    fun frameCount(): Int = MediaMetadataRetriever().use { r ->
        r.setDataSource(file.path)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt()
            ?: error("no frame count in ${file.name}")
    }

    /**
     * The frame indices production samples for its median background.
     *
     * `np.linspace(0, total - 1, min(total, max), dtype=int)` then `np.unique`
     * (inference.py:200-201). `dtype=int` on linspace TRUNCATES rather than
     * rounds, so this truncates: rounding would shift which frames form the
     * background, which changes the background, which changes every heatmap.
     */
    fun backgroundSampleIndices(totalFrames: Int, maxSamples: Int = 300): List<Int> {
        val count = minOf(totalFrames, maxSamples)
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(0)
        val step = (totalFrames - 1).toDouble() / (count - 1).toDouble()
        return (0 until count).map { (it * step).toInt() }.distinct()
    }

    /**
     * Decode the sampled frames for the median background.
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
     */
    fun sampleFramesForBackground(maxSamples: Int = 300): List<Bitmap> {
        check(android.os.Build.VERSION.SDK_INT >= 28) {
            "frame-indexed sampling needs API 28; API 26-27 has no implementation yet"
        }
        return MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            val total = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toInt() ?: error("no frame count")
            backgroundSampleIndices(total, maxSamples).mapNotNull { r.getFrameAtIndex(it) }
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
        val track = (0 until extractor.trackCount).first { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
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
