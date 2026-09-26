package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinishedRunMessageTest {
    private fun item(key: String, title: String, entryId: String? = null, videoId: String? = null) =
        MatchListItem(
            key = key, sortAtEpochMs = 0, title = title, detail = "", playersLine = null, sharedBy = null,
            where = null, analyses = emptyList(), statusText = null, statusIsError = false,
            action = com.badmintontracker.shared.matchlist.MatchAction.NONE,
            entryId = entryId, videoId = videoId, scoreLogId = null, localUri = null, coverClip = null,
            scoreText = null, isOwned = true, canShare = false, canEditDetails = false, canAnalyzeAgain = false,
            canRemoveFromPhone = false, delete = com.badmintontracker.shared.matchlist.MatchDelete.NONE,
            opensLocalPlayer = false, deviceClipCount = 0, heatmapId = null, accessibilityStatus = "",
        )

    private val thu = item("video-e1", "Thu League", entryId = "e1")
    private val sat = item("video-v2", "Sat Cup", videoId = "v2")
    private val list = MatchList(mine = listOf(thu, sat), shared = emptyList())

    @Test fun one_run_names_its_match_and_offers_it() {
        assertEquals(FinishedRunMessage("Thu League is analyzed", thu), finishedRunMessage(setOf("e1"), list))
    }

    @Test fun a_cloud_run_is_found_by_its_video() {
        assertEquals(sat, finishedRunMessage(setOf("v2"), list)?.item)
    }

    @Test fun several_runs_are_counted_and_offer_nothing_to_open() {
        assertEquals(FinishedRunMessage("2 matches are analyzed", null), finishedRunMessage(setOf("e1", "v2"), list))
    }

    @Test fun a_run_whose_match_is_gone_says_nothing() {
        assertNull(finishedRunMessage(setOf("gone"), list))
    }
}
