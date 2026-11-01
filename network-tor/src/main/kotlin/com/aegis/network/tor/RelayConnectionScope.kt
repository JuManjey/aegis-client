package com.aegis.network.tor

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Сессия транспорта: все соединения к релею в одном режиме сети.
 *
 * Перестать выдавать новых клиентов при смене режима мало: long-poll, начатый
 * по HTTPS, висит до 50 с и, если его не оборвать, закончится уже после старта
 * Tor — сервер увидел бы IP рядом с трафиком из Tor. Поэтому [close] обрывает
 * всё зарегистрированное и запрещает новые запросы, а [awaitIdle] позволяет
 * дождаться, пока последний поток выйдет из запроса.
 */
class RelayConnectionScope(val kind: RelayTransportKind) {

    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var closed = false
    private var inFlight = 0
    private val handles = LinkedHashSet<Handle>()

    /** Способ оборвать одно соединение; [close] снимает регистрацию по завершении запроса. */
    inner class Handle internal constructor(private val abort: () -> Unit) : Closeable {
        internal fun abortNow() {
            runCatching(abort)
        }

        override fun close() {
            lock.withLock { handles.remove(this) }
        }
    }

    val isClosed: Boolean get() = lock.withLock { closed }

    /** Сколько запросов сейчас внутри сессии (для журнала и тестов). */
    val inFlightCount: Int get() = lock.withLock { inFlight }

    /** Выполняет [block] как один запрос сессии. Закрытая сессия — исключение без единого сокета. */
    fun <T> enter(block: () -> T): T {
        lock.withLock {
            if (closed) throw ClosedTransportException(kind)
            inFlight++
        }
        try {
            return block()
        } finally {
            lock.withLock {
                inFlight--
                if (inFlight == 0) idle.signalAll()
            }
        }
    }

    /** Регистрирует обрыв соединения. Сессия уже закрыта — обрывает сразу и бросает. */
    fun register(abort: () -> Unit): Handle {
        val handle = Handle(abort)
        val alreadyClosed = lock.withLock {
            if (!closed) handles += handle
            closed
        }
        if (alreadyClosed) {
            handle.abortNow()
            throw ClosedTransportException(kind)
        }
        return handle
    }

    /** Новые запросы запрещены, текущие обрываются. Идемпотентно. */
    fun close() {
        val toAbort = lock.withLock {
            closed = true
            handles.toList().also { handles.clear() }
        }
        toAbort.forEach { it.abortNow() }
    }

    /** Ждёт выхода всех запросов из сессии. `true` — дождались за [timeoutMs]. */
    fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
        var leftNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (inFlight > 0) {
            if (leftNs <= 0L) return false
            leftNs = idle.awaitNanos(leftNs)
        }
        true
    }
}
