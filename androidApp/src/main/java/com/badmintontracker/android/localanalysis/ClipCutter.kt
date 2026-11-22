package com.badmintontracker.android.localanalysis

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.badmintontracker.analysis.rally.ClipWindow
import java.io.File
import kotlin.math.roundToInt

/**
 * Cuts rally clips out of a source video, re-encoding.
 *
 * Section 5.4: "Frame-accurate boundaries need a re-encode, not a stream copy,
 * for the same reason the cloud re-encodes." A stream copy can only start on a
 * keyframe, and a rally boundary almost never is one, so a copied clip either
 * starts seconds early or opens on a corrupt frame.
 *
 * Decoder output is rendered straight onto the encoder's input surface, so no
 * pixel ever crosses into Java. Frames outside the window are released without
 * rendering, which is what makes the boundary exact rather than keyframe
 * aligned, and each rendered frame's timestamp is rebased to the clip's own
 * zero.
 *
 * One decode pass per clip, each seeking to the sync sample before its start
 * rather than decoding the file from the beginning. Clips are cut
 * independently because [ClipWindow]s can overlap - `refineRallies` produces
 * overlapping rallies and padding preserves them - so a single pass would need
 * two encoders live at once for the same frame.
 *
 * **Video only.** The source's audio track is not carried across. Clips are
 * silent, which is a real gap for reviewing a rally and is not yet addressed.
 */
class ClipCutter {

    data class Clip(val index: Int, val file: File, val startSeconds: Double, val endSeconds: Double)

    fun cut(source: File, windows: List<ClipWindow>, into: File): List<Clip> {
        require(source.isFile) { "no such video: ${source.path}" }
        into.mkdirs()
        return windows.mapIndexed { i, w ->
            // rally_index is 1-based throughout this project, and the clip
            // filenames follow it so a file can be matched to a row by eye.
            val out = File(into, "rally-${i + 1}.mp4")
            cutOne(source, w.clipStart, w.clipEnd, out)
            Clip(i + 1, out, w.clipStart, w.clipEnd)
        }
    }

    private fun cutOne(source: File, startSeconds: Double, endSeconds: Double, out: File) {
        val startUs = (startSeconds * 1_000_000).toLong()
        val endUs = (endSeconds * 1_000_000).toLong()

        var extractor: MediaExtractor? = null
        var encoder: MediaCodec? = null
        var surface: Surface? = null
        var decoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var encoderStarted = false
        var decoderStarted = false
        var muxerStarted = false
        var rendered = 0
        var succeeded = false

        try {
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(source.path)
            val track = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                // Generous rather than tuned: these clips are the reviewing
                // surface for a coach looking at a shuttle a few pixels across,
                // and re-encoding artifacts there defeat the point of the app.
                // Capped at 16 Mbps so 4K inputs do not exceed hardware encoder limits.
                val bitrate = (width.toLong() * height * 8L).coerceIn(4_000_000L, 16_000_000L).toInt()
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // Every second. A clip is seconds long and gets scrubbed, so
                // sparse keyframes make seeking within it unpleasant.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder = it }
            enc.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = enc.createInputSurface().also { surface = it }
            enc.start()
            encoderStarted = true

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).also { decoder = it }
            dec.configure(inFormat, surf, null, 0)
            dec.start()
            decoderStarted = true

            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            var muxTrack = -1

            // Seek to the sync sample at or before the start, then decode forward
            // and discard until the window opens. Seeking to the nearest sync
            // AFTER the start would silently drop the opening of the rally.
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawDecodeEos = false
            var sawEncodeEos = false

            while (!sawEncodeEos) {
                if (!sawInputEos) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                if (!sawDecodeEos) {
                    val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        val pts = info.presentationTimeUs
                        val inWindow = pts in startUs..endUs
                        if (inWindow && info.size > 0) {
                            // Rebase to the clip's own zero, in nanoseconds.
                            dec.releaseOutputBuffer(outIndex, (pts - startUs) * 1000)
                            rendered++
                        } else {
                            dec.releaseOutputBuffer(outIndex, false)
                        }
                        val past = pts > endUs
                        if (past || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawDecodeEos = true
                            enc.signalEndOfInputStream()
                        }
                    }
                }

                var encIndex = enc.dequeueOutputBuffer(info, 10_000)
                while (encIndex >= 0 || encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    } else {
                        val buf = enc.getOutputBuffer(encIndex)!!
                        // The codec-config buffer is metadata, already carried
                        // by addTrack; muxing it as a sample corrupts the file.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mux.writeSampleData(muxTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(encIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEncodeEos = true
                            break
                        }
                    }
                    encIndex = enc.dequeueOutputBuffer(info, 0)
                }
            }
            succeeded = rendered > 0
        } finally {
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (encoderStarted) runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            val muxStopFailed = if (muxerStarted) runCatching { muxer?.stop() }.isFailure else false
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (!succeeded || muxStopFailed) runCatching { out.delete() }
        }

        check(rendered > 0) { emptyWindowMessage(startSeconds, endSeconds, source.name) }
    }

    /** Expected frame count for a window, for tests to check against. */
    fun expectedFrames(startSeconds: Double, endSeconds: Double, fps: Double): Int =
        ((endSeconds - startSeconds) * fps).roundToInt()
}

/** Why a cut produced nothing. Shown to the coach verbatim, so it names the file. */
internal fun emptyWindowMessage(startSeconds: Double, endSeconds: Double, fileName: String): String =
    "no frames fell inside %.2f..%.2fs of %s".format(java.util.Locale.ROOT, startSeconds, endSeconds, fileName)
