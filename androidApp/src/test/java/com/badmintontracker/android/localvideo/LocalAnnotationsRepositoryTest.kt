package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.AnnotationKind
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalAnnotationsRepositoryTest {

    @Test
    fun add_stores_and_hasAnnotations_reflects_it() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.hasAnnotations("v1").shouldBeFalse()
        repo.add("v1", 12f, "nice", AnnotationKind.GOOD_SHOT)
        repo.hasAnnotations("v1").shouldBeTrue()
        val a = repo.annotationsFor("v1").single()
        a.timestampSeconds shouldBe 12f
        a.body shouldBe "nice"
        a.kind shouldBe AnnotationKind.GOOD_SHOT
    }

    @Test
    fun annotationsFor_is_sorted_by_timestamp() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("v1", 30f, "late", null)
        repo.add("v1", 5f, "early", null)
        repo.add("v1", 15f, "mid", null)
        repo.annotationsFor("v1").map { it.timestampSeconds } shouldBe listOf(5f, 15f, 30f)
    }

    @Test
    fun keyed_per_video() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("v1", 1f, "a", null)
        repo.add("v2", 2f, "b", null)
        repo.annotationsFor("v1").single().body shouldBe "a"
        repo.annotationsFor("v2").single().body shouldBe "b"
    }

    @Test
    fun delete_removes_only_that_annotation() {
        val repo = LocalAnnotationsRepository(MapSettings())
        val a = repo.add("v1", 1f, "a", null)
        repo.add("v1", 2f, "b", null)
        repo.delete("v1", a.id)
        repo.annotationsFor("v1").map { it.body } shouldBe listOf("b")
    }

    @Test
    fun removeAllFor_clears_a_video() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("v1", 1f, "a", null)
        repo.add("v1", 2f, "b", null)
        repo.removeAllFor("v1")
        repo.hasAnnotations("v1").shouldBeFalse()
    }

    @Test
    fun persists_across_instances() {
        val settings = MapSettings()
        LocalAnnotationsRepository(settings).add("v1", 1f, "a", null)
        LocalAnnotationsRepository(settings).annotationsFor("v1").single().body shouldBe "a"
    }

    @Test
    fun corrupt_json_yields_empty() {
        val settings = MapSettings().apply { putString("local_annotations", "not-json") }
        LocalAnnotationsRepository(settings).annotationsFor("v1") shouldBe emptyList()
    }
}
