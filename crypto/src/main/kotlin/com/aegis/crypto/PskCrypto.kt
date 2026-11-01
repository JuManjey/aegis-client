package com.aegis.crypto

import android.util.Base64

/** Бросается при ошибке нативной операции с PSK (P4, §6.2). */
class PskCryptoException(message: String) : Exception(message)

/**
 * Kotlin-фасад для ручного обмена ключом каскадного контейнера (PSK, P4, §6.2/§10.3).
 *
 * PSK — общий секрет слоя A ([com.aegis.crypto.cascade.CascadeCipher]). По модели
 * угроз он обменивается СТРОГО вручную и вне сети (QR лично / мнемоника / NFC) и
 * НИКОГДА не уходит в сеть. Этот фасад даёт только локальные примитивы:
 *  - [generate] — новый 256-бит PSK из ОС-ГСЧ;
 *  - [toMnemonic] / [fromMnemonic] — кодирование PSK в BIP-39-подобную фразу;
 *  - [fingerprint] / [fingerprintHex] — необратимый отпечаток для повторной сверки.
 *
 * Реальная криптография — в аудированных Rust-крейтах ([NativeAegisCore]); здесь
 * только «клей» JNI (свою криптографию не пишем, §1). Байты PSK, полученные тут,
 * вызывающий обязан затирать сам после использования/запечатывания в Keystore.
 */
object PskCrypto {

    /** Длина PSK в байтах (256 бит), синхронно с `psk::PSK_LEN` в Rust. */
    const val PSK_LENGTH: Int = 32

    /** Готово ли нативное ядро. */
    val isReady: Boolean get() = NativeAegisCore.isAvailable

    private fun requireReady() {
        if (!NativeAegisCore.isAvailable) throw CryptoUnavailableException()
    }

