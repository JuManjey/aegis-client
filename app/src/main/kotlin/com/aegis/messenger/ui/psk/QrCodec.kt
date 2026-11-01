package com.aegis.messenger.ui.psk

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Генерация QR-кода для ручного обмена PSK (§6.2/P4) через открытую библиотеку
 * ZXing (без сети, локально). QR рисуется чёрным по белому для надёжного
 * сканирования; показывается только лично и с автоскрытием (см. экран показа).
 */
internal fun encodeQrBitmap(content: String, sizePx: Int): Bitmap? {
    // Для плотного QR с бандлом слоя B (~2.8 КБ) сразу берём L — M физически
    // не влезает (лимит 2331 байт), и попытка только зря жжёт CPU.
    val levels = if (content.length > 1500) {
        arrayOf(ErrorCorrectionLevel.L)
    } else {
        arrayOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)
    }
    for (ecLevel in levels) {
        val bitmap = encodeWith(content, sizePx, ecLevel)
        if (bitmap != null) return bitmap
    }
    return null
}

private fun encodeWith(content: String, sizePx: Int, ecLevel: ErrorCorrectionLevel): Bitmap? =
    runCatching {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ecLevel,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
