package com.badmintontracker.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class ShareErrorMessageTest {
    @Test
    fun mapsEachCaseToUserCopy() {
        assertEquals("You can only share matches you uploaded.", ShareError.NotOwner.userMessage())
        assertEquals("No Shuttl user found with that email.", ShareError.NoSuchUser.userMessage())
        assertEquals("You can't share a match with yourself.", ShareError.CannotShareSelf.userMessage())
        assertEquals("Could not share — please try again.", ShareError.Unknown(RuntimeException("x")).userMessage())
        assertEquals("Unknown error.", (null as ShareError?).userMessage())
    }
}
