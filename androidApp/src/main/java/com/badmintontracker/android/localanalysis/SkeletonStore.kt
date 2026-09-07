package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
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
 *   poseCount i32, then per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32)
 *
 * The timestamp is the frame's container presentation time, which is what
 * playback matches on; the video size is what the joints are measured in.
 * Both travel with the poses because a renderer that took either from
 * somewhere else would be pairing them by coincidence.
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
    )

    fun save(entryId: String, poses: List<PlayerPose>, fps: Double, videoWidth: Int, videoHeight: Int) {
        // Nothing to draw is nothing to offer: no file, so has() stays false.
        if (poses.isEmpty()) return
        val buffer = ByteBuffer.allocate(HEADER_BYTES + poses.size * POSE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(VERSION).putDouble(fps).putInt(videoWidth).putInt(videoHeight).putInt(poses.size)
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
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        return runCatching {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            magic.contentEquals(MAGIC) && buffer.getInt() == VERSION
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
            if (buffer.getInt() != VERSION) return null
            val fps = buffer.getDouble()
            val width = buffer.getInt()
            val height = buffer.getInt()
            val count = buffer.getInt()
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
            Stored(poses, fps, width, height)
        }.getOrNull()
    }

    private fun fileFor(entryId: String) = File(root, "skeletons/$entryId.skel")

    private companion object {
        val MAGIC = "SKEL".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val HEADER_BYTES = 4 + 4 + 8 + 4 + 4 + 4
        const val POSE_BYTES = 4 + 8 + Coco.COUNT * 12
    }
}
