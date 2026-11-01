package com.aegis.core.common.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStageLogTest {

    /** Управляемые «монотонные» часы: тесты не должны зависеть от реального времени. */
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun firstMarkStartsTimelineAtZero() {
        val clock = FakeClock(now = 5_000L)
        val log = CallStageLog(clock)

        val stages = log.mark(CallStage.OUTGOING_START, CallStageState.DONE)

        assertEquals(1, stages.size)
        assertEquals(0L, stages[0].atMs)
        assertEquals(CallStage.OUTGOING_START, stages[0].stage)
    }

    @Test
    fun atMsIsRelativeToFirstMark() {
        val clock = FakeClock(now = 5_000L)
        val log = CallStageLog(clock)
        log.mark(CallStage.OUTGOING_START, CallStageState.DONE)

        clock.now = 6_400L
        val stages = log.mark(CallStage.ENGINE, CallStageState.DONE)

        assertEquals(1_400L, stages.last().atMs)
    }

    @Test
    fun activeStageIsUpgradedInPlaceInsteadOfDuplicated() {
        val clock = FakeClock()
        val log = CallStageLog(clock)
        log.mark(CallStage.ICE, CallStageState.ACTIVE)

        clock.now += 3_000L
        val stages = log.mark(CallStage.ICE, CallStageState.DONE, "путь найден")

        assertEquals(1, stages.size)
        assertEquals(CallStageState.DONE, stages[0].state)
        assertEquals("путь найден", stages[0].detail)
        assertEquals(3_000L, stages[0].atMs)
    }

    @Test
    fun repeatedActiveMarkDoesNotGrowTimeline() {
        val log = CallStageLog(FakeClock())

        log.mark(CallStage.ICE, CallStageState.ACTIVE)
        log.mark(CallStage.ICE, CallStageState.ACTIVE)
        val stages = log.mark(CallStage.ICE, CallStageState.ACTIVE)

        assertEquals(1, stages.size)
    }

    @Test
    fun closedStageIsAppendedAgainNotRewritten() {
        val log = CallStageLog(FakeClock())
        log.mark(CallStage.MEDIA, CallStageState.DONE)

        // Медиа поднялось, потом путь потеряли: это ДВА события, а не правка
        // одного — пользователь должен видеть и то, и другое.
        val stages = log.mark(CallStage.MEDIA, CallStageState.WARN, "восстановление")

        assertEquals(2, stages.size)
        assertEquals(CallStageState.DONE, stages[0].state)
        assertEquals(CallStageState.WARN, stages[1].state)
    }

    @Test
    fun timelineIsCappedAndKeepsNewestEntries() {
        val clock = FakeClock()
        val log = CallStageLog(clock)

        // Больше записей, чем вмещает лента: старые вытесняются.
        repeat(40) {
            clock.now += 10L
            log.mark(CallStage.ICE, CallStageState.DONE, "шаг $it")
        }
        val stages = log.snapshot()

        assertTrue("лента не должна расти без предела", stages.size <= 24)
        assertEquals("шаг 39", stages.last().detail)
    }

    @Test
    fun resetDropsPreviousCallTimeline() {
        val clock = FakeClock()
        val log = CallStageLog(clock)
        log.mark(CallStage.OUTGOING_START, CallStageState.DONE)

        log.reset()
        clock.now += 9_000L
        val stages = log.mark(CallStage.INCOMING_OFFER, CallStageState.ACTIVE)

        assertEquals(1, stages.size)
        // Отсчёт нового звонка начинается заново, а не с конца предыдущего.
        assertEquals(0L, stages[0].atMs)
    }

    @Test
    fun snapshotIsDetachedFromLog() {
        val log = CallStageLog(FakeClock())
        val first = log.mark(CallStage.OUTGOING_START, CallStageState.DONE)

        log.mark(CallStage.ENGINE, CallStageState.DONE)

        assertEquals("снимок не должен меняться задним числом", 1, first.size)
    }
}
