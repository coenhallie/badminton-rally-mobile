package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.pixels
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerPose
import com.badmintontracker.analysis.player.PlayerSelection
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
 *   v3: magic "SKEL", version i32 = 3, fps f64, videoWidth i32, videoHeight i32,
 *   hasMarks i32, 12 x (x f64, y f64), trackCount i32, then per track:
 *     side i32 (CourtSide.ordinal), poseCount i32, then poses as in v2.
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
 * v3 is a THIRD version this store reads, not a replacement: v1 and v2 files
 * already on phones keep loading, and a device run keeps writing v2 because it
 * only ever has the near player. Only the cloud path writes v3.
 *
 * Save writes a complete file atomically: bytes are written to a temporary
 * sibling file, then renamed into place. A partial write never becomes visible,
 * so [has] reading the header alone is sound for files this store wrote. Files
 * truncated by external corruption still load as null.
 */
class SkeletonStore(private val root: File) {

    /** One player's joints, and which half of the court they were on. */
    data class SidePoses(val side: CourtSide, val poses: List<PlayerPose>)

    data class Stored(
        val tracks: List<SidePoses>,
        val fps: Double,
        val videoWidth: Int,
        val videoHeight: Int,
        val marks: CourtKeypoints?,
    ) {
        /** The near player's joints, which is what every caller wanted before there were two. */
        val poses: List<PlayerPose>
            get() = tracks.firstOrNull { it.side == CourtSide.NEAR }?.poses ?: emptyList()
    }

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
        poses.forEach { pose -> buffer.putPose(pose) }
        writeAtomically(fileFor(entryId), buffer.array())
    }

    /**
     * Every player's joints from one analysis, in the v3 format.
     *
     * v3 is a THIRD version this store reads, not a replacement: v1 and v2
     * files already on phones keep loading, and a device run keeps writing v2
     * because it only ever has the near player.
     */
    fun saveAll(
        entryId: String,
        source: TrackSource,
        selections: List<PlayerSelection>,
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: CourtKeypoints?,
    ) {
        val withPoses = selections.filter { it.poses.isNotEmpty() }
        // Nothing to draw is nothing to offer, matching save().
        if (withPoses.isEmpty()) return
        val poseCount = withPoses.sumOf { it.poses.size }
        val bytes = HEADER_BYTES_V3 + withPoses.size * TRACK_HEADER_BYTES_V3 + poseCount * POSE_BYTES
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(VERSION_V3).putDouble(fps).putInt(videoWidth).putInt(videoHeight)
        buffer.putInt(if (marks != null) 1 else 0)
        val pixels = marks?.pixels()
        repeat(12) { i ->
            val point = pixels?.get(i)
            buffer.putDouble(point?.x ?: 0.0).putDouble(point?.y ?: 0.0)
        }
        buffer.putInt(withPoses.size)
        withPoses.forEach { selection ->
            buffer.putInt(selection.side.ordinal).putInt(selection.poses.size)
            selection.poses.forEach { pose -> buffer.putPose(pose) }
        }
        writeAtomically(fileFor(entryId, source), buffer.array())
    }

    /** Writes [pose] in the fixed-width layout common to every version this store writes. */
    private fun ByteBuffer.putPose(pose: PlayerPose) {
        require(pose.keypoints.size == Coco.COUNT && pose.confidence.size == Coco.COUNT) {
            "pose at frame ${pose.frame} has ${pose.keypoints.size} joints"
        }
        putInt(pose.frame).putDouble(pose.timestamp)
        for (k in 0 until Coco.COUNT) {
            putFloat(pose.keypoints[k].x.toFloat())
                .putFloat(pose.keypoints[k].y.toFloat())
                .putFloat(pose.confidence[k])
        }
    }

    /** Writes [bytes] to [file] atomically: a temp sibling, then a rename into place. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                throw java.io.IOException("Failed to rename ${tmp.absolutePath} to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Whether a skeleton this version can draw exists for [entryId], from the header alone. */
    fun has(entryId: String, source: TrackSource = TrackSource.LOCAL): Boolean {
        val file = fileFor(entryId, source)
        // The v1 floor, not the v2 one: this reads only the first eight
        // bytes, so the shorter of the two headers is the right floor for
        // every version. A v2 or v3 file clears it comfortably.
        if (!file.isFile || file.length() < HEADER_BYTES_V1) return false
        return runCatching {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            val version = buffer.getInt()
            magic.contentEquals(MAGIC) && (version == 1 || version == VERSION || version == VERSION_V3)
        }.getOrDefault(false)
    }

    /** Null when there is nothing stored, or when what is stored cannot be read whole. */
    fun load(entryId: String, source: TrackSource = TrackSource.LOCAL): Stored? {
        val file = fileFor(entryId, source)
        if (!file.isFile) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) return null
            val version = buffer.getInt()
            when (version) {
                1, VERSION -> loadV1OrV2(version, buffer)
                VERSION_V3 -> loadV3(buffer)
                else -> null
            }
        }.getOrNull()
    }

    /** The single-track format (v1, v2): one header, then every pose for the near player. */
    private fun loadV1OrV2(version: Int, buffer: ByteBuffer): Stored? {
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
            if (hasMarks == 1) marks = points.toCourtKeypoints()
        }
        val poses = buffer.readPoses() ?: return null
        return Stored(listOf(SidePoses(CourtSide.NEAR, poses)), fps, width, height, marks)
    }

    /** The multi-track format: a header, then a section per player. */
    private fun loadV3(buffer: ByteBuffer): Stored? {
        val fps = buffer.getDouble()
        val width = buffer.getInt()
        val height = buffer.getInt()
        val hasMarks = buffer.getInt()
        if (hasMarks != 0 && hasMarks != 1) return null
        if (buffer.remaining() < MARKS_BYTES + 4) return null
        val points = List(12) { Point(buffer.getDouble(), buffer.getDouble()) }
        val marks = if (hasMarks == 1) points.toCourtKeypoints() else null
        val trackCount = buffer.getInt()
        // Rejected before the loop below can allocate or slice on it: a
        // negative or implausibly large count is corruption, not a file this
        // store wrote.
        if (trackCount < 0 || trackCount > CourtSide.entries.size) return null
        val tracks = ArrayList<SidePoses>(trackCount)
        repeat(trackCount) {
            if (buffer.remaining() < 8) return null
            val sideOrdinal = buffer.getInt()
            val side = CourtSide.entries.getOrNull(sideOrdinal) ?: return null
            val poseCount = buffer.getInt()
            if (poseCount < 0 || poseCount > buffer.remaining() / POSE_BYTES) return null
            val poses = buffer.readPoses(poseCount) ?: return null
            tracks.add(SidePoses(side, poses))
        }
        // A file killed mid-write ends short: nothing but a fully-formed
        // trailing pose section is offered.
        if (buffer.hasRemaining()) return null
        return Stored(tracks, fps, width, height, marks)
    }

    /** Reads every remaining pose (v1/v2: the count sits right before the poses). */
    private fun ByteBuffer.readPoses(): List<PlayerPose>? {
        val count = getInt()
        // Guarded before the multiply below: a negative or absurdly large
        // count - corruption, not a file this store wrote - would
        // otherwise overflow count * POSE_BYTES and could pass the
        // remaining-bytes check that follows on the wrapped value.
        if (count < 0 || count > remaining() / POSE_BYTES) return null
        // Length checked before parsing: a file killed mid-write ends in
        // a partial pose, and a skeleton missing its last joints is not one.
        if (remaining() != count * POSE_BYTES) return null
        return readPoses(count)
    }

    /** Reads exactly [count] poses, for a caller that already validated the count. */
    private fun ByteBuffer.readPoses(count: Int): List<PlayerPose>? {
        if (remaining() < count * POSE_BYTES) return null
        val poses = ArrayList<PlayerPose>(count)
        repeat(count) {
            val frame = getInt()
            val timestamp = getDouble()
            val keypoints = ArrayList<Point>(Coco.COUNT)
            val confidence = ArrayList<Float>(Coco.COUNT)
            repeat(Coco.COUNT) {
                keypoints.add(Point(getFloat().toDouble(), getFloat().toDouble()))
                confidence.add(getFloat())
            }
            poses.add(PlayerPose(frame, timestamp, keypoints, confidence))
        }
        return poses
    }

    private fun List<Point>.toCourtKeypoints() = CourtKeypoints(
        topLeft = this[0], topRight = this[1],
        bottomRight = this[2], bottomLeft = this[3],
        netLeft = this[4], netRight = this[5],
        serviceLineNearLeft = this[6], serviceLineNearRight = this[7],
        serviceLineFarLeft = this[8], serviceLineFarRight = this[9],
        centerNear = this[10], centerFar = this[11],
    )

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
    fun delete(entryId: String, source: TrackSource = TrackSource.LOCAL) {
        val file = fileFor(entryId, source)
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fileDeleted = file.delete()
        val tmpDeleted = tmp.delete()
        if ((!fileDeleted && file.exists()) || (!tmpDeleted && tmp.exists())) {
            throw java.io.IOException("Failed to delete skeleton at ${file.absolutePath}")
        }
    }

    private fun fileFor(entryId: String, source: TrackSource = TrackSource.LOCAL) =
        File(root, "${source.skeletonDir}/$entryId.skel")

    private companion object {
        val MAGIC = "SKEL".toByteArray(Charsets.US_ASCII)
        const val VERSION = 2
        const val VERSION_V3 = 3
        const val HEADER_BYTES_V1 = 4 + 4 + 8 + 4 + 4 + 4
        const val MARKS_BYTES = 12 * 2 * 8
        const val HEADER_BYTES_V2 = 4 + 4 + 8 + 4 + 4 + 4 + MARKS_BYTES + 4
        const val HEADER_BYTES_V3 = 4 + 4 + 8 + 4 + 4 + 4 + MARKS_BYTES + 4
        const val TRACK_HEADER_BYTES_V3 = 4 + 4
        const val POSE_BYTES = 4 + 8 + Coco.COUNT * 12
    }
}
