package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.RallyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * iOS composition helper: wires the shared AnalyzeCoordinator with a
 * file-streaming channel (entry.uri is a Documents-relative path on iOS).
 */
fun createIosAnalyzeCoordinator(rally: RallyApp, documentsPath: String): AnalyzeCoordinator =
    rally.analyzeCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openChannel = { uri, offset ->
            openLocalVideoChannel("$documentsPath/$uri", offset)
        },
        log = { println("AnalyzeCoordinator: $it") },
    )
