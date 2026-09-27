package com.badmintontracker.shared.local

import kotlinx.coroutines.CompletableDeferred

/**
 * iOS composition helper: [LocalAnalysisCoordinator] with its legacy marking
 * time answered by a Swift callback.
 *
 * The coordinator asks through a `suspend` function type, which reaches Swift
 * as a `KotlinSuspendFunction1` protocol with an untyped argument; SKIE turns
 * Kotlin suspend functions into Swift `async` ones but not the other way round.
 * A plain function type would cross cleanly but make Swift answer
 * synchronously, and the answer comes from `AVAssetImageGenerator`, which is
 * asynchronous: the coordinator runs on a Swift concurrency thread (SKIE hands
 * the coroutine's dispatch to the calling task), and blocking one of those on a
 * semaphore is what the cooperative pool must not be asked to do.
 *
 * So Swift takes a completion instead, and nothing blocks. [legacyMarkingTime]
 * must call `done` once; a second call is ignored rather than fatal, and a
 * missing one leaves the run waiting until it is cancelled.
 */
fun createIosLocalAnalysisCoordinator(
    engine: LocalInferenceEngine,
    log: (String) -> Unit,
    legacyMarkingTime: (videoPath: String, done: (Double) -> Unit) -> Unit,
): LocalAnalysisCoordinator = LocalAnalysisCoordinator(
    engine = engine,
    log = log,
    legacyMarkingTime = { videoPath ->
        val answer = CompletableDeferred<Double>()
        legacyMarkingTime(videoPath) { answer.complete(it) }
        answer.await()
    },
)
