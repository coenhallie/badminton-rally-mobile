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
                        .append(it.courtPosition.y).append('\n')
                }
            },
        )
    }

    /**
     * Whether a track this version of the app can draw exists for [entryId],
     * without reading it.
     *
     * [load] is the wrong way to ask: it reads every line of a file that runs to
     * roughly a megabyte for a 30-minute match and turns each one into a
     * [PlayerSample], so a list asking the question once per video pays for a
     * full parse of every analysed video on the phone. Reading the header line
     * costs one small read and answers the question that matters: is this a
     * track the current format can load. A file from an older format is not,
     * and must not be offered - the v1 tracks were built on a homography that
     * could be metres off and on hip positions two to three metres off, and a
     * row offering one would offer a heatmap that is wrong rather than merely
     * unloadable. Recovery is through the drawer, whose Analyze button is
     * gated on the stage rather than on the track.
     *
     * Still weaker than `load(id) != null` for a file truncated after its
     * header, which would need the process killed inside a single writeText
     * of a track that was just held whole in memory. Accepted.
     */
    fun has(entryId: String): Boolean {
        val file = fileFor(entryId)
        if (!file.isFile) return false
        return runCatching {
            file.bufferedReader().use { it.readLine() }?.split(' ')?.firstOrNull() == VERSION
        }.getOrDefault(false)
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
                )
            }
            // Rejections are not stored: they explain a thin track while it is
            // being produced, and the count that matters afterwards, coverage,
            // is recoverable from the samples.
            Stored(PlayerTrack(samples, framesWithPose, emptyMap()), fps)
        }.getOrNull()
    }

    /**
     * The clips a run produced, so they outlive it too.
     *
     * Same reason as the track: cutting re-encodes and the files are tens of
     * megabytes, and they were reachable only from the in-memory banner of the
     * run that made them. A process death left them on disk with nothing in the
     * app able to open them.
     */
    fun saveClips(entryId: String, clips: List<ClipCutter.Clip>) {
        if (clips.isEmpty()) return
        val file = clipIndexFor(entryId).apply { parentFile?.mkdirs() }
        file.writeText(
            buildString {
                append(VERSION).append('\n')
                clips.forEach {
                    append(it.index).append(',')
                        .append(it.startSeconds).append(',')
                        .append(it.endSeconds).append(',')
                        .append(it.file.name).append('\n')
                }
            },
        )
    }

    fun loadClips(entryId: String): List<ClipCutter.Clip> {
        val file = clipIndexFor(entryId)
        // No index: recover whatever is on disk anyway. The clips are the
        // expensive artifact - tens of megabytes and minutes of re-encoding -
        // and refusing to list them because a small sidecar is missing would
        // throw away the thing worth keeping to protect the bookkeeping.
        if (!file.isFile) return scanClips(entryId)
        return runCatching {
            val lines = file.readLines()
            if (lines.firstOrNull() != VERSION) return emptyList()
            lines.drop(1).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val f = line.split(',')
                val clip = File(file.parentFile, f[3])
                // A clip whose file has gone is not a clip; listing it would
                // offer a player that opens on nothing.
                if (!clip.isFile) return@mapNotNull null
                ClipCutter.Clip(
                    index = f[0].toInt(),
                    startSeconds = f[1].toDouble(),
                    endSeconds = f[2].toDouble(),
                    file = clip,
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Clips found by filename, for runs made before the index existed.
     *
     * Their bounds are not recoverable from the file, so they are left at zero
     * and the caller shows the rally number alone rather than a fabricated
     * "0.0s - 0.0s".
     */
    private fun scanClips(entryId: String): List<ClipCutter.Clip> =
        File(root, "local-clips/$entryId").listFiles()
            ?.filter { it.isFile && it.name.startsWith("rally-") && it.extension == "mp4" }
            ?.mapNotNull { f ->
                val index = f.nameWithoutExtension.removePrefix("rally-").toIntOrNull()
                    ?: return@mapNotNull null
                ClipCutter.Clip(index = index, file = f, startSeconds = 0.0, endSeconds = 0.0)
            }
            ?.sortedBy { it.index }
            .orEmpty()

    private fun clipIndexFor(entryId: String) = File(root, "local-clips/$entryId/clips.index")

    private fun fileFor(entryId: String) = File(root, "$DIR/$entryId.track")

    data class Stored(val track: PlayerTrack, val fps: Double)

    private companion object {
        const val DIR = "player-tracks"

        /** Bumped if the columns change, so an old file is ignored rather than misread. */
        /**
         * v2 dropped the per-sample ankle flag: every sample is an ankle
         * sample now. v1 files are refused rather than migrated, on purpose -
         * see [has].
         */
        const val VERSION = "v2"
    }
}
