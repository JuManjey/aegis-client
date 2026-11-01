package com.aegis.network.tor

import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.diag.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Общее для обоих транспортов релея: санитизация заголовков, редакция путей в
 * журнале, чтение тела с потолком. Защита живёт здесь, где формируются запросы,
 * а не в каждом вызывающем.
 */
object RelayHttp {

    const val CONNECT_TIMEOUT_MS: Int = 30_000
    const val DEFAULT_TIMEOUT_MS: Int = 30_000

    /**
     * Потолок тела ответа. Крупнейшее, что легитимно отдаёт релей, — блоб
     * вложения (`AEGIS_BLOB_MAX_BYTES`, кап сервера 32 МиБ); без потолка
     * недоверенный сервер мог бы исчерпать память бесконечным ответом.
     */
    const val MAX_RESPONSE_BYTES: Int = 40 * 1024 * 1024

    /** Тег записей транспорта в журнале диагностики. */
    const val DIAG_TAG: String = "relay-http"

    /**
     * Значение заголовка: только печатаемый ASCII. Это отсекает CR, LF и NUL,
     * которыми можно разорвать запрос и подставить лишние заголовки (HTTP request
     * splitting), если параметр попал сюда из внешних данных.
     */
    fun sanitizeHeaderValue(raw: String): String = raw.filter { it.code in 0x20..0x7E }.trim()

    /** Имя заголовка: только символы token по RFC 9110 — иначе `:` и пробел меняли бы строку заголовка. */
    fun sanitizeHeaderName(raw: String): String = raw.filter { it in TOKEN_CHARS }

    /** Заголовки, пригодные к отправке; пустые после санитизации выбрасываются. */
    fun sanitizedHeaders(headers: Map<String, String>): List<Pair<String, String>> =
        headers.mapNotNull { (name, value) ->
            val safeName = sanitizeHeaderName(name)
            val safeValue = sanitizeHeaderValue(value)
            if (safeName.isEmpty() || safeValue.isEmpty()) null else safeName to safeValue
        }

    /**
     * Путь для журнала: id ящика/блоба/бандла — это capability (кто знает id, тот
     * читает очередь), поэтому в журнал идёт только КЛАСС ресурса и отпечаток id,
     * а не сам id. Query-параметры (`after`, `wait`, `ttl`) безопасны и сохраняются.
     */
    fun redactPath(path: String): String {
        val query = path.substringAfter('?', "")
        val clean = path.substringBefore('?')
        val parts = clean.split('/').filter { it.isNotEmpty() }
        // /v1/<resource>/<opaque-id>[/suffix]
        val redacted = if (parts.size >= 3) {
            val tail = parts.drop(3).joinToString("/").let { if (it.isEmpty()) "" else "/$it" }
            "/${parts[0]}/${parts[1]}/${DiagnosticLog.fp(parts[2])}$tail"
        } else {
            clean
        }
        return if (query.isEmpty()) redacted else "$redacted?$query"
    }

    /**
     * Описание сетевой ошибки для журнала без адресов: имя хоста релея заменяется
     * меткой, IP-адреса маскируются. Сообщения платформы вида «failed to connect
     * to host/203.0.113.10» иначе унесли бы адрес сервера в выгружаемый журнал.
     */
    fun describeError(error: Throwable, host: String): String {
        fun clean(t: Throwable): String {
            val message = t.message.orEmpty()
                .let { if (host.isNotEmpty()) it.replace(host, "<релей>", ignoreCase = true) else it }
                .let(CallTrace::redactNet)
            return if (message.isEmpty()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
        }
        val cause = error.cause?.takeIf { it !== error }
        return if (cause == null) clean(error) else "${clean(error)} ← ${clean(cause)}"
    }

    /** Читает поток до конца, но не больше [limit] байт (сверх — [IOException]). */
    fun readLimited(input: InputStream, limit: Int = MAX_RESPONSE_BYTES): ByteArray {
        val buffer = ByteArrayOutputStream(4096)
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("ответ релея превышает $limit байт")
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private val TOKEN_CHARS: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()
}
