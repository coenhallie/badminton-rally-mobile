package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.pixels
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.PlayerPose
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The near player's joints for one analysed video, kept on disk so the
 * skeleton view outlives the run that made it.
 *
 * Binary, because a person does not read a joint list and a 30-minute match is
 * 11MB of them: 216 bytes a pose against the track store's three numbers. The
 * layout is fixed width and little-endian, so [has] can answer from the header
 * and [load] can check the length before parsing a byte.
 *
 *   magic "SKEL", version i32, fps f64, videoWidth i32, videoHeight i32,
 *   hasMarks i32, 12 x (x f64, y f64) in CourtKeypoints.pixels() order,
 *   poseCount i32, then per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32)
 *
 * The timestamp is the frame's container presentation time, which is what
 * playback matches on; the video size is what the joints are measured in.
 * Both travel with the poses because a renderer that took either from
 * somewhere else would be pairing them by coincidence.
 *
 * The court marks travel with them for the same reason, and one more: a
 * reading in metres needs the homography those marks fit, the local entry that
 * holds them can be deleted while this file lives on, and a file that carries
 * what it needs cannot be paired with the wrong marks.
 *
 * Version 1 files, written before the marks were stored, are still read; they
 * load with `marks = null`. Save always writes version 2.
 *
 * Save writes a complete file atomically: bytes are written to a temporary
 * sibling file, then renamed into place. A partial write never becomes visible,
 * so [has] reading the header alone is sound for files this store wrote. Files
 * truncated by external corruption still load as null.
 */
class SkeletonStore(private val root: File) {

    data class Stored(
        val poses: List<PlayerPose>,
        val fps: Double,
        val videoWidth: Int,
        val videoHeight: Int,
        val marks: CourtKeypoints?,
    )

