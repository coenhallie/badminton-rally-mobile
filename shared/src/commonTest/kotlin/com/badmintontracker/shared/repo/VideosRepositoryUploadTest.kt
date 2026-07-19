@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.badmintontracker.shared.repo

import app.cash.turbine.test
import com.badmintontracker.shared.testing.TestSupabase
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.storage.resumable.Fingerprint
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.jan.supabase.storage.resumable.ResumableCacheEntry
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private const val UID = "user-1"
private const val VIDEO_ID = "vid-1"
private val BYTES = ByteArray(4) { it.toByte() }
private const val TUS_SESSION_URL = "https://test.supabase.co/storage/v1/upload/resumable/session-1"

private suspend fun SupabaseClient.signInAs(uid: String) {
    auth.importSession(
        UserSession(
            accessToken = "test-token",
            refreshToken = "test-refresh",
            expiresIn = 3_600,
            tokenType = "Bearer",
            user = UserInfo(aud = "authenticated", id = uid),
        ),
        autoRefresh = false,
    )
}

/**
 * Mocks the two TUS calls a happy-path upload makes: POST creates the session
 * (201 + Location), PATCH uploads the single chunk (204 + Upload-Offset).
 */
private fun tusHandler(onCreate: (HttpRequestData) -> Unit = {}): suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData = { request ->
    when {
        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/upload/resumable") -> {
            onCreate(request)
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.Created,
                headers = headersOf("Location", TUS_SESSION_URL),
            )
        }
        request.method == HttpMethod.Patch -> respond(
            content = ByteReadChannel(""),
            status = HttpStatusCode.NoContent,
            headers = headersOf("Upload-Offset", BYTES.size.toString()),
        )
        else -> error("Unexpected request: ${request.method} ${request.url}")
    }
}

class VideosRepositoryUploadTest {

    @Test
    fun upload_recovers_from_poisoned_expired_tus_cache_entry() = runTest {
        // Older builds cached the TUS content type as the literal string "null"
        // (supabase-kt stores contentType.toString()). Resuming such an entry after
        // its 1-day expiry throws BadContentTypeFormatException("Bad Content-Type
        // format: null") — the exact failure behind the re-analyze bug.
        val cache = MemoryResumableCache()
        val client = TestSupabase.client(resumableCache = cache, handler = tusHandler())
        client.signInAs(UID)
        cache.set(
            Fingerprint(VIDEO_ID, BYTES.size.toLong()),
            ResumableCacheEntry(
                url = TUS_SESSION_URL,
                path = "$UID/$VIDEO_ID.mp4",
                bucketId = "videos",
                expiresAt = Clock.System.now() - 1.days,
                upsert = false,
                contentType = "null",
            ),
        )
        val repo = VideosRepositoryImpl(client)
        val states = repo.uploadVideo(VIDEO_ID, BYTES.size.toLong()) { ByteReadChannel(BYTES) }.toList()
        states.last() shouldBe UploadState.Done
    }

    @Test
    fun upload_creates_tus_session_with_upsert() = runTest {
        // Re-analyze re-uploads to the same storage path; without x-upsert the
        // create call 409s ("Specified path already exists") once the first
        // upload completed.
        var createHeaders: Headers? = null
        val client = TestSupabase.client(
            resumableCache = MemoryResumableCache(),
            handler = tusHandler(onCreate = { createHeaders = it.headers }),
        )
        client.signInAs(UID)
        val repo = VideosRepositoryImpl(client)
        val states = repo.uploadVideo(VIDEO_ID, BYTES.size.toLong()) { ByteReadChannel(BYTES) }.toList()
        states.last() shouldBe UploadState.Done
        createHeaders.shouldNotBeNull()["x-upsert"] shouldBe "true"
    }

    @Test
    fun resume_of_an_already_complete_upload_reports_done() = runTest {
        // Crash window: the app died after the last chunk uploaded but before the
        // TUS cache entry was removed. Resuming within the entry's 1-day lifetime
        // makes supabase-kt throw IllegalStateException("File already uploaded")
        // — but the file IS in storage, so the upload must report Done, not fail
        // every retry until the entry expires.
        val cache = MemoryResumableCache()
        val client = TestSupabase.client(resumableCache = cache) { request ->
            when {
                // TUS HEAD: server already holds every byte.
                request.method == HttpMethod.Head -> respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Upload-Offset", BYTES.size.toString()),
                )
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        }
        client.signInAs(UID)
        cache.set(
            Fingerprint(VIDEO_ID, BYTES.size.toLong()),
            ResumableCacheEntry(
                url = TUS_SESSION_URL,
                path = "$UID/$VIDEO_ID.mp4",
                bucketId = "videos",
                expiresAt = Clock.System.now() + 1.days,
                upsert = true,
                contentType = "video/mp4",
            ),
        )
        val repo = VideosRepositoryImpl(client)
        val states = repo.uploadVideo(VIDEO_ID, BYTES.size.toLong()) { ByteReadChannel(BYTES) }.toList()
        states.last() shouldBe UploadState.Done
    }

    @Test
    fun upload_caches_real_content_type_for_future_resumes() = runTest {
        // The cache entry must hold a parseable content type, not "null" — it is
        // fed back through ContentType.parse() when an expired entry is resumed.
        val cache = MemoryResumableCache()
        val client = TestSupabase.client(resumableCache = cache) { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/upload/resumable") ->
                    respond(
                        content = ByteReadChannel(""),
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location", TUS_SESSION_URL),
                    )
                // Park the chunk upload forever so the cache entry (removed on
                // completion) stays observable.
                else -> awaitCancellation()
            }
        }
        client.signInAs(UID)
        val repo = VideosRepositoryImpl(client)
        repo.uploadVideo(VIDEO_ID, BYTES.size.toLong()) { ByteReadChannel(BYTES) }.test {
            awaitItem().shouldBeInstanceOf<UploadState.InProgress>()
            val entry = cache.get(Fingerprint(VIDEO_ID, BYTES.size.toLong())).shouldNotBeNull()
            entry.contentType shouldContain "video/mp4"
            cancelAndIgnoreRemainingEvents()
        }
    }
}
