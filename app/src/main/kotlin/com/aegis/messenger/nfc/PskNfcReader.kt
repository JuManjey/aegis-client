package com.aegis.messenger.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import java.io.ByteArrayOutputStream

/**
 * Ридер обмена PSK по NFC (§6.2/P4): роль устройства, ПРИНИМАЮЩЕГО ключ.
 *
 * Через reader-mode подключается к HCE-«карте» собеседника ([PskHostApduService]),
 * выбирает AID и вычитывает JSON-нагрузку PSK кусками (chunked APDU). Обмен
 * локальный, без сети. Результат отдаётся в [onResult] на UI-потоке.
 */
class PskNfcReader(
    private val activity: Activity,
    private val onResult: (Result<String>) -> Unit,
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    private fun str(resId: Int): String = LocaleManager.wrap(activity).getString(resId)

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun enable() {
        adapter?.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    fun disable() {
        adapter?.disableReaderMode(activity)
    }

    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            deliver(Result.failure(IllegalStateException(str(R.string.nfc_err_no_isodep))))
            return
        }
        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS
            val selectResp = isoDep.transceive(PskNfcProtocol.buildSelectApdu())
            if (!endsWithOk(selectResp)) {
                deliver(Result.failure(IllegalStateException(str(R.string.nfc_err_not_showing))))
                return
            }
            val out = ByteArrayOutputStream()
            var block = 0
            while (true) {
                val resp = isoDep.transceive(PskNfcProtocol.buildReadApdu(block))
                if (!endsWithOk(resp)) {
                    deliver(Result.failure(IllegalStateException(str(R.string.nfc_err_read))))
                    return
                }
                val data = resp.copyOfRange(0, resp.size - 2)
                out.write(data)
                if (data.size < PskNfcProtocol.CHUNK) break
                block++
                if (block > MAX_BLOCKS) {
                    deliver(Result.failure(IllegalStateException(str(R.string.nfc_err_payload_too_big))))
                    return
                }
            }
            val json = out.toByteArray().decodeToString()
            deliver(Result.success(json))
        } catch (e: Exception) {
            deliver(Result.failure(e))
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private fun deliver(result: Result<String>) {
        activity.runOnUiThread { onResult(result) }
    }

    private fun endsWithOk(resp: ByteArray?): Boolean =
        resp != null && resp.size >= 2 &&
            resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()

    private companion object {
        const val TIMEOUT_MS = 5000
        const val MAX_BLOCKS = 255
    }
}
