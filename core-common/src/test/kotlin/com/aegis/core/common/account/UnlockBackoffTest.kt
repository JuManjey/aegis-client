package com.aegis.core.common.account

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockBackoffTest {

    private val boot = 7

    @Test
    fun firstFailuresHaveNoDelay() {
        var state = UnlockAttempts()
        repeat(UnlockBackoff.FREE_ATTEMPTS) {
            state = UnlockBackoff.recordFailure(state, nowElapsedMs = 1_000, bootCount = boot)
            assertEquals(0L, UnlockBackoff.remaining(state, nowElapsedMs = 1_000, bootCount = boot).first)
        }
    }

    @Test
    fun delayGrowsWithEachFailureAndCapsAtOneHour() {
        assertEquals(30_000L, UnlockBackoff.delayAfter(5))
        assertEquals(60_000L, UnlockBackoff.delayAfter(6))
        assertEquals(5 * 60_000L, UnlockBackoff.delayAfter(7))
        assertEquals(15 * 60_000L, UnlockBackoff.delayAfter(8))
        assertEquals(30 * 60_000L, UnlockBackoff.delayAfter(9))
        assertEquals(60 * 60_000L, UnlockBackoff.delayAfter(10))
        assertEquals(60 * 60_000L, UnlockBackoff.delayAfter(25))
    }

    @Test
    fun lockoutCountsDownOnMonotonicClock() {
        val state = UnlockAttempts(failures = 4, bootCount = boot)
        val locked = UnlockBackoff.recordFailure(state, nowElapsedMs = 10_000, bootCount = boot)
        assertEquals(30_000L, UnlockBackoff.remaining(locked, nowElapsedMs = 10_000, bootCount = boot).first)
        assertEquals(10_000L, UnlockBackoff.remaining(locked, nowElapsedMs = 30_000, bootCount = boot).first)
        assertEquals(0L, UnlockBackoff.remaining(locked, nowElapsedMs = 40_000, bootCount = boot).first)
    }

    @Test
    fun rebootRestartsTheDelayWindow() {
        val locked = UnlockBackoff.recordFailure(
            UnlockAttempts(failures = 5, bootCount = boot),
            nowElapsedMs = 500_000,
            bootCount = boot,
        )
        val (left, updated) = UnlockBackoff.remaining(locked, nowElapsedMs = 2_000, bootCount = boot + 1)
        assertEquals(60_000L, left)
        assertEquals(boot + 1, updated.bootCount)
        assertEquals(62_000L, updated.lockedUntilElapsedMs)
    }

    @Test
    fun unknownBootNeverStretchesTheDelay() {
        val stale = UnlockAttempts(failures = 6, bootCount = UnlockAttempts.UNKNOWN_BOOT, lockedUntilElapsedMs = 9_000_000)
        val left = UnlockBackoff.remaining(stale, nowElapsedMs = 1_000, bootCount = UnlockAttempts.UNKNOWN_BOOT).first
        assertEquals(UnlockBackoff.delayAfter(6), left)
    }

    @Test
    fun attemptsBeforeWipeNeverGoesNegative() {
        assertEquals(10, UnlockBackoff.attemptsBeforeWipe(0))
        assertEquals(1, UnlockBackoff.attemptsBeforeWipe(9))
        assertEquals(0, UnlockBackoff.attemptsBeforeWipe(12))
    }
}
