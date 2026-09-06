package com.badmintontracker.analysis.result

import com.badmintontracker.analysis.corpus.corpusIsRequired
import com.badmintontracker.analysis.corpus.readFixtureFileOrNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/**
 * The direction that actually bites.
 *
 * AnalysisResultTest runs the schema the easy way: this writer out, this
 * reader back. But the consumers are the web app and fetch_corpus.py, not this
 * module, and a field omitted from AnalysisResult round-trips perfectly
 * through a writer that never emits it. Only a real capture can show what is
 * missing.
 *
 * BLOCKED: needs a corpus. Skips loudly until tools/corpus/fetch_corpus.py has
 * been run; ANALYSIS_REQUIRE_CORPUS=1 makes the skip a failure.
 */
class AnalysisResultCorpusTest {

    @Test
    fun re_serialising_a_real_capture_drops_no_top_level_field() {
        val text = readFixtureFileOrNull("sample", "results.json")
        if (text == null) {
            val message =
                "SKIPPED: corpus fixture 'corpus/sample' is not present, so the " +
                    "results.json schema was not checked against real cloud output. " +
                    "Capture one with tools/corpus/fetch_corpus.py."
            if (corpusIsRequired()) error(message)
            println(message)
            return
        }

        val original = Json.parseToJsonElement(text).jsonObject
        val reSerialised = Json.parseToJsonElement(AnalysisResult.fromJson(text).toJson()).jsonObject

        // Added by trim_corpus.py, never written by the cloud.
        val fixtureOnly = setOf("fusion_shuttle_track", "trimmed_window")

        // Written only by Phase 2. AnalysisResult models the PHASE 1 contract,
        // which modal_supabase_processor.py:4189-4200 fixes at seven keys, and
        // a `completed` capture is a superset of it. Not modelling these is a
        // scope decision, not an omission - but it is the reason a capture
        // cannot be round-tripped byte for byte, and Stage 3 has to model them
        // before it can write a Phase 2 results.json.
        val phase2Only = setOf(
            "video_id", "duration", "processed_frames", "video_width",
            "video_height", "shuttle", "court_detection", "player_zone_analytics",
        )

        // Whatever is left is a PHASE 1 field this type drops. Reported as the
        // set rather than a boolean so a failure names what to add.
        (original.keys - reSerialised.keys - fixtureOnly - phase2Only) shouldBe emptySet()
    }
}
