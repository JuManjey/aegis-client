package com.aegis.core.common.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPhaseTest {

    @Test
    fun livePhasesCoverDialThroughActive() {
        assertFalse(CallPhase.IDLE.isLive)
        assertTrue(CallPhase.DIALING.isLive)
        assertTrue(CallPhase.RINGING.isLive)
        assertTrue(CallPhase.CONNECTING.isLive)
        assertTrue(CallPhase.ACTIVE.isLive)
        assertFalse(CallPhase.ENDED.isLive)
    }
}
