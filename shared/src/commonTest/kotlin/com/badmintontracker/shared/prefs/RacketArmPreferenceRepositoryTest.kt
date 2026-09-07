package com.badmintontracker.shared.prefs

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RacketArmPreferenceRepositoryTest {

    @Test
    fun nothing_is_assumed_about_which_arm_holds_the_racket() {
        assertNull(RacketArmPreferenceRepository(MapSettings()).racketArm("v1"))
    }

    @Test
    fun the_choice_is_per_video_and_survives_a_new_instance() {
        val settings = MapSettings()
        RacketArmPreferenceRepository(settings).setRacketArm("v1", RacketArm.LEFT)
        val reloaded = RacketArmPreferenceRepository(settings)
        assertEquals(RacketArm.LEFT, reloaded.racketArm("v1"))
        assertNull(reloaded.racketArm("v2"))
    }

    @Test
    fun clearing_returns_to_both_arms() {
        val repo = RacketArmPreferenceRepository(MapSettings())
        repo.setRacketArm("v1", RacketArm.RIGHT)
        repo.setRacketArm("v1", null)
        assertNull(repo.racketArm("v1"))
    }

    @Test
    fun a_value_this_build_does_not_know_reads_as_unset() {
        val settings = MapSettings()
        settings.putString("racket_arm:v1", "AMBIDEXTROUS")
        assertNull(RacketArmPreferenceRepository(settings).racketArm("v1"))
    }
}
