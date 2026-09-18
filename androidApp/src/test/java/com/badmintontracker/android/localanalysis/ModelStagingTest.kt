package com.badmintontracker.android.localanalysis

import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class ModelStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bytes(b: Int, n: Int = 64) = ByteArrayInputStream(ByteArray(n) { b.toByte() })

    @Test
    fun a_model_is_extracted_once_and_then_reused() {
        var opened = 0
        val first = stageModel(temp.root, "v1", "tracknet.onnx") { opened++; bytes(1) }
        val second = stageModel(temp.root, "v1", "tracknet.onnx") { opened++; bytes(1) }
        second shouldBe first
        opened shouldBe 1
    }

    @Test
    fun an_app_update_replaces_the_graph_even_under_the_same_file_name() {
        // Reuse was keyed on the file name alone. New weights shipped as
        // tracknet.fp16.onnx never reached a phone that had the old ones, while
        // MODEL_VERSION reported the new ones.
        stageModel(temp.root, "v1", "tracknet.onnx") { bytes(1) }
        val updated = stageModel(temp.root, "v2", "tracknet.onnx") { bytes(2) }
        updated.readBytes().first() shouldBe 2.toByte()
    }

    @Test
    fun the_previous_versions_graphs_are_not_left_on_the_phone() {
        val old = stageModel(temp.root, "v1", "tracknet.onnx") { bytes(1) }
        stageModel(temp.root, "v2", "tracknet.onnx") { bytes(2) }
        old.exists() shouldBe false
    }

    @Test
    fun an_extraction_cut_short_is_done_again_rather_than_loaded() {
        // Any file with a length above zero was taken as the model. One
        // truncated by a kill or a full disk then failed at session creation on
        // every run until the app's data was cleared.
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int = if (served++ < 10) 1 else throw IOException("disk full")
        }
        runCatching { stageModel(temp.root, "v1", "tracknet.onnx") { failing } }
        val staged = stageModel(temp.root, "v1", "tracknet.onnx") { bytes(3, n = 64) }
        staged.length() shouldBe 64L
    }
}
