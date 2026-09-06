package com.badmintontracker.shared.testing

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

/**
 * A separate file from [IosTestDoubles], not an accident: that file is the
 * video/clips/analyze doubles for `MatchModelTests`, and this one is the
 * annotation-labels double for `ScoringModelTests`. Keeping them apart means
 * a change on one surface never touches a file with unrelated in-flight
 * hunks on the other.
 *
 * See [IosTestDoubles]'s doc comment for why a Swift-side fake is not an
 * option: [AnnotationLabelsRepository] has several `suspend fun` members, and
 * Swift cannot implement a Kotlin interface that has any.
 */
private class InMemoryAnnotationLabelsRepository(
    initial: List<AnnotationLabel>,
) : AnnotationLabelsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(initial)

    override val labels: StateFlow<List<AnnotationLabel>> = state.asStateFlow()

    /**
     * Derived exactly the way [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl]
     * derives its own [scoreboardLabels] and [clipLabels]: a filter over
     * [AnnotationLabel.scope], not a hardcoded shortcut. A fake that skipped
     * this would let a test pass against behaviour the real repository does
     * not actually have.
     */
    override val scoreboardLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onScoreboard } }
            .stateIn(
                scope + Dispatchers.Unconfined,
                SharingStarted.Eagerly,
                state.value.filter { it.scope.onScoreboard },
            )

    override val clipLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onClips } }
            .stateIn(
                scope + Dispatchers.Unconfined,
                SharingStarted.Eagerly,
                state.value.filter { it.scope.onClips },
            )

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    override suspend fun create(name: String, color: LabelColor?, usage: LabelUsage): Result<AnnotationLabel> =
        Result.failure(UnsupportedOperationException("not needed by any test yet"))

    override suspend fun rename(id: String, name: String): Result<Unit> = Result.success(Unit)

    override suspend fun recolor(id: String, color: LabelColor): Result<Unit> = Result.success(Unit)

    /** The one mutator worth making real: cheap, and a test scoping a label
     *  onto or off the board is a plausible thing to want to assert on. */
    override suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit> = runCatching {
        state.value = state.value.map { if (it.id == id) it.copy(usage = usage.key) else it }
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        state.value = state.value.filterNot { it.id == id }
    }
}

/**
 * An [AnnotationLabelsRepository] the iOS test bundle can build, seeded with
 * [labels] up front. `scoreboardLabels` and `clipLabels` are derived from it
 * the same way the real repository derives them - see
 * [InMemoryAnnotationLabelsRepository.scoreboardLabels]'s comment.
 */
fun testAnnotationLabelsRepository(labels: List<AnnotationLabel>): AnnotationLabelsRepository =
    InMemoryAnnotationLabelsRepository(labels)
