package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * In-memory double for [AnnotationLabelsRepository]. Enforces the same
 * duplicate-name rule as [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl],
 * failing with the same message, so tests that depend on that rule are meaningful.
 */
class FakeAnnotationLabelsRepository(initial: List<AnnotationLabel> = emptyList()) : AnnotationLabelsRepository {

    private val state = MutableStateFlow(initial)
    override val labels: StateFlow<List<AnnotationLabel>> = state.asStateFlow()

    var refreshError: Throwable? = null
    private var nextId = 0

    override suspend fun refresh(): Result<Unit> {
        refreshError?.let { return Result.failure(it) }
        return Result.success(Unit)
    }

    override suspend fun create(name: String, color: LabelColor?): Result<AnnotationLabel> {
        val trimmed = name.trim()
        validate(trimmed)?.let { return Result.failure(it) }
        val swatch = color ?: AnnotationLabelsRepositoryImpl.nextUnusedColor(state.value.map { it.colorKey })
        val row = AnnotationLabel(
            id = "label-${++nextId}",
            name = trimmed,
            colorKey = swatch.key,
            createdAt = Instant.parse("2026-08-24T12:00:00Z"),
        )
        state.value = state.value + row
        return Result.success(row)
    }

    override suspend fun rename(id: String, name: String): Result<Unit> {
        val trimmed = name.trim()
        validate(trimmed, ignoringId = id)?.let { return Result.failure(it) }
        state.value = state.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        return Result.success(Unit)
    }

    override suspend fun recolor(id: String, color: LabelColor): Result<Unit> {
        state.value = state.value.map { if (it.id == id) it.copy(colorKey = color.key) else it }
        return Result.success(Unit)
    }

    override suspend fun delete(id: String): Result<Unit> {
        state.value = state.value.filterNot { it.id == id }
        return Result.success(Unit)
    }

    private fun validate(trimmed: String, ignoringId: String? = null): Throwable? = when {
        trimmed.isEmpty() -> IllegalArgumentException("Give the label a name.")
        trimmed.length > MAX_NAME -> IllegalArgumentException("The name can be up to $MAX_NAME characters.")
        state.value.any { it.id != ignoringId && it.name.equals(trimmed, ignoreCase = true) } ->
            IllegalArgumentException("You already have a label called \"$trimmed\".")
        else -> null
    }

    private companion object { const val MAX_NAME = 24 }
}
