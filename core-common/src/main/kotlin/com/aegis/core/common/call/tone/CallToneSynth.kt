package com.aegis.core.common.call.tone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Рендерер тонов звонка: патч ([CallToneSpec]) → моно PCM 16 бит.
 *
 * Чистая арифметика без Android API — поэтому проверяется обычным JVM-тестом
 * (`core-common/src/test/.../CallToneSynthTest.kt`), а не на устройстве.
 *
 * ## Гарантии, на которые опирается проигрыватель
 *
 *  1. **Детерминированность.** Шум берётся из собственного ГПСЧ с зерном из
 *     патча ([ToneVoice.seed]) — ни `Random`, ни времени, ни состояния процесса.
 *     Один и тот же тон всегда звучит одинаково (и сравним в тесте).
 *  2. **Отсутствие клиппинга.** Сумма голосов нормируется к [CallToneSpec.peak],
 *     поэтому переполнения на стыке громких голосов не бывает по построению.
 *  3. **Бесшумные края.** К готовому буферу применяется микро-фейд
 *     [EDGE_FADE_MS]: на стыке петли и в начале/конце одиночного тона нет
 *     щелчка даже если патч «забыли» закончить тишиной.
 *
 * ## Порядок обработки в голосе
 *
 * `осциллятор (+расстроенный дубль) → тремоло → кольцевая модуляция →
 * прореживание (sample & hold) → квантование → огибающая → в сумму`.
 *
 * Прореживание стоит ПОСЛЕ модуляций сознательно: оно «замораживает» и их,
 * добавляя ступенчатость, — это и есть искомая цифровая грязь, а не побочный
 * эффект.
 */
object CallToneSynth {

    /**
     * Частота дискретизации. 48 кГц — нативная для WebRTC: тон, уходящий в
     * канал разговора ([CallToneRoute.CALL]), не проходит через ресемплер
     * системы, а значит звучит ровно так, как отрендерен.
     */
    const val SAMPLE_RATE_HZ: Int = 48_000

    /** Микро-фейд на краях буфера — страховка от щелчков (см. KDoc класса). */
    private const val EDGE_FADE_MS: Double = 1.5

    private const val TWO_PI = 2.0 * PI

    /** Максимум 16-битного знакового PCM. */
    private const val PCM_MAX = 32_767.0

    /** Рендерит тон палитры. */
    fun render(tone: CallTone, sampleRate: Int = SAMPLE_RATE_HZ): ShortArray =
        render(CallTonePalette.spec(tone), sampleRate)

    /** Рендерит произвольный патч в моно PCM 16 бит little-endian (как ShortArray). */
    fun render(spec: CallToneSpec, sampleRate: Int = SAMPLE_RATE_HZ): ShortArray {
        require(sampleRate > 0) { "sampleRate <= 0" }
        val totalFrames = frames(spec.totalMs, sampleRate)
        require(totalFrames > 0) { "пустой тон: totalMs=${spec.totalMs}" }

        val mix = DoubleArray(totalFrames)
        for (voice in spec.voices) {
            renderVoice(voice, mix, sampleRate)
        }
        normalize(mix, spec.peak)
        applyEdgeFade(mix, sampleRate)
        return toPcm16(mix)
    }

    /** Длительность тона в кадрах — проигрывателю нужна для таймингов/петли. */
    fun frameCount(tone: CallTone, sampleRate: Int = SAMPLE_RATE_HZ): Int =
        frames(CallTonePalette.spec(tone).totalMs, sampleRate)

    // --- Внутреннее ----------------------------------------------------------

    private fun frames(ms: Int, sampleRate: Int): Int =
        (ms.toLong() * sampleRate / 1000L).toInt()

    private fun renderVoice(voice: ToneVoice, mix: DoubleArray, sampleRate: Int) {
        val start = frames(voice.startMs, sampleRate)
        val length = frames(voice.durationMs, sampleRate)
        if (length <= 0 || start >= mix.size) return

        val attack = frames(voice.attackMs, sampleRate).coerceIn(0, length / 2)
        val release = frames(voice.releaseMs, sampleRate).coerceIn(0, length - attack)
        val noise = ToneNoise(voice.seed)
        val isNoise = voice.wave == ToneWave.NOISE
        val glides = !isNoise && voice.fromHz > 0.0 && voice.toHz > 0.0 &&
            voice.fromHz != voice.toHz
        val baseHz = if (isNoise) 0.0 else max(voice.fromHz, 0.0)
        val ratio = if (glides) voice.toHz / voice.fromHz else 1.0

        // Фазы в «оборотах» (0..1): так шаг за кадр равен hz/sampleRate, и нет
        // накопления ошибки от умножений на 2π внутри цикла.
        var phase = 0.0
        var detunedPhase = 0.0
        var ringPhase = 0.0
        var tremoloPhase = 0.0
        var held = 0.0

        val limit = min(length, mix.size - start)
        for (i in 0 until limit) {
            val progress = if (length > 1) i.toDouble() / (length - 1) else 0.0
            val hz = if (glides) voice.fromHz * ratio.pow(progress) else baseHz

            var sample = if (isNoise) noise.next() else oscillator(voice.wave, phase)
            if (!isNoise && voice.detuneHz != 0.0) {
                sample = (sample + oscillator(voice.wave, detunedPhase)) * 0.5
            }
            if (voice.tremoloHz > 0.0 && voice.tremoloDepth > 0.0) {
                // Униполярная (0..1) пульсация: амплитуду не инвертирует, то
                // есть тембр не «щёлкает» на каждом периоде тремоло.
                val lfo = 0.5 * (1.0 - cos(TWO_PI * tremoloPhase))
                sample *= 1.0 - voice.tremoloDepth + voice.tremoloDepth * lfo
            }
            if (voice.ringModHz > 0.0) {
                sample *= sin(TWO_PI * ringPhase)
            }
            if (voice.downsample > 1) {
                if (i % voice.downsample == 0) held = sample
                sample = held
            }
            if (voice.bitcrushBits in 1..15) {
                val steps = (1 shl voice.bitcrushBits) - 1
                sample = (sample * steps).roundToLong().toDouble() / steps
            }

            mix[start + i] += sample * envelope(i, length, attack, release) * voice.amplitude

            phase = wrap(phase + hz / sampleRate)
            if (voice.detuneHz != 0.0) detunedPhase = wrap(detunedPhase + (hz + voice.detuneHz) / sampleRate)
            if (voice.ringModHz > 0.0) ringPhase = wrap(ringPhase + voice.ringModHz / sampleRate)
            if (voice.tremoloHz > 0.0) tremoloPhase = wrap(tremoloPhase + voice.tremoloHz / sampleRate)
        }
    }

