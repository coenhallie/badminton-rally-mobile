package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shots.detectShuttleShots

const val MIN_SHOTS: Int = 2
const val RALLY_GAP_SECONDS: Double = 3.1
const val MIN_RALLY_DURATION_S: Double = 0.8

/** Fraction of frames in a candidate window that must carry a shuttle position. */
const val SHUTTLE_VISIBILITY_THRESHOLD: Double = 0.25

data class Rally(
    val id: Int,
    val startFrame: Int,
    val endFrame: Int,
    val startTimestamp: Double,
    val endTimestamp: Double,
    val durationSeconds: Double,
)

/**
 * Group shots into rallies by inter-shot gap.
 *
 * Port of `detect_rallies_from_shots`, which is itself the Python twin of the
 * browser's grouping loop. This is the separation that drives clip cutting.
 *
 * Two gates in the Python original are absent. `require_players` is off
 * because this module runs before any player data exists, which is the same
 * position Phase 1 is in and why the cloud passes False there. And the
 * pose-classification fallback in `detect_all_shots` is omitted: it reads
 * `pose_classifications`, which this pipeline never populates, so
 * `detect_all_shots` reduces to `detect_shuttle_shots` on every Phase 1 input.
 */
fun detectRalliesFromShots(frames: List<FrameSample>, fps: Double): List<Rally> {
    if (frames.size < 10) return emptyList()
    val shots = detectShuttleShots(frames, fps)
    if (shots.size < MIN_SHOTS) return emptyList()

    fun shuttleActive(startTs: Double, endTs: Double): Boolean {
        var total = 0
        var visible = 0
        for (f in frames) {
            if (f.timestamp < startTs || f.timestamp > endTs) continue
            total++
            if (f.shuttle?.visible == true) visible++
        }
        return total == 0 || visible.toDouble() / total >= SHUTTLE_VISIBILITY_THRESHOLD
    }

    val detected = ArrayList<Rally>()
    var rallyStart = 0
    for (i in 1 until shots.size) {
        val gap = shots[i].timestamp - shots[i - 1].timestamp
        val isLast = i == shots.size - 1
        if (gap <= RALLY_GAP_SECONDS && !isLast) continue

        // Shot i joins the current rally only when it is the final shot AND
        // close enough to its predecessor. A shot that is both last and beyond
        // the gap starts a new one-shot rally, which is then rejected. Including
        // it welded the whole inter-rally pause onto the previous rally's end.
        val endExclusive = if (isLast && gap <= RALLY_GAP_SECONDS) i + 1 else i
        val group = shots.subList(rallyStart, endExclusive)
        if (group.size >= MIN_SHOTS) {
            val first = group.first()
            val last = group.last()
            val duration = last.timestamp - first.timestamp
            if (duration >= MIN_RALLY_DURATION_S && shuttleActive(first.timestamp, last.timestamp)) {
                detected.add(
                    Rally(
                        id = detected.size + 1,
                        startFrame = first.frame,
                        endFrame = last.frame,
                        startTimestamp = first.timestamp,
                        endTimestamp = last.timestamp,
                        durationSeconds = duration,
                    )
                )
            }
        }
        rallyStart = i
    }
    return detected
}
