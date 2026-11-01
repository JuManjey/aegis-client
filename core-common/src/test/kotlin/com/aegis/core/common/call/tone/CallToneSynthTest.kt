package com.aegis.core.common.call.tone

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Тесты синтезатора тонов звонка.
 *
 * Звук проверить на слух в CI нельзя, поэтому проверяются инварианты, на
 * которые опирается проигрыватель ([com.aegis.messenger.call.CallTonePlayer]):
 * отсутствие клиппинга, бесшумный стык петли, детерминированность и то, что
 * тон вообще звучит (а не оказался тишиной после правки патча).
 */
class CallToneSynthTest {

    private val rate = CallToneSynth.SAMPLE_RATE_HZ

    @Test
    fun `каждый тон палитры рендерится в ожидаемое число кадров`() {
        for (tone in CallTone.entries) {
            val spec = CallTonePalette.spec(tone)
            val pcm = CallToneSynth.render(tone)
            val expected = (spec.totalMs.toLong() * rate / 1000L).toInt()
            assertEquals("длина тона ${tone.name}", expected, pcm.size)
            assertEquals("frameCount ${tone.name}", pcm.size, CallToneSynth.frameCount(tone))
        }
    }

    @Test
    fun `тон не клиппится и достигает заданного пика`() {
        for (tone in CallTone.entries) {
            val spec = CallTonePalette.spec(tone)
            val pcm = CallToneSynth.render(tone)
            val maxAbs = pcm.maxOf { abs(it.toInt()) }
            val target = (spec.peak * 32_767.0).roundToInt()
            assertTrue(
                "тон ${tone.name} превысил пик: $maxAbs > $target",
                maxAbs <= target,
            )
            // Нормализация обязана «дотянуть» сумму голосов до пика: если
            // дотянуть не удалось, патч потерял голос или ушёл в тишину.
            assertTrue(
                "тон ${tone.name} тише пика: $maxAbs вместо ~$target",
                maxAbs >= target - 2,
            )
        }
    }

    @Test
    fun `края буфера бесшумны — на стыке петли нет щелчка`() {
        for (tone in CallTone.entries) {
            val pcm = CallToneSynth.render(tone)
            assertEquals("первый кадр ${tone.name}", 0, pcm.first().toInt())
            assertEquals("последний кадр ${tone.name}", 0, pcm.last().toInt())
        }
    }

    @Test
    fun `зацикленные тоны заканчиваются паузой — каденция не сливается`() {
        val tailFrames = frames(200)
        for (tone in CallTone.entries.filter { it.looping }) {
            val pcm = CallToneSynth.render(tone)
            assertTrue("тон ${tone.name} короче хвоста", pcm.size > tailFrames)
            val tail = pcm.copyOfRange(pcm.size - tailFrames, pcm.size)
            val loudest = tail.maxOf { abs(it.toInt()) }
            assertEquals("хвост ${tone.name} не тишина: $loudest", 0, loudest)
        }
    }

    @Test
    fun `рендер детерминирован — байт в байт при повторе`() {
        for (tone in CallTone.entries) {
            val first = CallToneSynth.render(tone)
            val second = CallToneSynth.render(tone)
            assertTrue("тон ${tone.name} не воспроизводим", first.contentEquals(second))
        }
    }

    @Test
    fun `тон действительно звучит — энергия выше порога`() {
        for (tone in CallTone.entries) {
            val pcm = CallToneSynth.render(tone)
            // Средний по модулю уровень: защищает от патча, где звучит один
            // короткий щелчок, а остальное — тишина.
            val mean = pcm.sumOf { abs(it.toInt()).toLong() }.toDouble() / pcm.size
            assertTrue("тон ${tone.name} почти беззвучен: mean=$mean", mean > 100.0)
        }
    }

    @Test
    fun `шум детерминирован по зерну и различается между зёрнами`() {
        val base = ToneVoice(
            wave = ToneWave.NOISE,
            startMs = 0,
            durationMs = 100,
            amplitude = 0.5,
            attackMs = 0,
            releaseMs = 0,
            seed = 42L,
        )
        val spec = { voice: ToneVoice -> CallToneSpec(totalMs = 100, peak = 0.9, voices = listOf(voice)) }
        val a = CallToneSynth.render(spec(base))
        val b = CallToneSynth.render(spec(base))
        val c = CallToneSynth.render(spec(base.copy(seed = 43L)))
        assertTrue("одно зерно — один шум", a.contentEquals(b))
        assertTrue("разные зёрна — разный шум", !a.contentEquals(c))
    }

    @Test
    fun `глиссандо меняет частоту — начало и конец тона различны`() {
        val spec = CallToneSpec(
            totalMs = 400,
            peak = 0.9,
            voices = listOf(
                ToneVoice(
                    wave = ToneWave.SINE,
                    startMs = 0,
                    durationMs = 400,
                    fromHz = 200.0,
                    toHz = 800.0,
                    amplitude = 0.8,
                    attackMs = 1,
                    releaseMs = 1,
                ),
            ),
        )
        val pcm = CallToneSynth.render(spec)
        // Частота оценивается числом переходов через ноль: у восходящего
        // глиссандо во второй половине их должно быть заметно больше.
        // Для экспоненциальной развёртки 200→800 Гц отношение половин ≈ 2.0,
        // поэтому порог берём с запасом вниз — тест проверяет направление, а не
        // точную форму развёртки.
        val half = pcm.size / 2
        val first = zeroCrossings(pcm, 0, half)
        val second = zeroCrossings(pcm, half, pcm.size)
        assertTrue("глиссандо не поднялось: $first → $second", second > first * 3 / 2)
    }

    @Test
    fun `патч с голосом за пределами длительности отвергается`() {
        val overflowing = ToneVoice(
            wave = ToneWave.SINE,
            startMs = 900,
            durationMs = 200,
            fromHz = 440.0,
            amplitude = 0.5,
        )
        try {
            CallToneSpec(totalMs = 1000, peak = 0.5, voices = listOf(overflowing))
            fail("голос вышел за totalMs, но патч создался")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "непонятное сообщение: ${expected.message}",
                expected.message?.contains("totalMs") == true,
            )
        }
    }

    @Test
    fun `огибающая не выходит за пределы даже когда атака длиннее голоса`() {
        val spec = CallToneSpec(
            totalMs = 50,
            peak = 0.9,
            voices = listOf(
                ToneVoice(
                    wave = ToneWave.SQUARE,
                    startMs = 0,
                    durationMs = 50,
                    fromHz = 440.0,
                    amplitude = 0.9,
                    // Заведомо абсурдные значения: сумма вдвое больше голоса.
                    attackMs = 60,
                    releaseMs = 60,
                ),
            ),
        )
        val pcm = CallToneSynth.render(spec)
        assertEquals(frames(50), pcm.size)
        assertTrue("клиппинг при усечённой огибающей", pcm.all { abs(it.toInt()) <= 29_491 })
    }

    private fun frames(ms: Int): Int = (ms.toLong() * rate / 1000L).toInt()

    private fun zeroCrossings(pcm: ShortArray, from: Int, to: Int): Int {
        var count = 0
        for (i in (from + 1) until to) {
            if ((pcm[i - 1] < 0) != (pcm[i] < 0)) count++
        }
        return count
    }
}