    /** Значение осциллятора по фазе в оборотах (0..1). Диапазон — [-1, 1]. */
    private fun oscillator(wave: ToneWave, phase: Double): Double = when (wave) {
        ToneWave.SINE -> sin(TWO_PI * phase)
        ToneWave.SQUARE -> if (phase < 0.5) 1.0 else -1.0
        ToneWave.SAW -> 2.0 * phase - 1.0
        ToneWave.TRIANGLE -> if (phase < 0.5) 4.0 * phase - 1.0 else 3.0 - 4.0 * phase
        // Шум обрабатывается отдельно (нужен ГПСЧ), сюда не попадает.
        ToneWave.NOISE -> 0.0
    }

    /**
     * Трапеция attack/sustain/release.
     *
     * Первый и последний кадр голоса дают РОВНО ноль (`index = 0` и
     * `remaining = 0`): голос обязан начинаться и заканчиваться тишиной, иначе
     * его вступление посреди буфера — это скачок амплитуды, то есть щелчок.
     */
    private fun envelope(index: Int, length: Int, attack: Int, release: Int): Double {
        val rise = if (attack > 0 && index < attack) index.toDouble() / attack else 1.0
        val remaining = length - 1 - index
        val fall = if (release > 0 && remaining < release) remaining.toDouble() / release else 1.0
        return min(rise, fall)
    }

    private fun wrap(phase: Double): Double = phase - kotlin.math.floor(phase)

    /**
     * Приводит пик суммы к [peak]. Одним скаляром на весь буфер: внутренний
     * баланс голосов сохраняется, меняется только итоговая громкость тона.
     */
    private fun normalize(mix: DoubleArray, peak: Double) {
        var maxAbs = 0.0
        for (value in mix) {
            val a = abs(value)
            if (a > maxAbs) maxAbs = a
        }
        if (maxAbs <= 0.0) return
        val gain = peak / maxAbs
        for (i in mix.indices) mix[i] *= gain
    }

    /**
     * Микро-фейд на краях буфера. Нарастание начинается РОВНО с нуля, поэтому
     * первый и последний кадр гарантированно нулевые — на этом держится
     * бесшумный стык аппаратной петли: `AudioTrack.setLoopPoints` переход не
     * сглаживает, он просто возвращает курсор в начало буфера.
     */
    private fun applyEdgeFade(mix: DoubleArray, sampleRate: Int) {
        val fade = min((EDGE_FADE_MS * sampleRate / 1000.0).roundToInt(), mix.size / 2)
        if (fade <= 0) return
        for (i in 0 until fade) {
            val gain = i.toDouble() / fade
            mix[i] *= gain
            mix[mix.size - 1 - i] *= gain
        }
    }

    private fun toPcm16(mix: DoubleArray): ShortArray {
        val pcm = ShortArray(mix.size)
        for (i in mix.indices) {
            pcm[i] = (mix[i] * PCM_MAX).roundToInt().coerceIn(-32_768, 32_767).toShort()
        }
        return pcm
    }

    /**
     * ГПСЧ шума: xorshift64. Свой, а не `java.util.Random`, по двум причинам —
     * нужна побитовая повторяемость независимо от версии платформы и не нужна
     * блокировка на каждый кадр (`Random` синхронизирован).
     */
    private class ToneNoise(seed: Long) {
        private var state: Long = if (seed == 0L) FALLBACK_SEED else seed

        /** Следующий отсчёт шума в диапазоне [-1, 1). */
        fun next(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            // 53 старших бита → [0, 1) без потери точности в Double.
            val unit = (state ushr 11).toDouble() / MANTISSA_SCALE
            return unit * 2.0 - 1.0
        }

        private companion object {
            const val FALLBACK_SEED = 0x2545F4914F6CDD1DL
            const val MANTISSA_SCALE = 9_007_199_254_740_992.0 // 2^53
        }
    }
}
