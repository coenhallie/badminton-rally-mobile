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

        // Any key the cloud writes and this type cannot reproduce is a field
        // AnalysisResult is missing. Reported as the set, not as a boolean, so
        // a failure names what to add.
        (original.keys - reSerialised.keys) shouldBe emptySet()
    }
}
