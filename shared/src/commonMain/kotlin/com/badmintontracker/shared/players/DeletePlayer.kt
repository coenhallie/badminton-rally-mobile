package com.badmintontracker.shared.players

import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.forgetPlayer
import com.badmintontracker.shared.scoring.ScoreLogsRepository

/**
 * Deletes [player] and leaves their matches readable, returning null or the
 * sentence to show. The server first: only a delete that landed rewrites this
 * phone's copies, so a refused delete never unassigns a match that still names a
 * live player. A sync that runs in the instant between the two steps can push a
 * score log at the dead id and be refused once; the next sync pushes the rewritten
 * row. [forgetVanishedPlayers] closes that window on the next refresh.
 *
 * The one delete path: the player page and the Swift side both call this, never
 * [PlayersRepository.delete] alone, because that would leave cached logs and
 * un-uploaded entries pointing at an id the database no longer has.
 */
suspend fun deletePlayerOrMessage(
    players: PlayersRepository,
    scoreLogs: ScoreLogsRepository,
    localVideos: LocalVideoRepository,
    player: Player,
): String? {
    val deleted = players.delete(player.id)
    deleted.exceptionOrNull()?.let { return PlayersCopy.failureMessage(it, PlayersCopy.DELETE_FAILED) }
    scoreLogs.forgetPlayer(player.id, player.name)
    localVideos.forgetPlayer(player.id, player.name)
    return null
}

/**
 * Rewrites the phone's copies for every player in [before] that is not in [after]:
 * deleted on another device of the same coach, or on this one a moment ago. The
 * cached name is the one [before] held, so the match keeps reading as it did.
 * [before] is the repository's state read just before a successful refresh and
 * [after] the state read just after it; the list's owner calls this on each.
 */
fun forgetVanishedPlayers(
    before: List<Player>,
    after: List<Player>,
    scoreLogs: ScoreLogsRepository,
    localVideos: LocalVideoRepository,
) {
    val remaining = after.map { it.id }.toSet()
    for (gone in before.filter { it.id !in remaining }) {
        scoreLogs.forgetPlayer(gone.id, gone.name)
        localVideos.forgetPlayer(gone.id, gone.name)
    }
}

/**
 * Refreshes [players] and runs [forgetVanishedPlayers] over what it shows gone,
 * returning null or the sentence for a failed load. What the match list's owner
 * calls on each pull, on both platforms.
 *
 * The forget runs only when [currentOwnerId] names someone before the refresh,
 * names the same someone after it, and the refresh succeeded: a failed refresh
 * says nothing about who was deleted, and a refresh with no session or across a
 * sign-out and sign-in compares two coaches' lists. "Before" is also cut down to
 * that owner's players, because the repository keeps its list in memory across a
 * sign-out and could still hold the previous coach's. An empty list after a
 * successful refresh is taken at its word: every player was deleted.
 */
suspend fun refreshPlayersAndForgetVanishedOrMessage(
    players: PlayersRepository,
    scoreLogs: ScoreLogsRepository,
    localVideos: LocalVideoRepository,
    currentOwnerId: () -> String?,
): String? {
    val owner = currentOwnerId()
    val before = players.players.value.filter { it.ownerId == owner }
    val refreshed = players.refresh()
    refreshed.exceptionOrNull()?.let { return PlayersCopy.failureMessage(it, PlayersCopy.LOAD_FAILED) }
    if (owner != null && currentOwnerId() == owner) {
        forgetVanishedPlayers(before, players.players.value, scoreLogs, localVideos)
    }
    return null
}
