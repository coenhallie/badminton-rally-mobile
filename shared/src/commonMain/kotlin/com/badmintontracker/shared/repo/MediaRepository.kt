package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.RallyClip
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlin.time.Duration.Companion.hours

interface MediaRepository {
    suspend fun signedClipUrl(clip: RallyClip): String
    suspend fun signedThumbnailUrl(clip: RallyClip): String?
}

class MediaRepositoryImpl(private val client: SupabaseClient) : MediaRepository {

    override suspend fun signedClipUrl(clip: RallyClip): String =
        client.storage.from("clips").createSignedUrl(clip.clipStoragePath, 1.hours)

    override suspend fun signedThumbnailUrl(clip: RallyClip): String? {
        val path = clip.thumbnailStoragePath ?: return null
        // Null (placeholder) on failure: thumbnails are decorative, and a missing
        // object must never propagate — on iOS an escaping exception here aborts
        // the whole app through the Swift interop bridge.
        return runCatching { client.storage.from("thumbnails").createSignedUrl(path, 1.hours) }
            .getOrNull()
    }
}
