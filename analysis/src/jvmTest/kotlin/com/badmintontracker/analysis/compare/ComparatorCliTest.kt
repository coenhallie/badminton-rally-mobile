package com.badmintontracker.analysis.compare

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * The CLI's failure paths, which are the only branches in it.
 *
 * They matter more than they look: a results.json that silently read as empty
 * would report total disagreement, and total disagreement is exactly what a
 * broken port looks like. Failing loudly is the difference between "the CLI
 * could not read your file" and a day spent debugging a port that was fine.
 */
class ComparatorCliTest {

    @Test
    fun a_missing_file_is_null_rather_than_an_empty_result() {
        ComparatorCli.readResult("/no/such/results.json") shouldBe null
    }

    @Test
    fun a_directory_is_null() {
        ComparatorCli.readResult(System.getProperty("java.io.tmpdir")) shouldBe null
    }

    @Test
    fun malformed_json_is_null_rather_than_an_empty_result() {
        val f = File.createTempFile("bad", ".json").apply { writeText("{\"phase\":") }
        try {
            ComparatorCli.readResult(f.path) shouldBe null
        } finally {
            f.delete()
        }
    }

    @Test
    fun a_well_formed_capture_parses() {
        val f = File.createTempFile("good", ".json").apply {
            writeText(
                """{"phase":"phase1","pipeline_variant":"legacy","rallies":[],""" +
                    """"shuttle_positions":{},"fps":30.0,"total_frames":10,""" +
                    """"video_metadata":{"duration_seconds":1.0,"filename":"m.mp4"}}"""
            )
        }
        try {
            ComparatorCli.readResult(f.path)?.totalFrames shouldBe 10
        } finally {
            f.delete()
        }
    }
}
