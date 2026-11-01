package com.aegis.messenger.call

import android.util.Log
import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.TorMode
import com.aegis.messenger.messaging.RelayGateway
import com.aegis.network.tor.LocalBindAddress
import com.aegis.network.tor.TorTcpForwarder
import kotlinx.coroutines.CoroutineScope
import java.util.Collections

/**
 * Аренда TURN для медиапотока звонка (§7.5/§10.10/§12.2). Что именно берётся,
 * определяет режим сети:
 *
 * **Tor ВКЛ.** WebRTC не умеет ходить в TURN через SOCKS-прокси Tor, поэтому:
 *  1) креды запрашиваются у релея через Tor (`GET /v1/turn` → [RelayGateway.fetchTurn]);
 *  2) поднимается локальный [TorTcpForwarder] `<bindHost>:<port>` → SOCKS5(Tor) →
 *     `<coturn>.onion` (порядок bind-адресов — [LocalBindAddress.bindCandidates]:
 *     сначала `127.0.0.2`, он не ломает Samsung TurnPort);
 *  3) WebRTC получает ровно один ICE-сервер `turn:<bindHost>:<port>?transport=tcp`.
 * STUN не запрашивается никогда: он раскрыл бы IP и через Tor бесполезен.
 *
 * **Tor ВЫКЛ.** Креды приходят по HTTPS, а ICE-серверы — клирнет `stun:`, `turn:`
 * и `turns:` своего сервера ([CallIcePlan.clearnetServers]) — отдаются WebRTC
 * напрямую, без форвардера и SOCKS.
 *
 * Zero-trust (§10.9): медиа остаётся E2EE (DTLS-SRTP + кадровый слой); coturn
 * видит только шифртекст. Секрет TURN клиенту не раскрывается.
 *
 * Best-effort: при любой проблеме — [Lease.EMPTY]. Без Tor звонок идёт без
 * резерва, в режиме Tor [CallManager] его не начинает (медиа ушло бы мимо Tor).
 */
