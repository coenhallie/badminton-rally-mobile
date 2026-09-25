package com.badmintontracker.shared.matchlist

/**
 * Every string the match list and its two sheets show, in one place so the two
 * platforms cannot word the same row two ways.
 */
object MatchListCopy {
    const val NOT_ANALYZED = "Not analyzed"
    const val ANALYZED = "Analyzed"
    const val FILTER_ALL = "All"
    const val FILTER_ON_THIS_PHONE = "On this phone"
    const val FILTER_NOT_ANALYZED = "Not analyzed"
    const val EMPTY_ON_THIS_PHONE = "None of your matches have their video on this phone."
    const val EMPTY_NOT_ANALYZED = "Every match on this phone has been analyzed."
    const val SHARED_SECTION = "Shared with me"
    const val LEGEND_BUTTON = "What the icons mean"
    const val LEGEND_TITLE = "What the icons mean"

    const val WHERE_PHONE = "On this phone"
    const val WHERE_CLOUD = "In the cloud"
    const val WHERE_BOTH = "On this phone and in the cloud"
    const val WHERE_NONE = "No video"

    const val ANALYSIS_RALLIES = "Rallies"
    const val ANALYSIS_HEATMAP = "Heatmap"
    const val ANALYSIS_SKELETON = "Skeleton"
    const val ANALYSIS_SCORE = "Score"

    /** Legend rows: label, then the line under it. */
    val LEGEND: List<Pair<String, String>> = listOf(
        WHERE_PHONE to "The video file is here. The skeleton needs it.",
        WHERE_CLOUD to "Uploaded and cut into rallies. Opens on any phone.",
        ANALYSIS_RALLIES to "Cut into rally clips, on this phone or in the cloud.",
        ANALYSIS_HEATMAP to "Where the player moved, and their base position.",
        ANALYSIS_SKELETON to "Body positions over the video. Only while the video is on this phone.",
        ANALYSIS_SCORE to "Scored point by point courtside.",
    )

    const val MENU_SHARE = "Share"
    const val MENU_EDIT_DETAILS = "Edit details"
    const val MENU_ANALYZE_AGAIN = "Analyze again"
    const val MENU_HEATMAP = "Player heatmap"
    fun menuDeviceClips(count: Int) = "Clips on this phone ($count)"
    const val MENU_REMOVE_FROM_PHONE = "Remove video from this phone"
    const val MENU_DELETE = "Delete match"
    const val MENU_LEAVE_SHARE = "Remove from my list"

    const val SWIPE_DELETE = "Delete"
    const val SWIPE_REMOVE = "Remove"

    const val CHOICE_REMOVE = "Remove video from this phone"
    const val CHOICE_DELETE = "Delete match everywhere"
    const val CHOICE_CANCEL = "Cancel"

    /** Under "Remove video from this phone" in the choice sheet. */
    fun choiceRemoveDetail(delete: MatchDelete): String = when (delete) {
        MatchDelete.SCORE_MATCH -> "The match and every point you scored stay."
        else -> "The match, its rallies and its analysis stay in the cloud."
    }

    /** Under "Delete match everywhere": the same promise each single-choice dialog makes today. */
    fun choiceDeleteDetail(delete: MatchDelete): String = when (delete) {
        MatchDelete.SCORE_MATCH -> "Deletes this match and every point you scored. This can't be undone."
        MatchDelete.BOUND_MATCH -> "Deletes this match, every point you scored and all its rally clips. This can't be undone."
        else -> "Deletes this match and all its rally clips. This can't be undone."
    }
}

private fun analysisWord(a: MatchAnalysis): String = when (a) {
    MatchAnalysis.RALLIES -> "rallies"
    MatchAnalysis.HEATMAP -> "heatmap"
    MatchAnalysis.SKELETON -> "skeleton"
    MatchAnalysis.SCORE -> "score"
}

/**
 * The status line as one sentence: "On this phone and in the cloud. Rallies,
 * heatmap, skeleton." The glyphs are a scan aid; this is what a screen reader
 * hears instead of seven unlabeled shapes.
 */
fun statusDescription(where: MatchWhere?, analyses: List<MatchAnalysis>, statusText: String?): String {
    val whereText = when (where) {
        MatchWhere.PHONE -> MatchListCopy.WHERE_PHONE
        MatchWhere.CLOUD -> MatchListCopy.WHERE_CLOUD
        MatchWhere.BOTH -> MatchListCopy.WHERE_BOTH
        null -> MatchListCopy.WHERE_NONE
    }
    val parts = mutableListOf("$whereText.")
    if (analyses.isNotEmpty()) {
        parts += analyses.joinToString(", ") { analysisWord(it) }
            .replaceFirstChar { it.uppercase() } + "."
    }
    if (statusText != null) parts += if (statusText.endsWith(".") || statusText.endsWith("…")) statusText else "$statusText."
    return parts.joinToString(" ")
}
