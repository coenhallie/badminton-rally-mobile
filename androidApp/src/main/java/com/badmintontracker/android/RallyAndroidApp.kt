package com.badmintontracker.android

import android.app.Application
import com.badmintontracker.android.data.ThemePreferenceRepository
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.SupabaseConfig
import com.russhwolf.settings.SharedPreferencesSettings

class RallyAndroidApp : Application() {

    lateinit var rally:       RallyApp                    private set
    lateinit var themePrefs:  ThemePreferenceRepository   private set

    override fun onCreate() {
        super.onCreate()
        val settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE))
        rally       = RallyApp(SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), settings)
        themePrefs  = ThemePreferenceRepository(settings)
    }
}
