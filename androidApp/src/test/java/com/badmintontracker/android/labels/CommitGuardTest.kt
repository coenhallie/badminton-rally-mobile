package com.badmintontracker.android.labels

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * [DraftLabelRow]'s commit guard, covering the four cases the fix set out to
 * satisfy:
 *
 * 1. Done then the follow-up blur, same text -> creates once:
 *    [done_then_the_follow_up_blur_with_the_same_text_fires_only_once].
 * 2. Blur without ever pressing Done -> creates once: this file proves the
 *    guard half of it below (a fresh guard fires on its first non-empty
 *    commit, with no preceding "was this ever focused" signal required, the
 *    way the old `hadFocus` flag needed one) - but the guard alone cannot
 *    prove blur-only actually reaches `commit()`, since it has no notion of
 *    focus at all. That half is the unconditional
 *    `onFocusChanged = { state -> if (!state.isFocused) commit() }` wiring in
 *    [DraftLabelRow], with no gate in front of it, verified by inspection.
 * 3. A create that fails, then the same text retried -> fires:
 *    [a_retry_with_the_same_text_after_a_failed_create_fires]. This is the
 *    rollback, and the case most likely to regress silently.
 * 4. A create that fails, then different text -> fires:
 *    [a_failed_create_followed_by_different_text_fires].
 */
class CommitGuardTest {

    @Test
    fun done_then_the_follow_up_blur_with_the_same_text_fires_only_once() {
        // ShuttlOutlinedTextField's onDone never clears focus, so the blur
        // that follows Done reaches commit() a second time with the same
        // text. The first call must dispatch, the second must not.
        val guard = CommitGuard()

        guard.begin("Smash winner") shouldBe true
        guard.begin("Smash winner") shouldBe false
    }

    @Test
    fun a_fresh_guard_fires_on_its_first_commit_with_no_prior_arming_call() {
        // Necessary but not sufficient for case 2 (see the class KDoc): this
        // shows the guard does not need a preceding successful begin() - or,
        // by extension, a preceding focus-gained event - before it will let a
        // commit through. A hadFocus-style flag would need exactly that,
        // which is what trapped a blur-only commit under the old guard.
        val guard = CommitGuard()

        guard.begin("Smash winner") shouldBe true
    }

    @Test
    fun a_retry_with_the_same_text_after_a_failed_create_fires() {
        // The case the bug was about: a duplicate name is rejected, the draft
        // row stays open with the typed name intact, the user deletes the
        // conflicting label and taps away again without changing a character.
        // Rolling back to what the guard held before the failed attempt is
        // what lets that identical retry through instead of reading as a
        // repeat of the commit that already failed.
        val guard = CommitGuard()

        val previous = guard.lastCommitted
        guard.begin("Good shot") shouldBe true // first attempt, rejected by the server
        guard.failed(previous)

        guard.begin("Good shot") shouldBe true // retry after the conflict is resolved
    }

    @Test
    fun a_failed_create_followed_by_different_text_fires() {
        val guard = CommitGuard()

        val previous = guard.lastCommitted
        guard.begin("Good shot") shouldBe true // rejected by the server
        guard.failed(previous)

        guard.begin("Great shot") shouldBe true // different text, would fire even without the rollback
    }

    @Test
    fun blank_text_never_fires() {
        // begin() takes already-trimmed text, matching every caller in
        // DraftLabelRow.commit() - trimming is the caller's job, not this
        // guard's.
        val guard = CommitGuard()

        guard.begin("") shouldBe false
    }
}
