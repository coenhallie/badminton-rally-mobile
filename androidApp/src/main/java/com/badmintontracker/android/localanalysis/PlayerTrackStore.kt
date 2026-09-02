package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerTrack
import java.io.File

/**
 * The player track, kept on disk so a heatmap outlives the process that made it.
 *
 * An on-device run with pose costs half an hour. Holding its only copy in a
 * StateFlow means an app swipe, a reinstall or an out-of-memory kill throws that
 * away and the user is asked to spend the half hour again, which is not a
 * reasonable thing to ask twice.
 *
 * A plain text table rather than a serialization framework: the shape is four
 * primitives and it has to be readable by a person debugging a bad heatmap. The
 * whole file for a 30-minute match is under a megabyte.
 */
class PlayerTrackStore(private val root: File) {

    fun save(entryId: String, track: PlayerTrack, fps: Double) {
        val file = fileFor(entryId).apply { parentFile?.mkdirs() }
        file.writeText(
            buildString {
                append(VERSION).append(' ').append(fps).append(' ')
                    .append(track.framesWithPose).append('\n')
                track.samples.forEach {
                    append(it.frame).append(',')
                        .append(it.courtPosition.x).append(',')
                        .append(it.courtPosition.y).append(',')
                        .append(if (it.onAnkles) 1 else 0).append('\n')
                }
            },
        )
    }

    /** Null when there is nothing stored, or when what is stored cannot be read. */
    fun load(entryId: String): Stored? {
        val file = fileFor(entryId)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            val header = lines.firstOrNull()?.split(' ') ?: return null
            if (header.getOrNull(0) != VERSION) return null
            val fps = header[1].toDouble()
            val framesWithPose = header[2].toInt()
            val samples = lines.drop(1).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val f = line.split(',')
                PlayerSample(
                    frame = f[0].toInt(),
                    courtPosition = Point(f[1].toDouble(), f[2].toDouble()),
                    onAnkles = f[3] == "1",
                )
            }
            // Rejections are not stored: they explain a thin track while it is
            // being produced, and the counts that matter afterwards - coverage
            // and the ankle share - are recoverable from the samples.
            Stored(PlayerTrack(samples, framesWithPose, emptyMap()), fps)
        }.getOrNull()
    }

    private fun fileFor(entryId: String) = File(root, "$DIR/$entryId.track")

    data class Stored(val track: PlayerTrack, val fps: Double)

    private companion object {
        const val DIR = "player-tracks"

        /** Bumped if the columns change, so an old file is ignored rather than misread. */
        const val VERSION = "v1"
    }
}