    /** Генерирует новый 256-битный PSK. Вызывающий отвечает за его затирание. */
    fun generate(): ByteArray {
        requireReady()
        val b64 = NativeAegisCore.nativeGeneratePskB64()
        if (b64.isBlank()) throw PskCryptoException("Не удалось сгенерировать PSK")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    /** base64-представление PSK (для передачи в хранилище/слой A). */
    fun toBase64(psk: ByteArray): String = Base64.encodeToString(psk, Base64.NO_WRAP)

    /** Декодирует PSK из base64. */
    fun fromBase64(pskB64: String): ByteArray = Base64.decode(pskB64.trim(), Base64.NO_WRAP)

    /** Кодирует PSK в BIP-39-подобную мнемонику (24 слова для 256-бит PSK). */
    fun toMnemonic(psk: ByteArray): String {
        requireReady()
        val phrase = NativeAegisCore.nativePskToMnemonic(toBase64(psk))
        if (phrase.isBlank()) throw PskCryptoException("Не удалось закодировать PSK в мнемонику")
        return phrase
    }

    /** Восстанавливает PSK из мнемоники; `null` при неверной фразе (штатный сигнал). */
    fun fromMnemonic(phrase: String): ByteArray? {
        requireReady()
        val b64 = NativeAegisCore.nativePskFromMnemonicB64(phrase.trim().lowercase())
        return if (b64.isBlank()) null else Base64.decode(b64, Base64.NO_WRAP)
    }

    /** Необратимый отпечаток PSK (32 байта) для повторной сверки сторон (§6.2). */
    fun fingerprint(psk: ByteArray): ByteArray {
        requireReady()
        val b64 = NativeAegisCore.nativePskFingerprintB64(toBase64(psk))
        if (b64.isBlank()) throw PskCryptoException("Не удалось вычислить отпечаток PSK")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    /** Отпечаток PSK в hex (для хранения/сравнения). */
    fun fingerprintHex(psk: ByteArray): String = fingerprint(psk).toHex()

    /**
     * Идентификатор mailbox релея из PSK и направления (§7.6/§12.2), URL-safe
     * base64 без паддинга (пригоден как сегмент URL). [role] — 0/1: направленный
     * подканал переписки. Сам PSK в сеть НЕ уходит — только производный 256-бит id
     * (это capability: без PSK его не вычислить). Байты PSK вызывающий затирает сам.
     */
    fun mailboxId(psk: ByteArray, role: Int): String {
        requireReady()
        require(role == 0 || role == 1) { "role должен быть 0 или 1" }
        val b64 = NativeAegisCore.nativeMailboxIdB64(toBase64(psk), role)
        if (b64.isBlank()) throw PskCryptoException("Не удалось вычислить mailbox-id")
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Тип v2-подканала (мультидевайс §7.1.1), синхронно с `psk::MailboxKind` в Rust. */
    enum class MailboxKind(val code: Int) {
        /** Подканал переписки к устройству контакта (secret = PSK). */
        CONVERSATION(0),

        /** Подканал синхронизации к своему устройству (secret = self-sync секрет). */
        SELF_SYNC(1),

        /**
         * Ящик ПРИГЛАШЕНИЙ владельца тега обнаружения (secret = долгоживущий
         * тег, `docs/contact_requests.md`). Единственный подканал, адресуемый
         * БЕЗ PSK — им и разрывается круг «нет PSK ⇒ нет адреса ⇒ нечем
         * согласовать PSK». Ротация тега отзывает все выданные адреса.
         */
        INVITE(2),

        /**
         * Ящик ОТВЕТА на конкретное приглашение (secret = эфемерный `replyTag`
         * инициатора). Отдельный от [INVITE], чтобы ответ не раскрывал
         * долгоживущий тег инициатора.
         */
        REPLY(3),
    }

    /**
     * Per-device идентификатор mailbox v2 (мультидевайс §7.1.1,
     * `docs/multidevice.md` §4), URL-safe base64 без паддинга. Адресует
     * КОНКРЕТНОЕ устройство владельца ящика (у каждого свой входящий подканал —
     * деструктивный ack не задевает другие устройства). [secret] — PSK переписки
     * ([MailboxKind.CONVERSATION]) либо self-sync секрет аккаунта
     * ([MailboxKind.SELF_SYNC]); [scope] — accountId владельца (`aegis:<hex>`);
     * [deviceId] — 1..255. Секрет в сеть НЕ уходит — только производный id.
     */
    fun mailboxIdDevice(secret: ByteArray, kind: MailboxKind, scope: String, deviceId: Int): String {
        requireReady()
        require(deviceId in 1..255) { "deviceId должен быть 1..255" }
        val b64 = NativeAegisCore.nativeMailboxIdDeviceB64(toBase64(secret), kind.code, scope, deviceId)
        if (b64.isBlank()) throw PskCryptoException("Не удалось вычислить mailbox-id (v2)")
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Секрет синхронизации СВОИХ устройств из мнемоники (мультидевайс §7.1.1,
     * `docs/multidevice.md` §4.2). Общий всем линкованным устройствам,
     * неизвестен контактам; в сеть НЕ уходит. `null` при неверной фразе.
     * Вызывающий обязан затереть байты после использования.
     */
    fun deriveSelfSyncSecret(mnemonic: String, passphrase: String = ""): ByteArray? {
        requireReady()
        val b64 = NativeAegisCore.nativeDeriveSelfSyncSecretB64(mnemonic.trim(), passphrase)
        return if (b64.isBlank()) null else Base64.decode(b64, Base64.NO_WRAP)
    }

    /**
     * Отпечаток PSK, разбитый на группы (safety-number стиль) для сверки глазами.
     * Показываем усечённо (первые [groups] групп по 4 hex-символа).
     */
    fun fingerprintGrouped(pskFingerprintHex: String, groupSize: Int = 4, groups: Int = 8): String =
        pskFingerprintHex.uppercase().take(groupSize * groups).chunked(groupSize).joinToString(" ")

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }
}
