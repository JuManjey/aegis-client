package com.aegis.messenger.messaging

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever

/**
 * Извлекает превью-кадр видео (§14.4, follow-up вложений) ПРЯМО ИЗ ПАМЯТИ, без
 * записи расшифрованного файла на диск (§6.3): [MediaMetadataRetriever] читает
 * байты через [MediaDataSource] поверх [ByteArray], поэтому plaintext видео нигде
 * не материализуется во временный файл. Кадр — только в оперативной памяти.
 *
 * Используется для показа миниатюры видео-вложения в пузыре чата (вместо простой
 * карточки-файла) с наложенной иконкой «play». Всё best-effort: если контейнер не
 * распознан/повреждён — возвращает `null`, UI откатывается на карточку файла.
 */
object VideoFrameDecoder {
    /**
     * Первый ключевой кадр видео из [bytes] или `null`. Тяжёлая операция —
     * вызывать вне UI-потока (декодирование средствами платформы).
     */
    fun firstFrame(bytes: ByteArray): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ByteArrayMediaDataSource(bytes))
            // OPTION_CLOSEST_SYNC у нулевой позиции — самый дешёвый кадр; при
            // отсутствии — общий getFrameAtTime как запасной вариант.
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Источник данных для [MediaMetadataRetriever] поверх байтового массива в
     * памяти — исключает промежуточный plaintext-файл на диске (§6.3).
     */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= data.size) return -1
            val available = (data.size - position).toInt()
            val count = minOf(size, available)
            if (count <= 0) return -1
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() { /* нечего освобождать: данные владеет вызывающий */ }
    }
}
