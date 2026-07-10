package com.badmintontracker.shared.util

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun randomUuid(): String = Uuid.random().toString()

internal expect fun nowEpochMs(): Long
