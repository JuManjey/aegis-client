package com.aegis.network.tor

import android.util.Log
import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.diag.DiagnosticLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Локальный TCP-форвардер `<bindHost>:<localPort>` → SOCKS5(Tor) →
 * `targetHost:targetPort` (обычно `.onion`). Каждое входящее соединение
 * туннелируется в Tor отдельным каналом и прокачивается в обе стороны.
 *
 * Зачем это для звонков (§7.5/§10.10): WebRTC не умеет самостоятельно ходить в
 * TURN через SOCKS-прокси Tor. Поэтому в анонимном режиме (`calls.mediaOverTor`)
 * мы поднимаем этот форвардер к onion-адресу coturn и отдаём WebRTC ICE-сервер
 * `turn:<bindHost>:<localPort>?transport=tcp`. Предпочтительный [bindHost] —
 * `127.0.0.2`: `127.0.0.1` и свой WLAN-IP на Android 15/16 + Samsung роняют
 * native TurnPort (SIGSEGV). Байты всё равно уходят в Tor. RTP — UDP, Tor — TCP —
 * поэтому форсируется TCP-TURN (RELAY). Медиа остаётся E2EE (DTLS-SRTP); Tor и
 * coturn видят только шифртекст (zero-trust, §10.9).
 *
 * Best-effort и «тихий»: любые сетевые ошибки закрывают конкретный канал, не роняя
 * форвардер и звонок. [close] останавливает листенер и рвёт все активные каналы.
 */
