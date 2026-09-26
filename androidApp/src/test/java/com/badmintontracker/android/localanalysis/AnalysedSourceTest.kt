package com.badmintontracker.android.localanalysis

import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which handle the skeleton is played from.
 *
 * A skeleton is drawn on the frames of one particular file, so this is not
 * "find any copy of the video" - it is "find the one that was analysed". The
 * cloud case is the one that was missing: a cloud run never copies the footage
 * (it streams the upload straight from the gallery grant), so the entry's
 * `content://` URI is the ONLY handle it leaves behind, and refusing it told a
 * coach the video was "no longer on this phone" while it sat in their gallery.
 */
class AnalysedSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String) = temp.newFile(name).apply { writeText("mp4") }

    @Test
    fun `a cloud run is played from the gallery grant it was uploaded from`() {
        // The defect. A cloud analysis copies nothing to app storage, so both
        // file candidates are absent and the content URI is all there is.
        analysedSourceUri(
            entryUri = "content://media/external/video/media/1234",
            direct = null,
            copy = null,
            contentReadable = true,
        ) shouldBe "content://media/external/video/media/1234"
    }

    @Test
    fun `a revoked grant is still no video`() {
        // The case the old message was written for, and the only one where it
        // is true: the picker's grant is gone and nothing else holds the file.
        analysedSourceUri(
            entryUri = "content://media/external/video/media/1234",
            direct = null,
            copy = null,
            contentReadable = false,
        ) shouldBe null
    }

    @Test
    fun `a device run prefers the file it actually decoded`() {
        // materialise() copies a gallery pick into local-sources and the device
        // analysis decodes THAT. Another copy could be re-encoded and would not
        // share the analysed frames.
        val copied = file("copy.mp4")

        analysedSourceUri(
            entryUri = "content://media/external/video/media/1234",
            direct = null,
            copy = copied,
            contentReadable = true,
        ) shouldBe copied.toURI().toString()
    }

    @Test
    fun `a plain file entry is played directly`() {
        val direct = file("direct.mp4")

        analysedSourceUri(
            entryUri = "file://${direct.path}",
            direct = direct,
            copy = null,
            contentReadable = false,
        ) shouldBe direct.toURI().toString()
    }

    @Test
    fun `the direct file wins over a copy, matching what materialise returned`() {
        // materialise() returns the direct file when it is readable and only
        // copies otherwise, so that is the file the analysis decoded.
        val direct = file("direct.mp4")
        val copied = file("copy.mp4")

        analysedSourceUri(
            entryUri = "file://${direct.path}",
            direct = direct,
            copy = copied,
            contentReadable = false,
        ) shouldBe direct.toURI().toString()
    }
}
