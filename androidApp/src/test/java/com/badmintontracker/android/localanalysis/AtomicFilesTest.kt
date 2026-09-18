package com.badmintontracker.android.localanalysis

import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class AtomicFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Hands over [good] bytes and then fails, like a copy cut off by a full disk. */
    private fun brokenAfter(good: Int) = object : InputStream() {
        private var served = 0
        override fun read(): Int = if (served++ < good) 1 else throw IOException("disk full")
    }

    @Test
    fun a_write_that_fails_halfway_leaves_the_old_file_exactly_as_it_was() {
        val file = File(temp.root, "e1.track").apply { writeText("the last good track") }
        runCatching {
            file.writeAtomically { out -> out.write("half a tr".toByteArray()); throw IOException("killed") }
        }
        file.readText() shouldBe "the last good track"
        temp.root.list()!!.toList() shouldBe listOf("e1.track")
    }

    @Test
    fun a_write_that_completes_replaces_the_file() {
        val file = File(temp.root, "nested/e1.track")
        file.writeAtomically { it.write("new".toByteArray()) }
        file.readText() shouldBe "new"
        file.parentFile!!.list()!!.toList() shouldBe listOf("e1.track")
    }

    @Test
    fun a_copy_cut_short_is_not_mistaken_for_the_video() {
        // materialise reused any file with a length above zero. A copy of a 2GB
        // match killed partway left exactly that, and every later run analysed
        // the first part of the match without complaint.
        val out = File(temp.root, "local-sources/e1.mp4")
        runCatching { out.copyAtomicallyFrom(brokenAfter(good = 1000)) }
        out.exists() shouldBe false

        out.copyAtomicallyFrom(ByteArrayInputStream(ByteArray(5000) { 7 }))
        out.length() shouldBe 5000L
    }
}
