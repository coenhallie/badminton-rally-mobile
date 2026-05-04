package com.badmintontracker.android

import android.app.Application
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.SupabaseConfig
import com.russhwolf.settings.SharedPreferencesSettings

class RallyAndroidApp : Application() {

    lateinit var rally: RallyApp
        private set

    override fun onCreate() {
        super.onCreate()
        rally = RallyApp(
            config   = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE)),
        )
    }
}
