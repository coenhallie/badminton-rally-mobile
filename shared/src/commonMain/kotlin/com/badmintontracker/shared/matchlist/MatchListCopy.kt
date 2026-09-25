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
    /** What a screen reader calls the three filter chips, as one control. */
    const val FILTER_GROUP = "Filter"
    /** The row's overflow button, to a screen reader. */
    const val MATCH_MENU = "Match menu"

    /** The status line's own words, as the builder writes them. */
    const val PAUSED = "Paused"
    const val ANALYSIS_FAILED = "Analysis failed"
    fun analysisFailed(message: String?) = "$ANALYSIS_FAILED: ${message ?: "Unknown error"}"
    fun sharedBy(email: String) = "Shared by $email"

    const val ACTION_ANALYZE = "Analyze"
    const val ACTION_RETRY = "Retry"
    const val ACTION_MARK_COURT = "Mark court"

    /**
     * The button for what the row asks, or null when there is nothing to press:
     * running and paused speak through the status line. A failed run is "Retry"
     * even when it will resume without court marking: the caller decides what
     * retrying does.
     */
    fun actionLabel(action: MatchAction): String? = when (action) {
        MatchAction.ANALYZE -> ACTION_ANALYZE
        MatchAction.RETRY -> ACTION_RETRY
        MatchAction.MARK_COURT -> ACTION_MARK_COURT
        MatchAction.NONE, MatchAction.RUNNING, MatchAction.PAUSED -> null
    }

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

    const val REMOVE_VIDEO_TITLE = "Remove video?"
    const val CONFIRM_REMOVE = "Remove"
    const val CONFIRM_DELETE = "Delete"

    /** The single delete confirmation's title; the message is [deleteMessage]. */
    fun deleteTitle(delete: MatchDelete): String? = when (delete) {
        MatchDelete.VIDEO_MATCH, MatchDelete.SCORE_MATCH, MatchDelete.BOUND_MATCH -> "Delete match?"
        MatchDelete.LEAVE_SHARE -> "Remove shared match?"
        MatchDelete.NONE -> null
    }

    /** The single delete confirmation's button. */
    fun deleteConfirmLabel(delete: MatchDelete): String? = when (delete) {
        MatchDelete.VIDEO_MATCH, MatchDelete.SCORE_MATCH, MatchDelete.BOUND_MATCH -> CONFIRM_DELETE
        MatchDelete.LEAVE_SHARE -> CONFIRM_REMOVE
        MatchDelete.NONE -> null
    }

    const val CHOICE_REMOVE = "Remove video from this phone"
    const val CHOICE_DELETE = "Delete match everywhere"
    const val CHOICE_CANCEL = "Cancel"

    /**
     * What removing the video from this phone does to [item]: the "Remove
     * video?" dialog's message, and the choice sheet's line under "Remove video
     * from this phone". One function for both, so the two ways to the same
     * gesture cannot promise different things.
     *
     * Removing frees the entry (`freeVideo`): the file, its notes, the clips a
     * device run cut and the skeleton go; the player tracks stay on disk. Only
     * a row with a cloud copy is still listed afterwards, as the cloud row, and
     * only there is a kept track reachable again: that row lists it when the
     * track is keyed by the cloud video's id. A match only on this phone
     * leaves the list altogether, so its heatmap is gone as far as the coach
     * can tell, and saying it stays (what this copy used to say) was not true.
     * A scored match keeps its row whatever happens to the video.
     *
     * "From this app", as [deleteMessage] says it: on Android the original
     * stays in the gallery, which the app only points at.
     */
    fun removeFromPhoneDetail(item: MatchListItem): String {
        val heatmapStays = item.heatmapId != null && item.heatmapId == item.videoId
        val goes = buildList {
            add("the video")
            add("its notes")
            if (item.deviceClipCount > 0) add("the rallies cut on this phone")
            if (MatchAnalysis.SKELETON in item.analyses) add("the skeleton")
            if (item.heatmapId != null && !heatmapStays) add("the heatmap")
        }
        val scored = MatchAnalysis.SCORE in item.analyses
        val stays = when {
            item.where == MatchWhere.BOTH -> {
                val kept = listOfNotNull("The match", "every point you scored".takeIf { scored }, "its rallies")
                "${listed(kept)} stay in the cloud." + if (heatmapStays) " The heatmap stays too." else ""
            }
            item.scoreLogId != null && scored -> "The match and every point you scored stay."
            item.scoreLogId != null -> "The match stays."
            else -> "The match is only on this phone, so it leaves your list."
        }
        return "Removes ${listed(goes)} from this app. $stays"
    }

    /**
     * The choice dialog's whole message, for a platform whose dialog has one
     * message and no room under each option (iOS's confirmation dialog): each
     * option's name, then what it does. Android's sheet shows the same two
     * details under their own options instead.
     */
    fun choiceMessage(item: MatchListItem): String =
        "$CHOICE_REMOVE\n${removeFromPhoneDetail(item)}\n\n" +
            "$CHOICE_DELETE\n${choiceDeleteDetail(item.delete, onThisPhone = item.entryId != null)}"

    /**
     * Under "Delete match everywhere" in the choice sheet: the same promise as
     * [deleteMessage], as a statement rather than a question.
     */
    fun choiceDeleteDetail(delete: MatchDelete, onThisPhone: Boolean): String =
        "Deletes ${deletedThings(delete, onThisPhone, removeVerb = "removes")}. This can't be undone."

    /**
     * The single delete confirmation's message. Each kind says exactly what goes:
     * a scored match's points, a filmed match's clips, both for a bound one.
     *
     * [onThisPhone]: the row also has the video in this app, and deleting the
     * match discards that too (and whatever analysis the phone holds for it), so
     * the message has to say so. "From this app", not "from this phone": on
     * Android the original stays in the gallery, which the app only points at.
     * Without the file the wording is exactly what each dialog said before the
     * list was one list.
     */
    fun deleteMessage(delete: MatchDelete, onThisPhone: Boolean): String? = when (delete) {
        MatchDelete.NONE -> null
        MatchDelete.LEAVE_SHARE -> "Remove this shared match from your list? You'll need the owner to share it again."
        else -> "Delete ${deletedThings(delete, onThisPhone, removeVerb = "remove")}? This can't be undone."
    }

    private fun deletedThings(delete: MatchDelete, onThisPhone: Boolean, removeVerb: String): String {
        val match = when (delete) {
            MatchDelete.SCORE_MATCH -> listOf("this match", "every point you scored")
            MatchDelete.BOUND_MATCH -> listOf("this match", "every point you scored", "all its rally clips")
            else -> listOf("this match", "all its rally clips")
        }
        return if (onThisPhone) "${listed(match)}, and $removeVerb its video and analysis from this app" else listed(match)
    }

    /** "a", "a and b", "a, b and c". */
    private fun listed(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()
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
