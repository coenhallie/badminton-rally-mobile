package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository
import kotlinx.datetime.Instant

class FakeAnnotationsRepository : AnnotationsRepository {
    var byClipId: Map<String, List<RallyAnnotation>> = emptyMap()
    var listError: Throwable? = null
    var addError: Throwable? = null
    var deleteError: Throwable? = null
    val addCalls = mutableListOf<Triple<String, Float, String>>()
    val deleteCalls = mutableListOf<String>()
    private var nextId = 0

    override suspend fun list(clipId: String): List<RallyAnnotation> {
        listError?.let { throw it }
        return byClipId[clipId] ?: emptyList()
    }

    override suspend fun add(
        clipId: String, timestampSeconds: Float, body: String,
    ): Result<RallyAnnotation> {
        addCalls += Triple(clipId, timestampSeconds, body)
        addError?.let { return Result.failure(it) }
        val row = RallyAnnotation(
            id = "new-${++nextId}",
            clipId = clipId,
            timestampSeconds = timestampSeconds,
            body = body,
            createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        byClipId = byClipId + (clipId to ((byClipId[clipId] ?: emptyList()) + row))
        return Result.success(row)
    }

    override suspend fun delete(id: String): Result<Unit> {
        deleteCalls += id
        deleteError?.let { return Result.failure(it) }
        byClipId = byClipId.mapValues { (_, v) -> v.filterNot { it.id == id } }
        return Result.success(Unit)
    }
}
