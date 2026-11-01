package com.aegis.core.common.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кодек огибающей голосового сообщения (§14.4). */
class VoiceWaveformTest {

    @Test
    fun `encode decode roundtrip preserves levels`() {
        val levels = IntArray(VoiceWaveform.BUCKETS) { it * 4 % 256 }
        val decoded = VoiceWaveform.decode(VoiceWaveform.encode(levels))
        assertArrayEquals(levels, decoded)
    }

    /**
     * Длина строки предсказуема и мала: огибающая едет в КАЖДОМ конверте
     * голосового, и рост здесь напрямую бьёт по лимиту тела дропа на релее
     * (`AEGIS_MAX_BODY_BYTES`).
     */
    @Test
    fun `encoded form is compact and unpadded`() {
        val encoded = VoiceWaveform.encode(IntArray(VoiceWaveform.BUCKETS) { 128 })
        // 56 байт → 75 символов base64 без паддинга.
        assertEquals(75, encoded.length)
        assertTrue("паддинг '=' раздувает строку без пользы", !encoded.contains('='))
    }

    @Test
    fun `decode rejects garbage and empty input`() {
        assertNull(VoiceWaveform.decode(""))
        assertNull(VoiceWaveform.decode("   "))
        assertNull(VoiceWaveform.decode("!!! не base64 !!!"))
    }

    /**
     * Огибающая от клиента с ДРУГИМ числом столбиков не отбрасывается, а
     * пересэмплируется — иначе смена [VoiceWaveform.BUCKETS] в будущей версии
     * сделала бы все ранее отправленные голосовые «безформенными».
     */
    @Test
    fun `decode resamples foreign bucket count`() {
        // Кодируем «чужой» размер напрямую: encode() всегда приводит к BUCKETS,
        // поэтому через него такую строку не получить.
        val foreign = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(ByteArray(16) { 200.toByte() })
        val decoded = VoiceWaveform.decode(foreign)
        assertEquals(VoiceWaveform.BUCKETS, decoded?.size)
        assertTrue("уровни должны сохраниться при пересэмплировании", decoded!!.all { it == 200 })
    }

    /**
     * Пересэмплирование берёт МАКСИМУМ, а не среднее: короткий всплеск речи не
     * должен исчезать из картинки (ср. комментарий в [VoiceWaveform.resample]).
     */
    @Test
    fun `resample keeps peaks instead of averaging them`() {
        val source = IntArray(100) { if (it == 50) 255 else 0 }
        val out = VoiceWaveform.resample(source, target = 10)
        assertEquals(10, out.size)
        assertEquals("всплеск должен уцелеть", 255, out.max())
    }

    @Test
    fun `resample handles shorter source than target`() {
        val out = VoiceWaveform.resample(intArrayOf(10, 200), target = 8)
        assertEquals(8, out.size)
        assertEquals(200, out.max())
    }

    @Test
    fun `resample of empty source yields silence`() {
        val out = VoiceWaveform.resample(IntArray(0), target = 5)
        assertArrayEquals(IntArray(5), out)
    }

    /** Долгая запись: `source.size * target` не должно переполнять Int. */
    @Test
    fun `resample does not overflow on long recordings`() {
        // 5 минут при шаге 50 мс = 6000 измерений; 6000 * 56 влезает в Int, но
        // вычисление ведётся в Long, поэтому проверяем и заведомо большой вход.
        val source = IntArray(600_000) { it % 256 }
        val out = VoiceWaveform.resample(source, VoiceWaveform.BUCKETS)
        assertEquals(VoiceWaveform.BUCKETS, out.size)
        assertTrue("переполнение дало бы нули/отрицательные индексы", out.all { it > 0 })
    }

    @Test
    fun `normalize stretches quiet recording to full scale`() {
        val quiet = intArrayOf(10, 20, 30, 40)
        val loud = VoiceWaveform.normalize(quiet)
        assertEquals(VoiceWaveform.MAX_LEVEL, loud.max())
    }

    /**
     * Почти тихая запись НЕ усиливается: иначе шум квантования растянулся бы в
     * бодрую осциллограмму, и пользователь решил бы, что речь записалась.
     */
    @Test
    fun `normalize does not amplify silence into fake speech`() {
        val silence = intArrayOf(0, 1, 2, VoiceWaveform.MIN_VISIBLE_LEVEL)
        val out = VoiceWaveform.normalize(silence)
        assertTrue(out.all { it == VoiceWaveform.MIN_VISIBLE_LEVEL })
    }

