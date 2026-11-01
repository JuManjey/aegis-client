package com.aegis.network.tor

import java.security.MessageDigest

/**
 * Решение proof-of-work для публичных маршрутов каталога алиасов
 * (`aegis-server/src/pow.rs`, PROTOCOL.md §4.8).
 *
 * ЗАЧЕМ. Индекс публичного алиаса — `HKDF(нормализованный_ник)`, то есть
 * пространство имён перечислимо словарём по популярным никам. Скрыть это нельзя
 * (свойство публичного имени), но можно сделать невыгодным: релей требует
 * небольшой работы на каждый запрос. Лимит по IP там бесполезен — за Onion
 * Service все запросы приходят от локального tor-демона с одного адреса.
 *
 * СХЕМА (обязана совпадать с серверной побитово):
 * ```
 * epoch = floor(unix_secs / epochSecs)
 * h     = SHA-256("aegis/pow/v1" || path || epoch(u64 BE) || nonce)
 * годно, если у h не меньше `bits` ведущих нулевых битов
 * заголовок: X-Aegis-Pow: <epoch>:<nonce-hex>
 * ```
 *
 * Своя криптография здесь не пишется: SHA-256 берётся из платформы.
 */
object PowSolver {

    private const val LABEL = "aegis/pow/v1"

    /**
     * Потолок числа попыток. 20 бит ≈ миллион хешей (доли секунды), но при
     * завышенной сложности на сервере поиск не должен вешать приложение
     * навсегда — лучше честно вернуть `null` и показать ошибку.
     */
    private const val MAX_ATTEMPTS = 1L shl 26

    /**
     * Ищет решение для [path] и возвращает готовое значение заголовка, либо
     * `null`, если решение не найдено за [MAX_ATTEMPTS] попыток.
     *
     * [bits] `== 0` (PoW на релее выключен) → пустая строка: заголовок не нужен.
     * Блокирующая операция — вызывать на IO/Default-диспетчере.
     */
    fun solve(
        path: String,
        bits: Int,
        nowMs: Long = System.currentTimeMillis(),
        epochSecs: Long = 600L,
    ): String? {
        if (bits <= 0) return ""
        val epoch = Math.floorDiv(nowMs / 1000L, epochSecs)
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = LABEL.toByteArray(Charsets.US_ASCII) +
            path.toByteArray(Charsets.UTF_8) +
            epoch.toBigEndian()
        var counter = 0L
        while (counter < MAX_ATTEMPTS) {
            val nonce = counter.toBigEndian()
            digest.reset()
            digest.update(prefix)
            digest.update(nonce)
            if (leadingZeroBits(digest.digest()) >= bits) {
                return "$epoch:${nonce.toHex()}"
            }
            counter++
        }
        return null
    }

    /** Число ведущих нулевых битов — та же метрика, что проверяет сервер. */
    private fun leadingZeroBits(hash: ByteArray): Int {
        var bits = 0
        for (byte in hash) {
            val v = byte.toInt() and 0xff
            if (v == 0) {
                bits += 8
            } else {
                bits += Integer.numberOfLeadingZeros(v) - 24
                break
            }
        }
        return bits
    }

    private fun Long.toBigEndian(): ByteArray = ByteArray(8) { i ->
        ((this ushr (56 - i * 8)) and 0xff).toByte()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }
}
