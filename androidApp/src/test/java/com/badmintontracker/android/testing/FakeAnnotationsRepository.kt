package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository

class FakeAnnotationsRepository : AnnotationsRepository {
    var byClipId: Map<String, List<RallyAnnotation>> = emptyMap()
    var listError: Throwable? = null

    override suspend fun list(clipId: String): List<RallyAnnotation> {
        listError?.let { throw it }
        return byClipId[clipId] ?: emptyList()
    }
    override suspend fun add(clipId: String, timestampSeconds: Float, body: String) =
        Result.failure<RallyAnnotation>(NotImplementedError())
    override suspend fun delete(id: String) = Result.success(Unit)
}
