package com.badmintontracker.android.localanalysis

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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

        val extractor = MediaExtractor()
        extractor.setDataSource(source.path)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
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
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 8).coerceAtLeast(4_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Every second. A clip is seconds long and gets scrubbed, so
            // sparse keyframes make seeking within it unpleasant.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, surface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false

        // Seek to the sync sample at or before the start, then decode forward
        // and discard until the window opens. Seeking to the nearest sync
        // AFTER the start would silently drop the opening of the rally.
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawDecodeEos = false
        var sawEncodeEos = false
        var rendered = 0

        try {
            while (!sawEncodeEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!sawDecodeEos) {
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        val pts = info.presentationTimeUs
                        val inWindow = pts in startUs..endUs
                        if (inWindow && info.size > 0) {
                            // Rebase to the clip's own zero, in nanoseconds.
                            decoder.releaseOutputBuffer(outIndex, (pts - startUs) * 1000)
                            rendered++
                        } else {
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                        val past = pts > endUs
                        if (past || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawDecodeEos = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                var encIndex = encoder.dequeueOutputBuffer(info, 10_000)
                while (encIndex >= 0 || encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else {
                        val buf = encoder.getOutputBuffer(encIndex)!!
                        // The codec-config buffer is metadata, already carried
                        // by addTrack; muxing it as a sample corrupts the file.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, buf, info)
                        }
                        encoder.releaseOutputBuffer(encIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEncodeEos = true
                            break
                        }
                    }
                    encIndex = encoder.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
            surface.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            extractor.release()
        }

        check(rendered > 0) {
            "no frames fell inside ${"%.2f".format(startSeconds)}..${"%.2f".format(endSeconds)}s of ${'$'}{source.name}"
        }
    }

    /** Expected frame count for a window, for tests to check against. */
    fun expectedFrames(startSeconds: Double, endSeconds: Double, fps: Double): Int =
        ((endSeconds - startSeconds) * fps).roundToInt()
}
