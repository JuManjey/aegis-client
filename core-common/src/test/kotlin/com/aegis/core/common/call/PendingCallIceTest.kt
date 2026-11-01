package com.aegis.core.common.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCallIceTest {

    @Test
    fun takeReturnsInInsertOrderAndClears() {
        val buf = PendingCallIce()
        buf.add("c1", "0|0|cand-a")
        buf.add("c1", "0|0|cand-b")
        buf.add("c2", "0|0|other")
        assertEquals(listOf("0|0|cand-a", "0|0|cand-b"), buf.take("c1"))
        assertTrue(buf.take("c1").isEmpty())
        assertEquals(listOf("0|0|other"), buf.take("c2"))
    }

    @Test
    fun ignoresDuplicateAndEmpty() {
        val buf = PendingCallIce()
        buf.add("c1", "0|0|cand")
        buf.add("c1", "0|0|cand")
        buf.add("", "0|0|cand")
        buf.add("c1", "")
        assertEquals(listOf("0|0|cand"), buf.take("c1"))
    }

    @Test
    fun dropDoesNotApply() {
        val buf = PendingCallIce()
        buf.add("busy", "0|0|cand")
        buf.drop("busy")
        assertTrue(buf.take("busy").isEmpty())
    }

    @Test
    fun evictsOldestCallWhenOverCap() {
        val buf = PendingCallIce(maxPerCall = 2, maxCalls = 2)
        buf.add("old", "0|0|a")
        buf.add("new", "0|0|b")
        buf.add("newer", "0|0|c")
        assertTrue("самый старый callId вытеснен", buf.take("old").isEmpty())
        assertEquals(listOf("0|0|b"), buf.take("new"))
        assertEquals(listOf("0|0|c"), buf.take("newer"))
    }

    @Test
    fun capsCandidatesPerCall() {
        val buf = PendingCallIce(maxPerCall = 2, maxCalls = 4)
        buf.add("c", "0|0|a")
        buf.add("c", "0|0|b")
        buf.add("c", "0|0|c")
        assertEquals(listOf("0|0|a", "0|0|b"), buf.take("c"))
    }

    @Test
    fun isolatesCallIds() {
        val buf = PendingCallIce()
        buf.add("live", "0|0|keep")
        buf.add("late", "0|0|poison")
        assertEquals(1, buf.size("live"))
        assertEquals(1, buf.size("late"))
        assertEquals(listOf("0|0|keep"), buf.take("live"))
        assertEquals(listOf("0|0|poison"), buf.take("late"))
    }
}
