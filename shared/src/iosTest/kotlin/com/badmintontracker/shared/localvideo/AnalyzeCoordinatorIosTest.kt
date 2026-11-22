package com.badmintontracker.shared.localvideo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyzeCoordinatorIosTest {

    private val docs = "/var/mobile/Containers/Data/Application/TEST/Documents"

    @Test
    fun resolves_relative_paths_within_documents() {
        assertEquals("$docs/match.mp4", resolveDocumentsVideoPath(docs, "match.mp4"))
        assertEquals("$docs/videos/2026/match.mp4", resolveDocumentsVideoPath("$docs/", "videos/2026/match.mp4"))
    }

    @Test
    fun rejects_blank_absolute_and_traversal_paths() {
        val badUris = listOf(
            "",
            "   ",
            "/etc/passwd",
            "$docs/match.mp4",
            "..",
            "../secret.mp4",
            "videos/../../secret.mp4",
            ".",
            "./",
            "bad\u0000.mp4",
        )
        for (uri in badUris) {
            assertFailsWith<IllegalArgumentException>("expected '$uri' to be rejected") {
                resolveDocumentsVideoPath(docs, uri)
            }
        }
    }
}
