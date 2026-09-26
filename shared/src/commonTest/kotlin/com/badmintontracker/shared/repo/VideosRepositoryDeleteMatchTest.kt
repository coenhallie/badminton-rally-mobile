package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class VideosRepositoryDeleteMatchTest {

    private val clipRows = """
      [
        {"clip_storage_path":"u1/v1/c0.mp4","thumbnail_storage_path":"u1/v1/c0.jpg"},
        {"clip_storage_path":"u1/v1/c1.mp4","thumbnail_storage_path":null}
      ]
    """.trimIndent()

    private fun handler(calls: MutableList<String>, storageStatus: HttpStatusCode = HttpStatusCode.OK) =
        TestSupabase.client { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            when {
                path.contains("/rest/v1/rpc/delete_match") -> jsonResponse("null")
                path.contains("/rest/v1/rally_clips")      -> jsonResponse(clipRows)
                path.contains("/rest/v1/videos")           -> jsonResponse("""[{"storage_path":"u1/v1.mp4"}]""")
                path.contains("/storage/v1/object/")       -> jsonResponse("[]", storageStatus)
                else                                       -> jsonResponse("[]")
            }
        }

    @Test
    fun deleteMatch_deletes_storage_objects_then_calls_rpc() = runTest {
        val calls = mutableListOf<String>()
        val repo = VideosRepositoryImpl(handler(calls))

        repo.deleteMatch("v1").isSuccess shouldBe true

        val rpcIndex = calls.indexOfFirst { it.contains("rpc/delete_match") }
        rpcIndex shouldBeGreaterThanOrEqual 0
        // clips, thumbnails, and original video buckets each got a delete...
        calls.count { it.startsWith("DELETE /storage/v1/object/") } shouldBe 3
        // ...and every storage delete happened before the row-deleting RPC.
        calls.forEachIndexed { i, call ->
            if (call.startsWith("DELETE /storage/v1/object/")) (i < rpcIndex) shouldBe true
        }
    }

    @Test
    fun deleteMatch_storage_failure_is_swallowed_and_rpc_still_runs() = runTest {
        val calls = mutableListOf<String>()
        val repo = VideosRepositoryImpl(handler(calls, storageStatus = HttpStatusCode.InternalServerError))

        repo.deleteMatch("v1").isSuccess shouldBe true

        calls.any { it.contains("rpc/delete_match") } shouldBe true
    }

    @Test
    fun deleteMatch_rpc_failure_returns_failure() = runTest {
        val client = TestSupabase.client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/rest/v1/rpc/delete_match") ->
                    jsonResponse("""{"message":"not_owner"}""", HttpStatusCode.Forbidden)
                path.contains("/rest/v1/rally_clips") -> jsonResponse("[]")
                path.contains("/rest/v1/videos")      -> jsonResponse("[]")
                else                                  -> jsonResponse("[]")
            }
        }
        val repo = VideosRepositoryImpl(client)

        repo.deleteMatch("v1").isFailure shouldBe true
    }

    @Test
    fun deleteMatch_removes_what_the_analysis_left_in_the_results_bucket() = runTest {
        // The delete was written before the cloud kept anything there. Phase 2
        // now leaves a pose artifact of tens of megabytes under
        // results/{owner}/{video}/, holding every frame of the players' bodies,
        // and once the videos row is gone nothing points at it any more.
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = TestSupabase.client { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            bodies += (request.body as? TextContent)?.text.orEmpty()
            when {
                path.contains("/rest/v1/rpc/delete_match") -> jsonResponse("null")
                path.contains("/rest/v1/rally_clips")      -> jsonResponse(clipRows)
                path.contains("/rest/v1/videos")           -> jsonResponse("""[{"storage_path":"u1/v1.mp4"}]""")
                path.endsWith("/storage/v1/object/list/results") -> jsonResponse(
                    """[{"name":"poses.raw","id":"1","updated_at":null,"created_at":null,
                         "last_accessed_at":null,"metadata":null},
                        {"name":"results.json","id":"2","updated_at":null,"created_at":null,
                         "last_accessed_at":null,"metadata":null}]""",
                )
                else -> jsonResponse("[]")
            }
        }

        VideosRepositoryImpl(client).deleteMatch("v1").isSuccess shouldBe true

        val listed = calls.indexOf("POST /storage/v1/object/list/results")
        val deleted = calls.indexOf("DELETE /storage/v1/object/results")
        val rpc = calls.indexOfFirst { it.contains("rpc/delete_match") }
        bodies[listed].shouldContain("\"prefix\":\"u1/v1\"")
        bodies[deleted].shouldContain("u1/v1/poses.raw")
        bodies[deleted].shouldContain("u1/v1/results.json")
        (deleted in 0 until rpc) shouldBe true
    }

    @Test
    fun deleteMatch_with_nothing_in_the_results_bucket_still_deletes_the_match() = runTest {
        val calls = mutableListOf<String>()
        val repo = VideosRepositoryImpl(handler(calls))

        repo.deleteMatch("v1").isSuccess shouldBe true

        calls.any { it == "DELETE /storage/v1/object/results" } shouldBe false
        calls.any { it.contains("rpc/delete_match") } shouldBe true
    }
}
