package com.aegis.messenger.messaging

import com.aegis.core.common.security.TorMode
import com.aegis.network.tor.RelayConnectionScope
import com.aegis.network.tor.RelayTransportKind
import com.aegis.network.tor.TorStatus
import com.aegis.network.tor.TorTransport
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок переключения режима сети: соединения прежнего режима закрыты и
 * ДОЖДАНЫ до того, как транспорт нового режима поднимается.
 */
class NetworkModeControllerTest {

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private inner class FakeSessions : RelaySessions {
        var current: RelayConnectionScope? = null

        override fun closeSession(): RelayConnectionScope? {
            events += "close:${current?.kind}"
            val closing = current
            current = null
            closing?.close()
            return closing
        }

        override fun openSession(mode: TorMode) {
            events += "open:$mode"
            current = RelayConnectionScope(if (mode == TorMode.ALWAYS) RelayTransportKind.TOR else RelayTransportKind.HTTPS)
        }
    }

    private inner class RecordingTor : TorTransport {
        override val status: StateFlow<TorStatus> = MutableStateFlow(TorStatus.Disabled)
        override val socksPort: Int? = null
        override val statusChangedAtElapsed: Long = 0L

        /** Сколько запросов прежней сессии было «в пути» в момент applyMode. */
        var inFlightAtApply: Int = -1
        var watched: RelayConnectionScope? = null

        override suspend fun applyMode(mode: TorMode) {
            inFlightAtApply = watched?.inFlightCount ?: 0
            events += "apply:$mode"
        }

        override suspend fun restart(reason: String) = Unit
        override suspend fun shutdown() = Unit
    }

    private fun controller(sessions: FakeSessions, tor: RecordingTor, mode: () -> TorMode) =
        NetworkModeController(
            torTransport = tor,
            gateway = sessions,
            modeProvider = mode,
            networkEpochs = null,
            onBeforeSwitch = { events += "before" },
            onAfterSwitch = { m -> events += "after:$m" },
            scope = scope,
        )

    @Test
    fun startAppliesSavedModeWithoutTorInDirectMode() = runBlocking {
        val sessions = FakeSessions()
        val tor = RecordingTor()
        controller(sessions, tor) { TorMode.OFF }.start()
        assertEquals(listOf("close:null", "before", "apply:OFF", "open:OFF", "after:OFF"), events)
    }

    @Test
    fun directRequestIsAbortedAndAwaitedBeforeTorStarts() = runBlocking {
        val sessions = FakeSessions()
        val tor = RecordingTor()
        var mode = TorMode.OFF
        val controller = controller(sessions, tor) { mode }
        controller.start()
        val direct = sessions.current!!
        tor.watched = direct

        // Висящий long-poll по HTTPS: обрывается только закрытием сессии.
        val inside = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val worker = Thread {
            direct.enter {
                direct.register { aborted.countDown() }
                inside.countDown()
                aborted.await(10, TimeUnit.SECONDS)
                events += "direct-request-finished"
            }
        }
        worker.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        events.clear()
        mode = TorMode.ALWAYS
        controller.switchTo(TorMode.ALWAYS, "тест")
        worker.join(5_000)

        assertEquals(0, tor.inFlightAtApply)
        assertEquals(
            listOf("close:HTTPS", "before", "direct-request-finished", "apply:ALWAYS", "open:ALWAYS", "after:ALWAYS"),
            events,
        )
    }

    @Test
    fun requestThatIgnoresAbortStillDelaysTorStart() = runBlocking {
        val sessions = FakeSessions()
        val tor = RecordingTor()
        var mode = TorMode.OFF
        val controller = controller(sessions, tor) { mode }
        controller.start()
        val direct = sessions.current!!
        tor.watched = direct

        val inside = CountDownLatch(1)
        val worker = Thread {
            direct.enter {
                inside.countDown()
                Thread.sleep(400)
                events += "slow-request-finished"
            }
        }
        worker.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        events.clear()
        mode = TorMode.ALWAYS
        controller.switchTo(TorMode.ALWAYS, "тест")

        assertEquals(0, tor.inFlightAtApply)
        assertTrue(events.indexOf("slow-request-finished") < events.indexOf("apply:ALWAYS"))
    }

    @Test
    fun sameModeIsNoOp() = runBlocking {
        val sessions = FakeSessions()
        val tor = RecordingTor()
        val controller = controller(sessions, tor) { TorMode.ALWAYS }
        controller.start()
        events.clear()
        controller.switchTo(TorMode.ALWAYS, "повтор")
        assertTrue(events.isEmpty())
        assertEquals(NetworkModeController.State.Active(TorMode.ALWAYS), controller.state.value)
    }

    @Test
    fun torToDirectStopsTorBeforeOpeningHttps() = runBlocking {
        val sessions = FakeSessions()
        val tor = RecordingTor()
        var mode = TorMode.ALWAYS
        val controller = controller(sessions, tor) { mode }
        controller.start()
        events.clear()
        mode = TorMode.OFF
        controller.switchTo(TorMode.OFF, "тест")
        assertEquals(listOf("close:TOR", "before", "apply:OFF", "open:OFF", "after:OFF"), events)
    }
}
