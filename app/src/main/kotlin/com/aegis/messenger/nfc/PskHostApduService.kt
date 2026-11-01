package com.aegis.messenger.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * HCE-сервис обмена PSK по NFC при касании устройств (§6.2/P4).
 *
 * Роль «карты»: устройство, показывающее ключ, эмулирует смарт-карту и по
 * запросу устройства-«ридера» отдаёт JSON-нагрузку PSK кусками (chunked APDU).
 * Данные отдаются ТОЛЬКО когда пользователь открыл экран показа ключа и положил
 * нагрузку в [PskNfcPayloadHolder]; вне этого — ответ «нет данных». Обмен
 * локальный, без сети.
 */
class PskHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return PskNfcProtocol.SW_ERROR

        if (PskNfcProtocol.isSelectApdu(apdu)) {
            return if (PskNfcPayloadHolder.hasPayload()) {
                PskNfcProtocol.SW_OK
            } else {
                PskNfcProtocol.SW_NOT_FOUND
            }
        }

        if (PskNfcProtocol.isReadApdu(apdu)) {
            if (!PskNfcPayloadHolder.hasPayload()) return PskNfcProtocol.SW_NOT_FOUND
            val block = apdu[2].toInt() and 0xFF
            val chunk = PskNfcPayloadHolder.chunk(block)
            // Пустой chunk (за пределами данных) + SW_OK = сигнал «конец» для ридера.
            return chunk + PskNfcProtocol.SW_OK
        }

        return PskNfcProtocol.SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        // Соединение разорвано/переключено на другой AID — ничего не храним здесь.
    }
}