class TurnProvisioner(
    private val relayGateway: RelayGateway,
    private val scope: CoroutineScope,
) {
    /**
     * Одноразовая «аренда» TURN на время звонка: список ICE-серверов для WebRTC и
     * связанный локальный форвардер (только в режиме Tor), который нужно закрыть
     * в конце ([close]).
     */
    class Lease(
        val iceServers: List<IceTurnServer>,
        private val forwarder: TorTcpForwarder?,
    ) {
        /** Есть ли в аренде ретранслятор TURN, а не только STUN прямого пути. */
        val hasRelay: Boolean
            get() = iceServers.any { !CallIcePlan.isStun(it.uri) }

        /** Останавливает форвардер и рвёт туннели. Идемпотентно. */
        fun close() {
            runCatching { forwarder?.close() }
        }

        companion object {
            val EMPTY = Lease(emptyList(), null)
        }
    }

    /** Живые аренды — чтобы смена режима сети могла закрыть их все. */
    private val live: MutableSet<Lease> = Collections.synchronizedSet(LinkedHashSet())

    /**
     * Готовит TURN-аренду для одного звонка в режиме [mode]. Никогда не бросает —
     * при любой проблеме возвращает [Lease.EMPTY].
     */
    suspend fun acquire(mode: TorMode): Lease {
        DiagnosticLog.sync(DIAG_TAG, "TURN аренда старт", "режим" to mode.name)
        val lease = when (mode) {
            TorMode.ALWAYS -> acquireOverTor()
            TorMode.OFF -> acquireClearnet()
        }
        if (lease !== Lease.EMPTY) live += lease
        return lease
    }

    /** Возвращает аренду (конец звонка). */
    fun release(lease: Lease?) {
        if (lease == null) return
        live -= lease
        lease.close()
    }

    /** Закрывает все аренды (смена режима сети). */
    fun closeAll() {
        val all = synchronized(live) { live.toList().also { live.clear() } }
        all.forEach { it.close() }
        if (all.isNotEmpty()) DiagnosticLog.event(DIAG_TAG, "TURN аренды закрыты", "штук" to all.size)
    }

    private suspend fun acquireOverTor(): Lease {
        val socksPort = relayGateway.socksPortOrNull() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "TURN: нет SOCKS-порта Tor" }
            return Lease.EMPTY
        }
        val turn = relayGateway.fetchTurn() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "TURN: релей не выдал креды" }
            return Lease.EMPTY
        }
        if (!turn.hasOnion) {
            DiagnosticLog.w(DIAG_TAG) { "TURN: на релее нет onion-TURN — медиа через Tor невозможно" }
            return Lease.EMPTY
        }
        DiagnosticLog.sync(
            DIAG_TAG, "TURN креды",
            "удалённыйПорт" to turn.port,
            "onion" to DiagnosticLog.fp(turn.onion),
            "user" to DiagnosticLog.presence(turn.username),
            "cred" to DiagnosticLog.presence(turn.credential),
        )
        return runCatching {
            val (forwarder, host, localPort) = startForwarder(socksPort, turn.onion, turn.port)
            val uri = "turn:$host:$localPort?transport=tcp"
            Log.i(TAG, "TURN через Tor готов: turn:${CallTrace.hostKind(host)}:$localPort → onion:${turn.port}")
            DiagnosticLog.sync(
                DIAG_TAG, "TURN готов",
                "локальныйПорт" to localPort,
                "удалённыйПорт" to turn.port,
                "onion" to DiagnosticLog.fp(turn.onion),
                "loopback" to LocalBindAddress.isLoopbackHost(host),
                "hostKind" to CallTrace.hostKind(host),
                *CallTrace.iceUriFields(uri),
            )
            Lease(listOf(IceTurnServer(uri, turn.username, turn.credential)), forwarder)
        }.getOrElse {
            Log.w(TAG, "не удалось поднять TURN-форвардер: ${it.message}")
            DiagnosticLog.e(DIAG_TAG, it) { "TURN-форвардер не поднялся" }
            Lease.EMPTY
        }
    }

    private suspend fun acquireClearnet(): Lease {
        val relayHost = relayGateway.httpsRelayHost() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "STUN/TURN: HTTPS-адрес релея не задан" }
            return Lease.EMPTY
        }
        val turn = relayGateway.fetchTurn() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "STUN/TURN: релей не выдал креды" }
            return Lease.EMPTY
        }
        val servers = CallIcePlan.clearnetServers(turn, relayHost)
        DiagnosticLog.sync(
            DIAG_TAG, "клирнет ICE-серверы",
            "выданоUri" to turn.uris.size,
            "принято" to servers.size,
            "stun" to servers.count { CallIcePlan.isStun(it.uri) },
            "turn" to servers.count { !CallIcePlan.isStun(it.uri) },
        )
        return if (servers.isEmpty()) Lease.EMPTY else Lease(servers, forwarder = null)
    }

    private fun startForwarder(
        socksPort: Int,
        onion: String,
        port: Int,
    ): Triple<TorTcpForwarder, String, Int> {
        val candidates = LocalBindAddress.bindCandidates()
        DiagnosticLog.sync(
            DIAG_TAG, "TURN bind кандидаты",
            "штук" to candidates.size,
            "порядок" to candidates.joinToString(",") { CallTrace.hostKind(it) },
        )
        var last: Throwable? = null
        for (host in candidates) {
            DiagnosticLog.sync(
                DIAG_TAG, "TURN bind попытка",
                "hostKind" to CallTrace.hostKind(host),
            )
            val forwarder = TorTcpForwarder(socksPort, onion, port, scope, bindHost = host)
            val started = runCatching { Triple(forwarder, host, forwarder.start()) }
            if (started.isSuccess) {
                DiagnosticLog.sync(
                    DIAG_TAG, "TURN bind ок",
                    "hostKind" to CallTrace.hostKind(host),
                )
                return started.getOrThrow()
            }
            last = started.exceptionOrNull()
            runCatching { forwarder.close() }
            DiagnosticLog.w(DIAG_TAG) {
                "TURN: bind ${CallTrace.hostKind(host)} не удался: ${last?.javaClass?.simpleName}: ${last?.message}"
            }
        }
        throw last ?: IllegalStateException("TURN: нет bind-адреса")
    }

    private companion object {
        const val TAG = "AegisTurnProvisioner"
        const val DIAG_TAG = "turn"
    }
}
