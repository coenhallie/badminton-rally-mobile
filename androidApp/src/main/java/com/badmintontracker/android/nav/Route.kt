package com.badmintontracker.android.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object SignIn   : Route
    @Serializable data object ClipList : Route
    @Serializable data class  MatchClips(val videoId: String) : Route
    @Serializable data class  ClipDetail(val clipId: String)  : Route
}
