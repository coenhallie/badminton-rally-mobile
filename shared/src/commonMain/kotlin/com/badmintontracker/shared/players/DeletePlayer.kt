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
 * The player ids the matches on this phone name: every cached score log and every
 * local entry, home and away.
 */
internal fun referencedPlayerIds(
    scoreLogs: ScoreLogsRepository,
    localVideos: LocalVideoRepository,
): Set<String> =
    (scoreLogs.logs.value.map { it.matchPlayers } + localVideos.entries.value.map { it.matchPlayers })
        .flatMap { listOfNotNull(it.homePlayerId, it.awayPlayerId) }
        .toSet()

/**
 * Rewrites the phone's copies for every player id that a successful refresh shows
 * is not the coach's any more: deleted on another device of the same coach, on
 * this one a moment ago, or never this coach's (left on the phone by an earlier
 * sign-in). The database refuses all of them, and a score log that still named one
 * would fail the whole sync batch, every other log's push with it.
 *
 * The ids looked at are those in [before] plus [referenced], both read just before
 * the refresh; [after] is the list read just after it. Only ids known before the
 * refresh began count: anything known then existed before the refresh's select
 * began, so missing from its answer means gone. An id assigned while the refresh
 * was out (a player added inline, a log another sync pulled meanwhile) can be newer
 * than the select and is left for the next refresh, rather than stripped from a
 * live player.
 *
 * The name kept is the one [before] held. An id it never held (the refresh that
 * would have cached it failed, or the cache had not been re-read yet) has no name
 * on this phone, and its side is left unnamed. No local record carries a resolved
 * name to fall back on.
 */
internal fun forgetVanishedPlayers(
    before: List<Player>,
    referenced: Set<String>,
    after: List<Player>,
    scoreLogs: ScoreLogsRepository,
    localVideos: LocalVideoRepository,
) {
    val remaining = after.map { it.id }.toSet()
    val names = before.associate { it.id to it.name }
    for (gone in (names.keys + referenced) - remaining) {
        scoreLogs.forgetPlayer(gone, names[gone])
        localVideos.forgetPlayer(gone, names[gone])
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
 * sign-out and sign-in compares two coaches' lists. An empty list after a
 * successful refresh is taken at its word: every player was deleted.
 */
suspend fun refreshPlayersAndForgetVanishedOrMessage(
    players: PlayersRepository,
    scoreLogs: ScoreLogsRepository,
    localVideos: LocalVideoRepository,
    currentOwnerId: () -> String?,
): String? {
    val owner = currentOwnerId()
    // Both before the request, never after: see forgetVanishedPlayers.
    val before = players.players.value
    val referenced = referencedPlayerIds(scoreLogs, localVideos)
    val refreshed = players.refresh()
    refreshed.exceptionOrNull()?.let { return PlayersCopy.failureMessage(it, PlayersCopy.LOAD_FAILED) }
    if (owner != null && currentOwnerId() == owner) {
        forgetVanishedPlayers(before, referenced, players.players.value, scoreLogs, localVideos)
    }
    return null
}
