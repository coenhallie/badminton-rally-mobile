package com.badmintontracker.android.localanalysis

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
 * The cloud stores are included on the same footing as the local ones:
 * removing the entry already removes its local analysis, and nothing protects
 * a cloud artifact the way it protects a local one - a local track costs half
 * an hour of device time to recreate, a cloud one only a re-download. A match
 * uploaded from a different phone has no local entry here to delete, so its
 * artifacts are never reached by this.
 *
 * The iOS side is `AnalysisFiles.deleteAll`, over the local names only for
 * now; it picks up the cloud names in the task that gives it a cloud path.
 */
object AnalysisFiles {

    /**
     * Two shapes: a directory named for the entry (the clips) and a file named
     * for it with an extension (everything else). Both are tried for every
     * store rather than mapped one to one, so adding a store here is one string
     * and cannot get its shape wrong.
     */
    private val STORES = listOf(
        "player-tracks", "skeletons", "local-clips", "local-sources",
        // Cloud artifacts for this entry, not this device's own analysis.
        // Removing the entry already removes its local analysis, and a cloud
        // artifact has no equivalent reason to survive: it costs a
        // re-download, not half an hour of device time. A match uploaded
        // from a different phone has no local entry to delete, so this never
        // reaches an artifact whose local entry is elsewhere.
        "cloud-tracks", "cloud-skeletons",
    )
    private val SUFFIXES = listOf(".track", ".skel", ".mp4")

    /** Removes everything an analysis produced for one video. */
    fun deleteAll(filesDir: File, entryId: String) {
        for (store in STORES) {
            val base = File(filesDir, store)
            File(base, entryId).deleteRecursively()
            for (suffix in SUFFIXES) File(base, entryId + suffix).delete()
        }
    }
}
