package com.badmintontracker.shared.util

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun randomUuid(): String = Uuid.random().toString()

internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
