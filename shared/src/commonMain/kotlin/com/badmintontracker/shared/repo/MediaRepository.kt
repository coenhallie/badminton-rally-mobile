package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.RallyClip
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
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
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
    }

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Pair<String, Instant>>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<String?>>()

    suspend fun get(path: String, sign: suspend (String) -> String?): String? {
        var ownedDeferred: CompletableDeferred<String?>? = null
        val existingDeferred: CompletableDeferred<String?>? = lock.withLock {
            val currentTime = now()
            entries[path]?.let { (url, at) ->
                if (currentTime - at < ttl) return url
                entries.remove(path)
            }
            inFlight[path] ?: CompletableDeferred<String?>().also {
                inFlight[path] = it
                ownedDeferred = it
            }.let { null }
        }
        if (existingDeferred != null) return existingDeferred.await()

        val deferred = ownedDeferred!!
        val url = try {
            sign(path)
        } catch (t: Throwable) {
            lock.withLock {
                if (inFlight[path] === deferred) inFlight.remove(path)
            }
            deferred.completeExceptionally(t)
            throw t
        }
        lock.withLock {
            val stillCurrent = if (inFlight[path] === deferred) {
                inFlight.remove(path)
                true
            } else {
                false
            }
            if (stillCurrent && url != null) {
                putLocked(path, url, now())
            }
        }
        deferred.complete(url)
        return url
    }

    /** Drops all cached signed URLs and detaches any in-flight requests (e.g. on sign-out). */
    suspend fun clear() {
        lock.withLock {
            entries.clear()
            inFlight.clear()
        }
    }

    private fun putLocked(path: String, url: String, at: Instant) {
        entries.remove(path)
        if (entries.size >= maxEntries) {
            val expired = entries.entries.filter { (_, value) -> at - value.second >= ttl }.map { it.key }
            expired.forEach { entries.remove(it) }
            while (entries.size >= maxEntries) {
                val oldestKey = entries.keys.firstOrNull() ?: break
                entries.remove(oldestKey)
            }
        }
        entries[path] = url to at
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
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

    /** Clears cached signed thumbnail URLs (e.g. on sign-out or account switch). */
    suspend fun clear() {
        thumbnails.clear()
    }

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
