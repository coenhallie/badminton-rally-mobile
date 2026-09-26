package com.badmintontracker.android.cliplist

/**
 * What earlier runs left on disk, for the ids the list can show. Read once for
 * the whole list: the probes stat files and parse sidecars, and the list
 * recomposes several times a second while a run reports progress.
 */
data class StoredResults(
    val trackIds: Set<String> = emptySet(),
    val skeletonIds: Set<String> = emptySet(),
    val clipCounts: Map<String, Int> = emptyMap(),
)

fun interface StoredResultsReader {
    /** Called off the main thread. */
    fun read(ids: Set<String>): StoredResults
}
