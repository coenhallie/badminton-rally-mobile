package com.badmintontracker.shared.matchlist

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MatchListCopyTest {

    @Test
    fun a_match_with_no_video_and_a_score_reads_as_one_sentence() {
        statusDescription(null, listOf(MatchAnalysis.SCORE), null) shouldBe "No video. Score."
    }

    @Test
    fun a_running_status_keeps_its_ellipsis_rather_than_gaining_a_full_stop() {
        statusDescription(MatchWhere.PHONE, emptyList(), "Analyzing on device…") shouldBe
            "On this phone. Analyzing on device…"
    }

    @Test
    fun every_string_the_copy_object_can_produce_avoids_the_em_dash() {
        val fixed = listOf(
            MatchListCopy.NOT_ANALYZED, MatchListCopy.ANALYZED, MatchListCopy.FILTER_ALL,
            MatchListCopy.FILTER_ON_THIS_PHONE, MatchListCopy.FILTER_NOT_ANALYZED,
            MatchListCopy.EMPTY_ON_THIS_PHONE, MatchListCopy.EMPTY_NOT_ANALYZED,
            MatchListCopy.SHARED_SECTION, MatchListCopy.LEGEND_BUTTON, MatchListCopy.LEGEND_TITLE,
            MatchListCopy.WHERE_PHONE, MatchListCopy.WHERE_CLOUD, MatchListCopy.WHERE_BOTH, MatchListCopy.WHERE_NONE,
            MatchListCopy.ANALYSIS_RALLIES, MatchListCopy.ANALYSIS_HEATMAP,
            MatchListCopy.ANALYSIS_SKELETON, MatchListCopy.ANALYSIS_SCORE,
            MatchListCopy.MENU_SHARE, MatchListCopy.MENU_EDIT_DETAILS, MatchListCopy.MENU_ANALYZE_AGAIN,
            MatchListCopy.MENU_HEATMAP, MatchListCopy.MENU_REMOVE_FROM_PHONE, MatchListCopy.MENU_DELETE,
            MatchListCopy.MENU_LEAVE_SHARE, MatchListCopy.SWIPE_DELETE, MatchListCopy.SWIPE_REMOVE,
            MatchListCopy.CHOICE_REMOVE, MatchListCopy.CHOICE_DELETE, MatchListCopy.CHOICE_CANCEL,
        )
        val produced = listOf(
            MatchListCopy.menuDeviceClips(0), MatchListCopy.menuDeviceClips(7),
            MatchListCopy.choiceRemoveDetail(MatchDelete.SCORE_MATCH),
            MatchListCopy.choiceRemoveDetail(MatchDelete.VIDEO_MATCH),
            MatchListCopy.choiceRemoveDetail(MatchDelete.BOUND_MATCH),
        ) + listOf(true, false).flatMap { onThisPhone ->
            MatchDelete.entries.flatMap { kind ->
                listOfNotNull(
                    MatchListCopy.choiceDeleteDetail(kind, onThisPhone),
                    MatchListCopy.deleteMessage(kind, onThisPhone),
                )
            }
        }
        val legend = MatchListCopy.LEGEND.flatMap { (label, line) -> listOf(label, line) }
        (fixed + produced + legend).forEach { it.containsEmDash() shouldBe false }
    }

    @Test
    fun without_a_file_on_this_phone_each_delete_says_what_it_said_before() {
        MatchListCopy.deleteMessage(MatchDelete.VIDEO_MATCH, onThisPhone = false) shouldBe
            "Delete this match and all its rally clips? This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.SCORE_MATCH, onThisPhone = false) shouldBe
            "Delete this match and every point you scored? This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.BOUND_MATCH, onThisPhone = false) shouldBe
            "Delete this match, every point you scored and all its rally clips? This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.LEAVE_SHARE, onThisPhone = false) shouldBe
            "Remove this shared match from your list? You'll need the owner to share it again."
        MatchListCopy.deleteMessage(MatchDelete.NONE, onThisPhone = false) shouldBe null
        MatchListCopy.choiceDeleteDetail(MatchDelete.VIDEO_MATCH, onThisPhone = false) shouldBe
            "Deletes this match and all its rally clips. This can't be undone."
        MatchListCopy.choiceDeleteDetail(MatchDelete.BOUND_MATCH, onThisPhone = false) shouldBe
            "Deletes this match, every point you scored and all its rally clips. This can't be undone."
    }

    /**
     * Deleting a match that is also on this phone discards the video and the
     * phone's analysis with it, so the confirmation has to say so. "This app",
     * because on Android the original stays in the gallery.
     */
    @Test
    fun with_a_file_on_this_phone_the_delete_says_the_video_goes_too() {
        MatchListCopy.deleteMessage(MatchDelete.VIDEO_MATCH, onThisPhone = true) shouldBe
            "Delete this match and all its rally clips, and remove its video and analysis from this app? " +
            "This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.BOUND_MATCH, onThisPhone = true) shouldBe
            "Delete this match, every point you scored and all its rally clips, and remove its video and " +
            "analysis from this app? This can't be undone."
        MatchListCopy.choiceDeleteDetail(MatchDelete.SCORE_MATCH, onThisPhone = true) shouldBe
            "Deletes this match and every point you scored, and removes its video and analysis from this app. " +
            "This can't be undone."
    }

    // Built from a code point rather than a literal, so this file itself never
    // carries an em dash to trip up the same check run over the repo's own source.
    private fun String.containsEmDash(): Boolean = contains(Char(0x2014))
}
