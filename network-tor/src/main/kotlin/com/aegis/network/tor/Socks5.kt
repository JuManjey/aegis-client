package com.aegis.network.tor

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Минимальный клиент SOCKS5 (RFC 1928, только команда CONNECT) к локальному
 * прокси встроенного Tor (Arti, §7.2). Открывает TCP-туннель к целевому
 * `host:port` (обычно `.onion`) — резолвинг делает выходной узел Tor
 * (ATYP=DOMAINNAME), приложение НИКОГДА не делает локальный DNS (иначе утечка).
 *
 * Используется и [TorSocksClient] (HTTP-over-Tor: релей/mailbox), и
 * [TorTcpForwarder] (TCP-TURN через Tor для медиа звонков, §7.5). Аутентификация
 * SOCKS не нужна (Arti её не требует на localhost).
 */
internal object Socks5 {

    /**
     * Открывает соединение к `127.0.0.1:[socksPort]`, проходит SOCKS5-хендшейк и
     * команду CONNECT к [host]:[port]. Возвращает уже «протуннелированный» сокет:
     * дальше по нему можно читать/писать прикладные байты. При ошибке сокет
     * закрывается и бросается [IOException].
     *
     * @param connectTimeoutMs таймаут TCP-подключения к локальному прокси.
     * @param soTimeoutMs таймаут чтения по сокету (0 = бесконечно; для потоковых
     *   туннелей вроде TURN держим долгим/бесконечным).
     * @param onSocketCreated вызывается с ещё не подключённым сокетом — чтобы его
     *   можно было оборвать из другого потока уже во время SOCKS-хендшейка.
     */
    fun connect(
        socksPort: Int,
        host: String,
        port: Int,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        soTimeoutMs: Int = DEFAULT_SO_TIMEOUT_MS,
        handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        onSocketCreated: (Socket) -> Unit = {},
    ): Socket {
        val socket = Socket()
        try {
            onSocketCreated(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), connectTimeoutMs)
            // Пока идёт SOCKS handshake, нужен конечный таймаут: soTimeout=0
            // (потоковый TURN) иначе CONNECT к onion может зависнуть навсегда.
            socket.soTimeout = if (soTimeoutMs > 0) soTimeoutMs else handshakeTimeoutMs
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            handshake(out, input)
            request(out, input, host, port)
            socket.soTimeout = soTimeoutMs
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun handshake(out: OutputStream, input: InputStream) {
        out.write(byteArrayOf(0x05, 0x01, 0x00)) // VER=5, NMETHODS=1, METHOD=0 (no auth)
        out.flush()
        val ver = input.read()
        val method = input.read()
        if (ver != 0x05 || method != 0x00) {
            throw IOException("SOCKS5: сервер отклонил метод авторизации ($ver/$method)")
        }
    }

    private fun request(out: OutputStream, input: InputStream, host: String, port: Int) {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        require(hostBytes.size <= 255) { "SOCKS5: слишком длинный хост" }
        val req = ArrayList<Byte>(7 + hostBytes.size)
        req.add(0x05) // VER
        req.add(0x01) // CMD = CONNECT
        req.add(0x00) // RSV
        req.add(0x03) // ATYP = DOMAINNAME
        req.add(hostBytes.size.toByte())
        hostBytes.forEach { req.add(it) }
        req.add((port ushr 8 and 0xFF).toByte())
        req.add((port and 0xFF).toByte())
        out.write(req.toByteArray())
        out.flush()

        val ver = input.read()
        val rep = input.read()
        input.read() // RSV
        if (ver != 0x05 || rep != 0x00) {
            throw IOException("SOCKS5: CONNECT отклонён (rep=$rep)")
        }
        when (val atyp = input.read()) {
            0x01 -> skip(input, 4 + 2) // IPv4 + порт
            0x03 -> {
                val len = input.read()
                skip(input, len + 2)
            }
            0x04 -> skip(input, 16 + 2) // IPv6 + порт
            else -> throw IOException("SOCKS5: неизвестный ATYP в ответе ($atyp)")
        }
    }

    private fun skip(input: InputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0) {
                if (input.read() < 0) throw IOException("SOCKS5: неожиданный конец потока")
                remaining--
            } else {
                remaining -= skipped.toInt()
            }
        }
    }

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000
    private const val DEFAULT_SO_TIMEOUT_MS = 30_000
    private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 45_000
}
