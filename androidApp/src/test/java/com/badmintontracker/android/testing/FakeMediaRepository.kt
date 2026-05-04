package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

class FakeMediaRepository : MediaRepository {
    var nextClipUrl: () -> String = { "https://signed/${System.nanoTime()}.mp4" }
    var nextClipUrlError: Throwable? = null
    var nextThumbUrl: String? = "https://signed/thumb.jpg"
    val clipUrlCalls = mutableListOf<RallyClip>()

    override suspend fun signedClipUrl(clip: RallyClip): String {
        clipUrlCalls += clip
        nextClipUrlError?.let { throw it }
        return nextClipUrl()
    }
    override suspend fun signedThumbnailUrl(clip: RallyClip): String? = nextThumbUrl
}
