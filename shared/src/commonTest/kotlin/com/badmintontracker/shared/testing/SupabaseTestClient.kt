package com.badmintontracker.shared.testing

import com.badmintontracker.shared.SupabaseConfig
import com.badmintontracker.shared.buildSupabaseClient
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

object TestSupabase {
    val config = SupabaseConfig(
        url = "https://test.supabase.co",
        anonKey = "test-anon-key",
    )

    fun client(
        settings: Settings = MapSettings(),
        handler: MockRequestHandler,
    ): SupabaseClient = buildSupabaseClient(
        config = config,
        settings = settings,
        httpEngine = MockEngine { request -> handler(request) },
    )
}

fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = ByteReadChannel(body),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)
