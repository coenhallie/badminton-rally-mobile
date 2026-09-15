package com.badmintontracker.android.localanalysis

import com.badmintontracker.shared.localvideo.LocalVideoRemoval
import java.io.File

/**
 * What an analysis leaves on disk, and how it is thrown away.
 *
 * The stores a run writes into, named in one place rather than at each of
 * their own call sites: [PlayerTrackStore] and [SkeletonStore] each own a
 * local pair and a cloud pair, and [LocalAnalysisRunner] the copied source,
 * and none of them knew about a deletion. A match removed from the app kept
 * its cut clips - tens of megabytes - and its skeleton for the life of the
 * install, reachable by nothing.
 *
 * How much goes depends on WHY the entry left, which is what
 * [LocalVideoRemoval] carries. A coach deleting a video wants everything
 * derived from it gone. A cloud run retiring one does not: it has just written
 * this entry's cloud track and skeleton, and deleting them here would throw
 * away the result of the run that is doing the deleting. That is not
 * hypothetical - it is what shipped, and what this split exists to stop.
 *
 * The iOS side is `AnalysisFiles.deleteAll`, over the same six names and the
 * same rule.
 */
object AnalysisFiles {

    /**
     * What goes whatever the reason: the footage, the re-encoded clips beside
     * it, and both skeletons.
     *
     * The skeletons are here rather than with the tracks because a skeleton is
     * drawn OVER playback. With the video gone it is an artifact nothing can
     * display - roughly 11MB of it for a 30-minute match - so keeping one past
     * its video would be storage held for a panel that can never open.
     */
    private val VIDEO_STORES = listOf(
        "local-clips", "local-sources", "skeletons", "cloud-skeletons",
    )

    /**
     * The player tracks, local and cloud.
     *
     * Kept when the coach is only freeing the footage. The heatmap and the base
     * position are computed entirely from these and need no video at all, which
     * is what `AnalyticsRowState.READY_NO_VIDEO` exists for - so taking them
     * here would turn "get my storage back" into "lose my analysis".
     */
    private val TRACK_STORES = listOf("player-tracks", "cloud-tracks")

    /**
     * Two shapes: a directory named for the entry (the clips) and a file named
     * for it with an extension (everything else). Both are tried for every
     * store rather than mapped one to one, so adding a store here is one string
     * and cannot get its shape wrong.
     */
    private val SUFFIXES = listOf(".track", ".skel", ".mp4")

    /** Removes what this video left on disk, as much of it as [reason] allows. */
    fun deleteAll(filesDir: File, entryId: String, reason: LocalVideoRemoval) {
        val stores = when (reason) {
            LocalVideoRemoval.DISCARDED -> VIDEO_STORES + TRACK_STORES
            LocalVideoRemoval.VIDEO_ONLY -> VIDEO_STORES
        }
        for (store in stores) {
            val base = File(filesDir, store)
            File(base, entryId).deleteRecursively()
            for (suffix in SUFFIXES) File(base, entryId + suffix).delete()
        }
    }
}
