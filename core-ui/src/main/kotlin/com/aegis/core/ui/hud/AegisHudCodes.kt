package com.aegis.core.ui.hud

/**
 * Декоративные микро-коды по углам HUD-панелей (`TRN_TCLAS_000090`,
 * `VER_M6AGT02` в референсе). Чистая типографика: они ничего не значат и никуда
 * не отправляются.
 *
 * ### Инвариант приватности (обязателен к соблюдению)
 *
 * В [trace] и [version] допустимо передавать ТОЛЬКО статический строковый
 * литерал-идентификатор экрана («chats», «chat.thread», «contacts»). Никогда —
 * `contactId`, никнейм, отпечаток ключа, onion-адрес, id сообщения или любое
 * другое значение, зависящее от данных пользователя.
 *
 * Причина: код отрисован в углу экрана и попадает на любой скриншот, в запись
 * экрана и в скриншот-превью системного переключателя задач. Код, выведенный из
 * `contactId`, превратил бы «украшение» в стабильный идентификатор переписки,
 * видимый снаружи E2EE (§6.3/§10 — метаданные не должны утекать в UI).
 *
 * Функции чистые и детерминированные: один и тот же экран всегда даёт один и тот
 * же код, поэтому интерфейс не «мигает» при рекомпозиции.
 */
object AegisHudCodes {

    /**
     * Левый верхний угол: «трассировка сессии».
     *
     * `toInt()` обязателен: `UInt` — inline-класс Kotlin, а не `java.lang.Number`,
     * и `%d` в `java.util.Formatter` бросил бы на нём
     * `IllegalFormatConversionException` уже в рантайме. Остаток по модулю
     * гарантирует, что значение влезает в положительный `Int`.
     */
    fun trace(slug: String): String {
        val value = (hash(slug, salt = TRACE_SALT) % TRACE_MODULO).toInt()
        return "TRN_TCLAS_%06d".format(value)
    }

    /** Правый верхний угол: «версия прошивки». */
    fun version(slug: String): String {
        val h = hash(slug, salt = VERSION_SALT)
        val body = buildString {
            var acc = h
            repeat(VERSION_LEN) {
                append(ALPHABET[(acc % ALPHABET.length.toUInt()).toInt()])
                acc /= ALPHABET.length.toUInt()
            }
        }
        return "VER_$body"
    }

    /**
     * FNV-1a (32 бит). Не криптография и не претендует на неё: нужна лишь
     * стабильная функция «строка → число» без обращения к `hashCode()`, значение
     * которого не зафиксировано контрактом JVM между версиями.
     */
    private fun hash(value: String, salt: UInt): UInt {
        var h = salt
        for (byte in value.encodeToByteArray()) {
            h = h xor byte.toUByte().toUInt()
            h *= FNV_PRIME
        }
        return h
    }

    private const val VERSION_LEN = 6

    /** Только заглавные латинские и цифры — как машинная маркировка в референсе. */
    private const val ALPHABET = "0123456789ABCDEFGHJKLMNPRSTUVWXYZ"

    /** Шесть десятичных разрядов в маркировке `TRN_TCLAS_……`. */
    private val TRACE_MODULO = 1_000_000u

    private val FNV_PRIME = 16_777_619u
    private val TRACE_SALT = 2_166_136_261u
    private val VERSION_SALT = 2_166_136_453u
}
