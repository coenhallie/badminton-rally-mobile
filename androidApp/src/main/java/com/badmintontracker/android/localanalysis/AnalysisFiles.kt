package com.badmintontracker.android.localanalysis

import java.io.File

/**
 * What an on-device analysis leaves on disk, and how it is thrown away.
 *
 * The four stores a run writes into, named in one place rather than at each of
 * their own call sites: [PlayerTrackStore] owns two of them, [SkeletonStore]
 * a third and [LocalAnalysisRunner] the copied source, and none of them knew
 * about a deletion. A match removed from the app kept its cut clips - tens of
 * megabytes - and its skeleton for the life of the install, reachable by
 * nothing.
 *
 * The iOS side is `AnalysisFiles.deleteAll`, over the same four names, because
 * the two pipelines file their output identically on purpose.
 */
object AnalysisFiles {

    /**
     * Two shapes: a directory named for the entry (the clips) and a file named
     * for it with an extension (everything else). Both are tried for every
     * store rather than mapped one to one, so adding a store here is one string
     * and cannot get its shape wrong.
     */
    private val STORES = listOf("player-tracks", "skeletons", "local-clips", "local-sources")
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