    fun save(
        entryId: String,
        poses: List<PlayerPose>,
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: CourtKeypoints?,
    ) {
        // Nothing to draw is nothing to offer: no file, so has() stays false.
        if (poses.isEmpty()) return
        val buffer = ByteBuffer.allocate(HEADER_BYTES_V2 + poses.size * POSE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(VERSION).putDouble(fps).putInt(videoWidth).putInt(videoHeight)
        buffer.putInt(if (marks != null) 1 else 0)
        // The marks field is fixed width whether or not there are marks, so
        // everything after it sits at the same offset in every v2 file.
        val pixels = marks?.pixels()
        repeat(12) { i ->
            val point = pixels?.get(i)
            buffer.putDouble(point?.x ?: 0.0).putDouble(point?.y ?: 0.0)
        }
        buffer.putInt(poses.size)
        poses.forEach { pose ->
            require(pose.keypoints.size == Coco.COUNT && pose.confidence.size == Coco.COUNT) {
                "pose at frame ${pose.frame} has ${pose.keypoints.size} joints"
            }
            buffer.putInt(pose.frame).putDouble(pose.timestamp)
            for (k in 0 until Coco.COUNT) {
                buffer.putFloat(pose.keypoints[k].x.toFloat())
                    .putFloat(pose.keypoints[k].y.toFloat())
                    .putFloat(pose.confidence[k])
            }
        }
        val file = fileFor(entryId).apply { parentFile?.mkdirs() }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeBytes(buffer.array())
            if (!tmp.renameTo(file)) {
                throw java.io.IOException("Failed to rename ${tmp.absolutePath} to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Whether a skeleton this version can draw exists for [entryId], from the header alone. */
    fun has(entryId: String): Boolean {
        val file = fileFor(entryId)
        // The v1 floor, not the v2 one: this reads only the first eight
        // bytes, so the shorter of the two headers is the right floor for
        // both versions. A v2 file clears it comfortably.
        if (!file.isFile || file.length() < HEADER_BYTES_V1) return false
        return runCatching {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            val version = buffer.getInt()
            magic.contentEquals(MAGIC) && (version == 1 || version == VERSION)
        }.getOrDefault(false)
    }

    /** Null when there is nothing stored, or when what is stored cannot be read whole. */
    fun load(entryId: String): Stored? {
        val file = fileFor(entryId)
        if (!file.isFile) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) return null
            val version = buffer.getInt()
            if (version != 1 && version != VERSION) return null
            val fps = buffer.getDouble()
            val width = buffer.getInt()
            val height = buffer.getInt()
            var marks: CourtKeypoints? = null
            if (version == VERSION) {
                val hasMarks = buffer.getInt()
                // Refused rather than guessed at, as a bad magic or an unknown
                // version is: this store writes only 0 or 1, so anything else
                // is not a file it wrote.
                if (hasMarks != 0 && hasMarks != 1) return null
                // Guarded before the reads, not after: a file cut inside the
                // marks would otherwise throw out of runCatching, which reads
                // as "no skeleton" for the wrong reason and hides where the
                // file ended. The 4 is the count that follows the marks.
                if (buffer.remaining() < MARKS_BYTES + 4) return null
                val points = List(12) { Point(buffer.getDouble(), buffer.getDouble()) }
                if (hasMarks == 1) {
                    marks = CourtKeypoints(
                        topLeft = points[0], topRight = points[1],
                        bottomRight = points[2], bottomLeft = points[3],
                        netLeft = points[4], netRight = points[5],
                        serviceLineNearLeft = points[6], serviceLineNearRight = points[7],
                        serviceLineFarLeft = points[8], serviceLineFarRight = points[9],
                        centerNear = points[10], centerFar = points[11],
                    )
                }
            }
            val count = buffer.getInt()
            // Guarded before the multiply below: a negative or absurdly large
            // count - corruption, not a file this store wrote - would
            // otherwise overflow count * POSE_BYTES and could pass the
            // remaining-bytes check that follows on the wrapped value.
            if (count < 0 || count > buffer.remaining() / POSE_BYTES) return null
            // Length checked before parsing: a file killed mid-write ends in
            // a partial pose, and a skeleton missing its last joints is not one.
            if (buffer.remaining() != count * POSE_BYTES) return null
            val poses = ArrayList<PlayerPose>(count)
            repeat(count) {
                val frame = buffer.getInt()
                val timestamp = buffer.getDouble()
                val keypoints = ArrayList<Point>(Coco.COUNT)
                val confidence = ArrayList<Float>(Coco.COUNT)
                repeat(Coco.COUNT) {
                    keypoints.add(Point(buffer.getFloat().toDouble(), buffer.getFloat().toDouble()))
                    confidence.add(buffer.getFloat())
                }
                poses.add(PlayerPose(frame, timestamp, keypoints, confidence))
            }
            Stored(poses, fps, width, height, marks)
        }.getOrNull()
    }

    /**
     * Removes any skeleton stored for [entryId], final file and a leftover temp
     * file alike. Called by the runner when a completed run did not ask for a
     * skeleton, so an earlier run's poses cannot outlive the run that made them
     * and be offered against a track that has since moved on. A no-op when
     * neither file exists.
     *
     * `File.delete()`'s return value is checked against whether the file is
     * still there afterwards: a stale skeleton that silently failed to delete
     * would keep answering [has] and [load] as if this call had succeeded.
     */
    fun delete(entryId: String) {
        val file = fileFor(entryId)
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fileDeleted = file.delete()
        val tmpDeleted = tmp.delete()
        if ((!fileDeleted && file.exists()) || (!tmpDeleted && tmp.exists())) {
            throw java.io.IOException("Failed to delete skeleton at ${file.absolutePath}")
        }
    }

    private fun fileFor(entryId: String) = File(root, "skeletons/$entryId.skel")

    private companion object {
        val MAGIC = "SKEL".toByteArray(Charsets.US_ASCII)
        const val VERSION = 2
        const val HEADER_BYTES_V1 = 4 + 4 + 8 + 4 + 4 + 4
        const val MARKS_BYTES = 12 * 2 * 8
        const val HEADER_BYTES_V2 = 4 + 4 + 8 + 4 + 4 + 4 + MARKS_BYTES + 4
        const val POSE_BYTES = 4 + 8 + Coco.COUNT * 12
    }
}
