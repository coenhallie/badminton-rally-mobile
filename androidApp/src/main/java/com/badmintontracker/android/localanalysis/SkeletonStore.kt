package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
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
 *   v1: magic "SKEL", version i32 = 1, fps f64, videoWidth i32, videoHeight i32,
 *   poseCount i32, then per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32)
 *
 *   v2: as v1, with hasMarks i32 and 12 x (x f64, y f64) in
 *   CourtKeypoints.pixels() order between videoHeight and poseCount.
 *
 *   v3: magic "SKEL", version i32 = 3, fps f64, videoWidth i32, videoHeight i32,
 *   hasMarks i32, 12 x (x f64, y f64), trackCount i32, then per track:
 *     side i32 (CourtSide.ordinal), poseCount i32, then poses as in v2.
 *
 *   v4: as v3, with hasCamera i32 between the marks and trackCount. When it is
 *   1 every pose record is followed by its camera, h00 h01 h02 h10 h11 h12 h20
 *   h21 as f64 (h22 is 1.0 by construction and not stored); when it is 0 the
 *   poses are exactly v3's.
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
 * The camera travels for the last of those reasons. The marks describe the
 * frame they were placed on; a pose from a frame the camera had moved in
 * reaches the court only through that frame's camera, and a file that dropped
 * it would turn every pan into metres of error (design §5.3). Within one file
 * every pose has a camera or none does, because selection only returns a pose
 * for a frame whose camera it knew; a mix is refused at save rather than
 * written with cameras dropped or invented.
 *
 * Both [save] and [saveAll] write version 4. A device run writes it too, as
 * one NEAR track, because a device run's poses carry cameras as well: v2 has
 * nowhere to put them. Versions 1 to 3, already on phones, keep loading, with
 * no marks for v1 and no camera for any of them.
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

    /**
     * The near player's joints from a device run, as one NEAR track in the
     * version 4 format. Throws [IllegalArgumentException] when some poses
     * have a camera and some do not.
     */
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
        val bytes = encode(listOf(SidePoses(CourtSide.NEAR, poses)), fps, videoWidth, videoHeight, marks)
        writeAtomically(fileFor(entryId), bytes)
    }

    /**
     * Every player's joints from one analysis, in the version 4 format.
     * Throws [IllegalArgumentException] when some poses have a camera and
     * some do not, across every track: the file has one flag for all of them.
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
        val tracks = withPoses.map { SidePoses(it.side, it.poses) }
        writeAtomically(fileFor(entryId, source), encode(tracks, fps, videoWidth, videoHeight, marks))
    }

    /** The version 4 bytes for [tracks], none of them empty. */
    private fun encode(
        tracks: List<SidePoses>,
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: CourtKeypoints?,
    ): ByteArray {
        val poses = tracks.flatMap { it.poses }
        val withCamera = poses.count { it.camera != null }
        require(withCamera == 0 || withCamera == poses.size) {
            "$withCamera of ${poses.size} poses have a camera; a skeleton file holds all or none"
        }
        val hasCamera = withCamera > 0
        val poseBytes = POSE_BYTES + if (hasCamera) CAMERA_BYTES else 0
        val size = HEADER_BYTES_V4 + tracks.size * TRACK_HEADER_BYTES + poses.size * poseBytes
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(VERSION).putDouble(fps).putInt(videoWidth).putInt(videoHeight)
        buffer.putInt(if (marks != null) 1 else 0)
        // The marks field is fixed width whether or not there are marks, so
        // everything after it sits at the same offset in every file.
        val pixels = marks?.pixels()
        repeat(12) { i ->
            val point = pixels?.get(i)
            buffer.putDouble(point?.x ?: 0.0).putDouble(point?.y ?: 0.0)
        }
        buffer.putInt(if (hasCamera) 1 else 0)
        buffer.putInt(tracks.size)
        tracks.forEach { track ->
            buffer.putInt(track.side.ordinal).putInt(track.poses.size)
            track.poses.forEach { pose ->
                buffer.putPose(pose)
                pose.camera?.let { buffer.putCamera(it) }
            }
        }
        return buffer.array()
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

    /** Writes [camera] as its first eight entries; h22 is 1.0 by construction. */
    private fun ByteBuffer.putCamera(camera: Matrix3x3) {
        require(camera.size == 3 && camera.all { it.size == 3 } && camera[2][2] == 1.0) {
            "a camera is a 3x3 matrix with h22 == 1.0, not $camera"
        }
        for (r in 0 until 3) for (c in 0 until 3) if (r != 2 || c != 2) putDouble(camera[r][c])
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
        // The v1 floor, not a later one: this reads only the first eight
        // bytes, so the shortest header is the right floor for every
        // version. A v2, v3 or v4 file clears it comfortably.
        if (!file.isFile || file.length() < HEADER_BYTES_V1) return false
        return runCatching {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            val version = buffer.getInt()
            magic.contentEquals(MAGIC) && version in 1..VERSION
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
                1, VERSION_V2 -> loadV1OrV2(version, buffer)
                VERSION_V3 -> loadMultiTrack(buffer, withCameraFlag = false)
                VERSION -> loadMultiTrack(buffer, withCameraFlag = true)
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
        if (version == VERSION_V2) {
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

    /**
     * The multi-track formats: a header, then a section per player. v4 is v3
     * with a camera flag after the marks, and a camera after every pose when
     * it is set.
     */
    private fun loadMultiTrack(buffer: ByteBuffer, withCameraFlag: Boolean): Stored? {
        val fps = buffer.getDouble()
        val width = buffer.getInt()
        val height = buffer.getInt()
        val hasMarks = buffer.getInt()
        if (hasMarks != 0 && hasMarks != 1) return null
        // The 4 is the count that follows the marks, and v4's flag another 4.
        if (buffer.remaining() < MARKS_BYTES + 4 + (if (withCameraFlag) 4 else 0)) return null
        val points = List(12) { Point(buffer.getDouble(), buffer.getDouble()) }
        val marks = if (hasMarks == 1) points.toCourtKeypoints() else null
        val hasCamera = if (withCameraFlag) buffer.getInt() else 0
        // Refused, as a marks flag other than 0 or 1 is: not a file this store wrote.
        if (hasCamera != 0 && hasCamera != 1) return null
        val poseBytes = POSE_BYTES + if (hasCamera == 1) CAMERA_BYTES else 0
        val trackCount = buffer.getInt()
        // Rejected before the loop below can allocate or slice on it: a
        // negative or implausibly large count is corruption, not a file this
        // store wrote.
        if (trackCount < 0 || trackCount > CourtSide.entries.size) return null
        val tracks = ArrayList<SidePoses>(trackCount)
        repeat(trackCount) {
            if (buffer.remaining() < TRACK_HEADER_BYTES) return null
            val sideOrdinal = buffer.getInt()
            val side = CourtSide.entries.getOrNull(sideOrdinal) ?: return null
            val poseCount = buffer.getInt()
            if (poseCount < 0 || poseCount > buffer.remaining() / poseBytes) return null
            val poses = buffer.readPoses(poseCount, hasCamera == 1) ?: return null
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

    /**
     * Reads exactly [count] poses, each followed by its camera when
     * [withCamera], for a caller that already validated the count.
     */
    private fun ByteBuffer.readPoses(count: Int, withCamera: Boolean = false): List<PlayerPose>? {
        if (remaining() < count * (POSE_BYTES + if (withCamera) CAMERA_BYTES else 0)) return null
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
            val camera = if (withCamera) {
                val h = DoubleArray(8) { getDouble() }
                listOf(listOf(h[0], h[1], h[2]), listOf(h[3], h[4], h[5]), listOf(h[6], h[7], 1.0))
            } else {
                null
            }
            poses.add(PlayerPose(frame, timestamp, keypoints, confidence, camera))
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
        /** What [save] and [saveAll] write. */
        const val VERSION = 4
        const val VERSION_V2 = 2
        const val VERSION_V3 = 3
        const val HEADER_BYTES_V1 = 4 + 4 + 8 + 4 + 4 + 4
        const val MARKS_BYTES = 12 * 2 * 8
        const val HEADER_BYTES_V4 = 4 + 4 + 8 + 4 + 4 + 4 + MARKS_BYTES + 4 + 4
        const val TRACK_HEADER_BYTES = 4 + 4
        const val POSE_BYTES = 4 + 8 + Coco.COUNT * 12
        const val CAMERA_BYTES = 8 * 8
    }
}