class TorTcpForwarder(
    private val socksPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    parentScope: CoroutineScope,
    private val bindHost: String = LocalBindAddress.LOOPBACK_ALT,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    @Volatile private var serverSocket: ServerSocket? = null

    /** Все открытые сокеты (loopback + upstream), чтобы разом закрыть в [close]. */
    private val liveSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Уже открытый SOCKS-туннель, чтобы первое TCP-подключение WebRTC сразу
     * попало в coturn. Иначе accept() завершает handshake к localhost мгновенно,
     * а onion ещё 2–5 с: native TurnPort шлёт Allocate в чёрную дыру, по STUN RTO
     * рвёт порт — на Samsung это SIGSEGV без Java-стека.
     */
    private val preconnected: ArrayDeque<Socket> = ArrayDeque()

    /**
     * Поднимает листенер на [bindHost] (порт назначает ОС) и запускает цикл приёма.
     * Возвращает назначенный локальный порт. Бросает при неудаче bind или если
     * заранее открыть SOCKS к onion не удалось.
     */
    fun start(): Int {
        // Достаточно ОДНОГО живого туннеля, чтобы отдать порт WebRTC: остальные
        // догоняются в фоне ([replenish]). Прежде здесь синхронно открывались
        // все PRECONNECT_COUNT, и каждый непрошедший SOCKS-хендшейк (до трёх
        // попыток по 400/800 мс) добавлялся к времени установки звонка.
        val first = connectUpstream() ?: error("не удалось открыть SOCKS к $targetHost:$targetPort")
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(bindHost), 0))
            serverSocket = server
            synchronized(preconnected) { preconnected.addLast(first) }
            val local = server.localPort
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break // сокет закрыт в close() или ошибка — выходим из цикла.
                    }
                    launch(Dispatchers.IO) { handle(client) }
                }
            }
            replenish()
            val loopback = LocalBindAddress.isLoopbackHost(bindHost)
            Log.i(TAG, "TCP-форвардер запущен: ${CallTrace.hostKind(bindHost)}:$local → onion:$targetPort (через Tor)")
            DiagnosticLog.sync(
                DIAG_TAG, "форвардер готов",
                "локальныйПорт" to local,
                "прогрет" to 1,
                "цельПрогрева" to PRECONNECT_COUNT,
                "loopback" to loopback,
                "hostKind" to CallTrace.hostKind(bindHost),
            )
            return local
        } catch (t: Throwable) {
            runCatching { first.close() }
            throw t
        }
    }

    /**
     * Доводит пул прогретых туннелей до [PRECONNECT_COUNT] в фоне.
     *
     * WebRTC открывает по одному TCP-соединению НА КАЖДЫЙ TurnPort, а порты
     * создаются по числу сетей устройства (на телефоне с мобильным интернетом и
     * активным VPN это `any`, `tun0`, `rmnet_data0`). Лишние аллокации потом
     * отсекает `turnPortPrunePolicy`, но СОЕДИНЕНИЯ к нам они открывают до
     * этого — и пул на два туннеля означал, что часть портов ждала свежего
     * SOCKS-хендшейка через Tor (2–5 с) и успевала получить STUN-таймаут ещё до
     * первого ответа coturn.
     *
     * Работа идёт в [scope], поэтому [close] обрывает догрев вместе со звонком.
     */
    private fun replenish() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val need = synchronized(preconnected) { PRECONNECT_COUNT - preconnected.size }
                if (need <= 0) return@launch
                val socket = connectUpstream() ?: return@launch
                var kept = false
                synchronized(preconnected) {
                    if (preconnected.size < PRECONNECT_COUNT) {
                        preconnected.addLast(socket)
                        kept = true
                    }
                }
                if (!kept) {
                    runCatching { socket.close() }
                    return@launch
                }
            }
        }
    }

    private fun takeUpstream(): Socket? {
        val pooled = synchronized(preconnected) {
            if (preconnected.isNotEmpty()) preconnected.removeFirst() else null
        }
        if (pooled != null) {
            // Место в пуле освободилось — доливаем, пока идёт сборка ICE.
            replenish()
            // Туннель мог умереть, пока лежал в пуле (цепь Tor перестроилась).
            if (!pooled.isClosed && pooled.isConnected) return pooled
            runCatching { pooled.close() }
        }
        return connectUpstream()
    }

    private fun handle(client: Socket) {
        val fromPool = synchronized(preconnected) { preconnected.isNotEmpty() }
        DiagnosticLog.sync(
            DIAG_TAG, "форвардер клиент",
            "локальный" to client.localPort,
            "прогрет" to fromPool,
        )
        val upstream = takeUpstream() ?: run {
            DiagnosticLog.sync(DIAG_TAG, "форвардер нет upstream")
            runCatching { client.close() }
            return
        }
        DiagnosticLog.sync(DIAG_TAG, "форвардер туннель", "прогрет" to fromPool)
        runCatching { client.soTimeout = 0 }
        runCatching { client.tcpNoDelay = true }
        runCatching { client.keepAlive = true }
        runCatching { upstream.tcpNoDelay = true }
        runCatching { upstream.keepAlive = true }
        liveSockets.add(client)
        liveSockets.add(upstream)

        // Два независимых насоса; когда любой закончится (EOF/ошибка) — рвём оба,
        // чтобы разблокировать встречное направление.
        val up = scope.launch(Dispatchers.IO) { pump(client, upstream, "к-onion") }
        val down = scope.launch(Dispatchers.IO) { pump(upstream, client, "к-webrtc") }
        scope.launch(Dispatchers.IO) {
            up.join()
            down.join()
            DiagnosticLog.sync(DIAG_TAG, "форвардер канал закрыт")
            closeBoth(client, upstream)
        }
    }

    private fun pump(from: Socket, to: Socket, direction: String) {
        try {
            val input: InputStream = from.getInputStream()
            val output: OutputStream = to.getOutputStream()
            val buf = ByteArray(BUFFER_SIZE)
            var first = true
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (first) {
                    first = false
                    DiagnosticLog.sync(DIAG_TAG, "форвардер первый пакет", "направление" to direction, "байт" to n)
                }
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            DiagnosticLog.event(DIAG_TAG, "форвардер насос конец", "направление" to direction, "ошибка" to (e.javaClass.simpleName))
        } finally {
            closeBoth(from, to)
        }
    }

    private fun closeBoth(a: Socket, b: Socket) {
        liveSockets.remove(a)
        liveSockets.remove(b)
        runCatching { a.close() }
        runCatching { b.close() }
    }

    /** Останавливает листенер и рвёт все активные каналы. Идемпотентно. */
    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(preconnected) {
            preconnected.forEach { runCatching { it.close() } }
            preconnected.clear()
        }
        synchronized(liveSockets) {
            liveSockets.forEach { runCatching { it.close() } }
            liveSockets.clear()
        }
        job.cancel()
    }

    /**
     * SOCKS CONNECT к onion через Tor. Цепочка часто не сходится с первой попытки
     * (rep=5 / timeout) — без ретрая WebRTC остаётся без relay-кандидата.
     */
    private fun connectUpstream(): Socket? {
        var last: Exception? = null
        repeat(SOCKS_ATTEMPTS) { attempt ->
            if (!job.isActive) return null
            try {
                // soTimeout=0 после handshake: потоковый туннель на весь звонок.
                return Socks5.connect(socksPort, targetHost, targetPort, soTimeoutMs = 0)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "SOCKS попытка ${attempt + 1}/$SOCKS_ATTEMPTS: ${e.message}")
                DiagnosticLog.w(DIAG_TAG) {
                    "форвардер SOCKS попытка ${attempt + 1}/$SOCKS_ATTEMPTS: ${e.message}"
                }
                if (attempt < SOCKS_ATTEMPTS - 1) {
                    Thread.sleep(SOCKS_RETRY_DELAY_MS * (attempt + 1L))
                }
            }
        }
        DiagnosticLog.e(DIAG_TAG, last) { "форвардер: SOCKS к onion не открылся" }
        return null
    }

    private companion object {
        const val TAG = "AegisTurnForwarder"
        const val DIAG_TAG = "turn-fwd"
        const val BUFFER_SIZE = 16 * 1024
        const val SOCKS_ATTEMPTS = 3
        const val SOCKS_RETRY_DELAY_MS = 400L

        /**
         * Цель размера пула прогретых SOCKS-туннелей.
         *
         * Четыре — с запасом на число сетей устройства: каждый TurnPort открывает
         * своё TCP-соединение к нашему листенеру ещё до того, как политика
         * отсечения оставит одну аллокацию, и ни одно из них не должно ждать
         * хендшейк через Tor.
         *
         * Стоимость: одно TCP-соединение к onion на каждый неиспользованный
         * туннель, живущее ровно до конца звонка ([close]).
         */
        const val PRECONNECT_COUNT = 4
    }
}
