package com.aegis.network.tor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayConnectionScopeTest {

    @Test
    fun closedScopeRunsNothing() {
        val scope = RelayConnectionScope(RelayTransportKind.HTTPS)
        scope.close()
        var ran = false
        try {
            scope.enter { ran = true }
            fail("закрытая сессия должна бросать")
        } catch (_: ClosedTransportException) {
        }
        assertFalse(ran)
    }

    @Test
    fun registerAfterCloseAbortsImmediately() {
        val scope = RelayConnectionScope(RelayTransportKind.HTTPS)
        scope.close()
        val aborted = AtomicInteger()
        try {
            scope.register { aborted.incrementAndGet() }
            fail("регистрация в закрытой сессии должна бросать")
        } catch (_: ClosedTransportException) {
        }
        assertEquals(1, aborted.get())
    }

    @Test
    fun closeAbortsLiveConnectionsButNotFinishedOnes() {
        val scope = RelayConnectionScope(RelayTransportKind.TOR)
        val aborted = AtomicInteger()
        val finished = scope.register { aborted.addAndGet(100) }
        finished.close()
        scope.register { aborted.incrementAndGet() }
        scope.close()
        assertEquals(1, aborted.get())
        assertTrue(scope.isClosed)
    }

    @Test
    fun awaitIdleWaitsForInFlightRequests() {
        val scope = RelayConnectionScope(RelayTransportKind.HTTPS)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread { scope.enter { inside.countDown(); release.await(5, TimeUnit.SECONDS) } }
        worker.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        assertEquals(1, scope.inFlightCount)
        assertFalse(scope.awaitIdle(100))
        release.countDown()
        assertTrue(scope.awaitIdle(5_000))
        worker.join()
        assertEquals(0, scope.inFlightCount)
    }
}
