package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class MediaRepositoryTest {
    private fun fakeClip(
        clipPath: String = "uid/video/clip-7.mp4",
        thumbPath: String? = "uid/video/clip-7.jpg",
    ) = RallyClip(
        id = "c1", videoId = "v1", rallyIndex = 7,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = clipPath, thumbnailStoragePath = thumbPath,
        title = null, annotationCount = 0,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    @Test
    fun signedClipUrl_calls_storage_sign_endpoint() = runTest {
        var called: String? = null
        val client = TestSupabase.client { request ->
            called = "${request.method.value} ${request.url.encodedPath}"
            jsonResponse("""{"signedURL":"/storage/v1/object/sign/clips/uid/video/clip-7.mp4?token=abc"}""")
        }
        val repo = MediaRepositoryImpl(client)

        val url = repo.signedClipUrl(fakeClip())

        called!!.shouldContain("/storage/v1/object/sign/clips/uid/video/clip-7.mp4")
        url.shouldContain("token=abc")
    }

    @Test
    fun signedThumbnailUrl_returns_null_when_no_thumbnail() = runTest {
        val client = TestSupabase.client { _ ->
            respond("", HttpStatusCode.OK)
        }
        val repo = MediaRepositoryImpl(client)
        repo.signedThumbnailUrl(fakeClip(thumbPath = null)) shouldBe null
    }
}
