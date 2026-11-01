package com.aegis.messenger.nfc

/**
 * Общие константы протокола обмена PSK по NFC (§6.2/P4) и держатель полезной
 * нагрузки для HCE-сервиса.
 *
 * ВАЖНО (модель угроз): NFC — это ЛОКАЛЬНЫЙ обмен при касании устройств, без
 * сети. HCE-сервис отдаёт PSK ТОЛЬКО когда пользователь явно открыл экран
 * показа ключа и положил нагрузку в [PskNfcPayloadHolder]. Вне этого окна
 * сервис отвечает «нет данных».
 */
internal object PskNfcProtocol {
    /** AID: 0xF0 || "AegisPSK". Должен совпадать с res/xml/psk_apduservice.xml. */
    val AID: ByteArray = byteArrayOf(
        0xF0.toByte(), 0x41, 0x65, 0x67, 0x69, 0x73, 0x50, 0x53, 0x4B,
    )

    /** Максимум байтов данных в одном ответе (оставляем запас до 256). */
    const val CHUNK: Int = 240

    // Инструкции.
    const val CLA_READ: Byte = 0x80.toByte()
    const val INS_READ: Byte = 0xB0.toByte()

    // Статус-слова (SW1 SW2).
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    val SW_ERROR = byteArrayOf(0x6F, 0x00)

    /** SELECT AID (по имени): 00 A4 04 00 Lc <AID> 00. */
    fun buildSelectApdu(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    /** READ блока [block]: 80 B0 P1 00 00 (Le=0x00 → до 256 байт ответа). */
    fun buildReadApdu(block: Int): ByteArray =
        byteArrayOf(CLA_READ, INS_READ, block.toByte(), 0x00, 0x00)

    fun isSelectApdu(apdu: ByteArray): Boolean =
        apdu.size >= 5 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() &&
            apdu[2] == 0x04.toByte() && apdu[3] == 0x00.toByte()

    fun isReadApdu(apdu: ByteArray): Boolean =
        apdu.size >= 2 && apdu[0] == CLA_READ && apdu[1] == INS_READ
}

/**
 * Потокобезопасный держатель полезной нагрузки PSK для HCE-сервиса.
 *
 * Экран показа ключа кладёт JSON-нагрузку через [set] на время показа и
 * ОБЯЗАТЕЛЬНО очищает её через [clear] при уходе с экрана — чтобы устройство
 * не раздавало PSK по касанию в фоне. Данные живут только в оперативной памяти.
 */
object PskNfcPayloadHolder {
    @Volatile
    private var payload: ByteArray? = null

    fun set(bytes: ByteArray) {
        payload = bytes.copyOf()
    }

    fun clear() {
        payload?.fill(0)
        payload = null
    }

    fun hasPayload(): Boolean = payload != null

    /** Возвращает срез [block] полезной нагрузки (или пустой массив за пределами). */
    fun chunk(block: Int): ByteArray {
        val data = payload ?: return ByteArray(0)
        val start = block * PskNfcProtocol.CHUNK
        if (start >= data.size) return ByteArray(0)
        val end = minOf(start + PskNfcProtocol.CHUNK, data.size)
        return data.copyOfRange(start, end)
    }
}
