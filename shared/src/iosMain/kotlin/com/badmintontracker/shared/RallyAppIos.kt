package com.badmintontracker.shared

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

/** iOS entry point: builds the app graph with NSUserDefaults-backed settings. */
fun createRallyApp(url: String, anonKey: String): RallyApp = RallyApp(
    config = SupabaseConfig(url = url, anonKey = anonKey),
    settings = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults),
)
