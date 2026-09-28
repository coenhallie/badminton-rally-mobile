package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.RallyClip
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/**
 * Signed thumbnail URLs, reused while their signature holds.
 *
 * A signed URL is new on every call, and a new URL is a cache miss for every
 * image loader downstream. The match list draws a cover for every cloud match
 * each time its list comes on screen, so without this it would re-sign and
 * re-download all of them.
 * 50 minutes against the one-hour signature leaves a margin for a slow load.
 */
class ThumbnailUrlCache(
    private val ttl: Duration = 50.minutes,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Pair<String, Instant>>()

    suspend fun get(path: String, sign: suspend (String) -> String?): String? {
        lock.withLock { entries[path]?.let { (url, at) -> if (now() - at < ttl) return url } }
        val url = sign(path) ?: return null
        lock.withLock { entries[path] = url to now() }
        return url
    }
}

interface MediaRepository {
    /**
     * Throws when the request fails. Declared, because Swift can only catch what
     * is: an undeclared exception out of a suspend function terminates the iOS
     * app instead of reaching the `catch` beside the call.
     */
    @Throws(Exception::class)
    suspend fun signedClipUrl(clip: RallyClip): String
    suspend fun signedThumbnailUrl(clip: RallyClip): String?
}

class MediaRepositoryImpl(private val client: SupabaseClient) : MediaRepository {

    private val thumbnails = ThumbnailUrlCache()

    override suspend fun signedClipUrl(clip: RallyClip): String =
        client.storage.from("clips").createSignedUrl(clip.clipStoragePath, 1.hours)

    override suspend fun signedThumbnailUrl(clip: RallyClip): String? {
        val path = clip.thumbnailStoragePath ?: return null
        return thumbnails.get(path) { p ->
            // Null (the placeholder) on any failure, a missing object included:
            // a thumbnail is decorative, and an exception must never propagate
            // out of here. This function is not @Throws, so on iOS an escaping
            // exception aborts the app through the Swift bridge.
            runCatching { client.storage.from("thumbnails").createSignedUrl(p, 1.hours) }.getOrNull()
        }
    }
}
