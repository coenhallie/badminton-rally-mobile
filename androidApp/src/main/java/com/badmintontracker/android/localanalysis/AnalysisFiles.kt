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
     * What this device's own analysis wrote. Always removed: the entry is
     * leaving either way, and a local track is only reachable through it.
     */
    private val LOCAL_STORES = listOf(
        "player-tracks", "skeletons", "local-clips", "local-sources",
    )

    /**
     * What a cloud analysis wrote for this entry.
     *
     * Removed on a DISCARD and kept on a RETIRE. A cloud artifact costs a
     * re-download rather than half an hour of device time, which is why it was
     * once safe to treat it like the local ones - but nothing re-downloads it
     * today (`CloudPoseCoordinator.install` has one caller, inside the upload
     * pipeline), so deleting it on the pipeline's own success path loses it for
     * good.
     */
    private val CLOUD_STORES = listOf("cloud-tracks", "cloud-skeletons")

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
            LocalVideoRemoval.DISCARDED -> LOCAL_STORES + CLOUD_STORES
            LocalVideoRemoval.RETIRED -> LOCAL_STORES
        }
        for (store in stores) {
            val base = File(filesDir, store)
            File(base, entryId).deleteRecursively()
            for (suffix in SUFFIXES) File(base, entryId + suffix).delete()
        }
    }
}
