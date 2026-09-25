package com.badmintontracker.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeCopyTest {
    private val all = listOf(
        HomeCopy.TAB_MATCHES, HomeCopy.TAB_ANALYTICS, HomeCopy.TAB_SETTINGS,
        HomeCopy.ADD_MATCH, HomeCopy.EMPTY_BODY, HomeCopy.ANALYTICS_EMPTY_TITLE,
        HomeCopy.ANALYTICS_EMPTY_BODY, HomeCopy.SETTINGS_LABELS, HomeCopy.SETTINGS_LABELS_DETAIL,
        HomeCopy.SETTINGS_DARK_THEME, HomeCopy.SETTINGS_SIGNED_IN_AS, HomeCopy.SETTINGS_SIGN_OUT,
        HomeCopy.version("0.8.0", 25),
    )

    @Test fun no_em_dash_anywhere() {
        all.forEach { assertFalse('\u2014' in it, it) }
    }

    @Test fun the_version_line_names_name_and_code() {
        assertEquals("Version 0.8.0 (25)", HomeCopy.version("0.8.0", 25))
    }

    // The add sheet lists "New match" as one of its own three options; a
    // button named after one of them would read as a shortcut to it.
    @Test fun the_add_button_is_not_named_after_a_sheet_option() {
        assertEquals("Add match", HomeCopy.ADD_MATCH)
    }
}
