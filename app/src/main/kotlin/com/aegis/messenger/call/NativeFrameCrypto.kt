package com.aegis.messenger.call

import android.util.Log
import org.webrtc.FrameDecryptor
import org.webrtc.FrameEncryptor

/**
 * Кадровый слой поверх DTLS-SRTP (§1.14, §7.5/§8/§10.10) — мост к нативному
 * C++-шиму `libaegis_frame.so`, который реализует
 * `webrtc::FrameEncryptorInterface`/`FrameDecryptorInterface` (insertable
 * streams) и шифрует каждый медиа-кадр ВТОРЫМ независимым AEAD на ключах звонка
 * (Signal-сессия + ручной PSK, см. [com.aegis.crypto.CallCrypto]).
 *
 * Зачем: DTLS-SRTP уже защищает медиа, но его ключи выводятся в DTLS-рукопожатии,
 * которым управляет недоверенный сервер сигналинга. Кадровый слой добавляет
 * шифрование на ключах, которых НЕТ в DTLS: компрометация DTLS-SRTP (или подмена
 * согласования сервером) не раскрывает медиа, пока цел out-of-band корень
 * (`callSecret` из Signal-канала) и PSK. Это единственный независимый крипто-слой
 * медиа (defense-in-depth, §4), симметричный слою A для текста.
 *
 * Свою крипту НЕ пишем (§1): AEAD (ChaCha20-Poly1305) — аудированный RustCrypto в
 * `libaegis_core.so`; C++-шим лишь реализует vtable WebRTC и маршалит буферы.
 *
 * Best-effort: если `libaegis_frame.so` не собрана — [isAvailable] = false.
 * На медиаконвейер этот шим НЕ навешивается: predсобранный WebRTC вызывает
 * vtable из чужого `.so` и падает SIGSEGV. Рабочий кадровый слой —
 * [CallFrameCryptor] (`org.webrtc.FrameCryptor` в той же .so, что WebRTC).
 */
object NativeFrameCrypto {

    private const val TAG = "AegisFrame"

    /** Загрузилась ли нативная библиотека шима (иначе слой недоступен). */
    private val libLoaded: Boolean = runCatching {
        System.loadLibrary("aegis_frame")
        true
    }.getOrElse {
        Log.w(TAG, "libaegis_frame.so не загрузилась — C++-шим недоступен (на провод идёт FrameCryptor): ${it.message}")
        false
    }

    /**
     * Доступен ли кадровый слой: библиотека загружена И нативный шим разрешил
     * символы AEAD из `libaegis_core.so` ([nativeAvailable]).
     */
    val isAvailable: Boolean by lazy {
        libLoaded && runCatching { nativeAvailable() }.getOrDefault(false)
    }

    /**
     * Создаёт [FrameEncryptor] для ОДНОГО `RtpSender` на исходящих ключах
     * ([keys.sendKey]/[keys.sendSalt]). Возвращает `null`, если слой недоступен
     * или ключи некорректны (тогда шифратор не навешивается).
     *
     * ВАЖНО: на каждый sender — свой объект (нативный объект живёт вместе с
     * sender'ом и не переиспользуется между дорожками).
     */
    fun newEncryptor(key: ByteArray, salt: ByteArray): FrameEncryptor? {
        if (!isAvailable) return null
        val ptr = runCatching { nativeCreateEncryptor(key, salt) }.getOrDefault(0L)
        return if (ptr != 0L) NativeEncryptor(ptr) else null
    }

    /** Создаёт [FrameDecryptor] для ОДНОГО `RtpReceiver` на входящих ключах. */
    fun newDecryptor(key: ByteArray, salt: ByteArray): FrameDecryptor? {
        if (!isAvailable) return null
        val ptr = runCatching { nativeCreateDecryptor(key, salt) }.getOrDefault(0L)
        return if (ptr != 0L) NativeDecryptor(ptr) else null
    }

    // Нативный указатель отдаётся WebRTC как есть; владение переходит к
    // RtpSender/RtpReceiver (scoped_refptr AddRef'ит объект, созданный с
    // refcount=0, и Release'ит его при уничтожении). Kotlin-обёртка НЕ
    // освобождает указатель сама (иначе double-free) — по контракту WebRTC.
    private class NativeEncryptor(private val ptr: Long) : FrameEncryptor {
        override fun getNativeFrameEncryptor(): Long = ptr
    }

    private class NativeDecryptor(private val ptr: Long) : FrameDecryptor {
        override fun getNativeFrameDecryptor(): Long = ptr
    }

    private external fun nativeAvailable(): Boolean

    /** @return указатель `webrtc::FrameEncryptorInterface*` (0 при ошибке). */
    private external fun nativeCreateEncryptor(key: ByteArray, salt: ByteArray): Long

    /** @return указатель `webrtc::FrameDecryptorInterface*` (0 при ошибке). */
    private external fun nativeCreateDecryptor(key: ByteArray, salt: ByteArray): Long
}
