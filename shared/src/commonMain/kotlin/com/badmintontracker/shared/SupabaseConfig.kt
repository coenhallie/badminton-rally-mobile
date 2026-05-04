package com.badmintontracker.shared

data class SupabaseConfig(
    val url: String,
    val anonKey: String,
    val deeplinkScheme: String = "badmintontracker",
    val deeplinkHost:   String = "login",
)
