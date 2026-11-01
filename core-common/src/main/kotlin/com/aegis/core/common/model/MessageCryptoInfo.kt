package com.aegis.core.common.model

import kotlinx.serialization.Serializable

/**
 * Фактически применённые слои шифрования конкретного сообщения.
 *
 * Записывается в момент отправки (после [encrypt]) или приёма (после успешного
 * [decrypt]) — не декларация «как должно быть», а след того, что конвейер
 * реально сделал. Старые сообщения без этой записи показывают в UI честный
 * «нет данных», а не выдуманный список алгоритмов.
 */
@Serializable
data class MessageCryptoInfo(
    /** Слой A: каскад AEAD на ручном PSK контакта. */
    val layerA: Boolean = false,
    /**
     * Тип конверта Signal слоя B: `PreKey` (X3DH/PQXDH, первое сообщение сессии)
     * или `Whisper` (Double Ratchet). Пусто, если слой B не применялся.
     */
    val layerBType: String = "",
    /** Человекочитаемое имя протокола слоя B. */
    val layerBProtocol: String = "",
    /**
     * Имена AEAD-слоёв каскада в порядке шифрования. Непусто только если
     * [layerA] истинен — тогда это ровно те алгоритмы, которыми зашифровали.
     */
    val cascadeAlgorithms: List<String> = emptyList(),
    /** Вложение дополнительно зашифровано тем же каскадом на случайном ключе. */
    val attachmentEncrypted: Boolean = false,
    /** Транспорт до релея (обычно Tor). */
    val transport: String = "",
    /**
     * Сессионная обёртка PSK (ChaCha20-Poly1305 на HKDF(PSK, identity)):
     * payload слоя B нельзя вскрыть без PSK и без тех ключей личности, которые
     * сторона реально увидела. Это не хук в KDF PQXDH (его нет в API libsignal).
     */
    val pskSessionSeal: Boolean = false,
) {
    /** Есть ли подтверждённая запись о хотя бы одном криптослое. */
    val recorded: Boolean get() = layerA || layerBType.isNotBlank() || pskSessionSeal
}
