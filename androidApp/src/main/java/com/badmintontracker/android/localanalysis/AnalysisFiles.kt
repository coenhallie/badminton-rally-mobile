package com.badmintontracker.android.localanalysis

import com.badmintontracker.shared.localvideo.LocalVideoRemoval
import java.io.File

private val SAFE_ENTRY_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

internal fun isSafeEntryId(entryId: String): Boolean = SAFE_ENTRY_ID.matches(entryId)

/**
 * Guards every on-disk store path derived from an entry ID against path
 * traversal (`..`, path separators, empty or overly long names).
 */
internal fun requireSafeEntryId(entryId: String): String {
    require(isSafeEntryId(entryId)) { "invalid entry id" }
    return entryId
}

/**
 * Resolves [relativeName] under [base] and verifies the canonical path stays
 * strictly inside [base].
 */
internal fun resolveContainedChild(base: File, relativeName: String): File {
    val baseCanonical = base.canonicalFile
    val target = File(baseCanonical, relativeName).canonicalFile
    val basePath = baseCanonical.path
    val expectedPrefix = if (basePath.endsWith(File.separator)) basePath else basePath + File.separator
    require(target.path.startsWith(expectedPrefix)) {
        "resolved path escapes base directory"
    }
    return target
}

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
     * position are computed entirely from these and need no video at all: a
     * track kept after its file is gone still opens the match page's Analysis,
     * with Heatmap and Base but no Skeleton. Taking them here would turn "get
     * my storage back" into "lose my analysis". Only a match with a cloud copy
     * is listed again afterwards; one only on this phone leaves the list with
     * its entry, and its kept track with it.
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
        requireSafeEntryId(entryId)
        val stores = when (reason) {
            LocalVideoRemoval.DISCARDED -> VIDEO_STORES + TRACK_STORES
            LocalVideoRemoval.VIDEO_ONLY -> VIDEO_STORES
        }
        for (store in stores) {
            val base = File(filesDir, store)
            resolveContainedChild(base, entryId).deleteRecursively()
            for (suffix in SUFFIXES) {
                resolveContainedChild(base, entryId + suffix).delete()
                // What writeAtomically leaves when the process dies between the
                // write and the rename.
                resolveContainedChild(base, entryId + suffix + TMP_SUFFIX).delete()
            }
        }
    }
}
