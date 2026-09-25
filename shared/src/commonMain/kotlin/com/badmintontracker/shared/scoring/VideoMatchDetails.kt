package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.MAX_MATCH_TITLE_LENGTH
import com.badmintontracker.shared.localvideo.mirrorVideoDetails
import com.badmintontracker.shared.localvideo.normalizeDescription
import com.badmintontracker.shared.localvideo.normalizeTitle
import com.badmintontracker.shared.repo.VideosRepository

/**
 * The name cap the details editor applies to a match that was only ever filmed:
 * the web app's, because the two clients type into the same `videos.title`, and
 * a name one of them cannot show in full is not a name. A scored match's editor
 * applies [MAX_MATCH_TITLE] instead. Everything else about the editor's rules is
 * the same, see [matchDetailsProblem].
 */
const val VIDEO_MATCH_TITLE_CAP = MAX_MATCH_TITLE_LENGTH

const val VIDEO_DETAILS_SAVE_FAILED_MESSAGE =
    "Couldn't save the match details. Check your connection and try again."

/**
 * Saves a video-born match's details, which live on its videos row rather than
 * on a score log, and returns null - or the sentence to show when the save was
 * refused. Shared so the two match pages cannot order or word this differently.
 *
 * The server first, and the local copy only after it lands: the entry that
 * uploaded this video, if it is still on the phone, shows the same name in the
 * "On this phone" list, and a name written there ahead of a refused save would
 * be the divergence the local editor's LOCAL-only window exists to prevent.
 *
 * What is typed is normalised here the way the upload normalised it, so a name
 * typed on the phone and a name typed on the web app obey one rule.
 */
suspend fun updateVideoMatchDetailsOrMessage(
    videos: VideosRepository,
    localVideos: LocalVideoRepository,
    videoId: String,
    title: String,
    description: String,
    format: MatchFormat?,
    homePlayers: List<String>,
    awayPlayers: List<String>,
): String? {
    val cleanTitle = normalizeTitle(title)
    val cleanDescription = normalizeDescription(description)
    val saved = videos.updateMatchDetails(
        videoId = videoId,
        title = cleanTitle,
        description = cleanDescription,
        format = format,
        homePlayers = submittedPlayers(homePlayers, format),
        awayPlayers = submittedPlayers(awayPlayers, format),
    )
    if (saved.isFailure) return VIDEO_DETAILS_SAVE_FAILED_MESSAGE
    localVideos.mirrorVideoDetails(videoId, cleanTitle, cleanDescription)
    return null
}