    /** Тишина остаётся видимой чертой, а не «дыркой» в дорожке. */
    @Test
    fun `normalize keeps silence visible`() {
        val out = VoiceWaveform.normalize(intArrayOf(0, 255, 0))
        assertTrue(out.all { it >= VoiceWaveform.MIN_VISIBLE_LEVEL })
    }

    /**
     * Синтетическая огибающая ДЕТЕРМИНИРОВАНА: иначе полоски «танцевали» бы на
     * каждой рекомпозиции списка сообщений.
     */
    @Test
    fun `synthetic is deterministic per seed`() {
        val a = VoiceWaveform.synthetic("blob-id-0001")
        val b = VoiceWaveform.synthetic("blob-id-0001")
        assertArrayEquals(a, b)
        assertNotEquals(
            "разные вложения должны выглядеть по-разному",
            a.toList(),
            VoiceWaveform.synthetic("blob-id-0002").toList(),
        )
    }

    @Test
    fun `synthetic stays inside the visible scale`() {
        val out = VoiceWaveform.synthetic("seed")
        assertEquals(VoiceWaveform.BUCKETS, out.size)
        assertTrue(out.all { it in VoiceWaveform.MIN_VISIBLE_LEVEL..VoiceWaveform.MAX_LEVEL })
    }

    @Test
    fun `forDisplay falls back to synthetic when waveform is absent`() {
        val fallback = VoiceWaveform.forDisplay(null, "obj-1")
        assertArrayEquals(VoiceWaveform.synthetic("obj-1"), fallback)
        val blankFallback = VoiceWaveform.forDisplay("", "obj-1")
        assertArrayEquals(VoiceWaveform.synthetic("obj-1"), blankFallback)
    }

    @Test
    fun `forDisplay prefers the transmitted waveform`() {
        val levels = IntArray(VoiceWaveform.BUCKETS) { 77 }
        val shown = VoiceWaveform.forDisplay(VoiceWaveform.encode(levels), "obj-1")
        assertArrayEquals(levels, shown)
    }

    // --- Измерение громкости PCM --------------------------------------------

    @Test
    fun `peakOfPcm16 reads little endian samples`() {
        // Один сэмпл = Short.MAX_VALUE (0x7FFF) в little-endian.
        val buffer = byteArrayOf(0xFF.toByte(), 0x7F)
        assertEquals(VoiceWaveform.MAX_LEVEL, VoiceWaveform.peakOfPcm16(buffer, buffer.size))
    }

    @Test
    fun `peakOfPcm16 of silence is zero`() {
        assertEquals(0, VoiceWaveform.peakOfPcm16(ByteArray(64), 64))
    }

    /**
     * `Short.MIN_VALUE` не имеет положительной пары: без клемпа `abs()` вернул бы
     * отрицательное значение и пик «провалился» бы в ноль на самом громком сэмпле.
     */
    @Test
    fun `peakOfPcm16 handles Short MIN_VALUE without going negative`() {
        val buffer = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(VoiceWaveform.MAX_LEVEL, VoiceWaveform.peakOfPcm16(buffer, buffer.size))
    }

    /** Читается ровно `lengthBytes`, а не весь буфер: хвост от прошлого чтения не влияет. */
    @Test
    fun `peakOfPcm16 respects the declared length`() {
        val buffer = ByteArray(8)
        buffer[6] = 0xFF.toByte()
        buffer[7] = 0x7F
        assertEquals(0, VoiceWaveform.peakOfPcm16(buffer, lengthBytes = 6))
        assertEquals(VoiceWaveform.MAX_LEVEL, VoiceWaveform.peakOfPcm16(buffer, lengthBytes = 8))
    }

    @Test
    fun `peakOfPcm16 tolerates odd length and oversized declaration`() {
        val buffer = byteArrayOf(0xFF.toByte(), 0x7F, 0x11)
        assertEquals(VoiceWaveform.MAX_LEVEL, VoiceWaveform.peakOfPcm16(buffer, lengthBytes = 3))
        assertEquals(VoiceWaveform.MAX_LEVEL, VoiceWaveform.peakOfPcm16(buffer, lengthBytes = 999))
    }
}
