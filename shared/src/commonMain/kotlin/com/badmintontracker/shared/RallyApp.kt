package com.badmintontracker.shared

import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AnnotationsRepositoryImpl
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.AuthRepositoryImpl
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.ClipsRepositoryImpl
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.MediaRepositoryImpl
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.SharesRepositoryImpl
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.repo.VideosRepositoryImpl
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.HttpClientEngine

class RallyApp(
    config: SupabaseConfig,
    settings: Settings,
    httpEngine: HttpClientEngine? = null,
) {
    val client: SupabaseClient = buildSupabaseClient(config, settings, httpEngine)
    val auth:        AuthRepository        = AuthRepositoryImpl(client)
    val clips:       ClipsRepository       = ClipsRepositoryImpl(client)
    val annotations: AnnotationsRepository = AnnotationsRepositoryImpl(client)
    val media:       MediaRepository       = MediaRepositoryImpl(client)
    val shares:      SharesRepository      = SharesRepositoryImpl(client)
    val videos:      VideosRepository      = VideosRepositoryImpl(client)
}
