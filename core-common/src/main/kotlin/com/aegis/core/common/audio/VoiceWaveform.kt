package com.aegis.core.common.audio

import java.util.Base64

/**
 * Огибающая громкости голосового сообщения (§14.4) — компактный «рисунок звука»
 * для полосок в пузыре чата.
 *
 * ЗАЧЕМ ОНА ЕДЕТ ПО ПРОВОДУ. Нарисовать огибающую можно и локально, но только
 * ПОСЛЕ скачивания и декодирования блоба. Входящее голосовое до автозагрузки
 * выглядело бы пустой полосой, а декодирование ради картинки стоило бы полного
 * прохода AAC-декодером на каждый пузырь. Поэтому отправитель считает её один
 * раз прямо из PCM во время записи и кладёт в [com.aegis.core.common.model.VoiceMeta]
 * — то есть ВНУТРЬ E2EE-конверта: релею она недоступна (§7.6/§10.9).
 *
 * ФОРМАТ v1: ровно [BUCKETS] байт, каждый — пиковая громкость своего отрезка
 * записи в шкале 0..255, закодированные base64 без паддинга (75 символов).
 * Фиксированная длина важна: получатель рисует полоски, не зная длительности, а
 * ширина пузыря от неё не зависит.
 *
 * Обе операции ([encode]/[decode]) намеренно fail-soft: повреждённая или чужая
 * (из будущей версии) строка даёт «тихую» огибающую, а не исключение — голосовое
 * всё равно должно проигрываться.
 */
object VoiceWaveform {
    /**
     * Число столбиков огибающей. 56 — компромисс: на экране 360dp пузырь
     * голосового шириной ~260dp даёт столбик ~3dp (штрих + зазор, различимо
     * пальцем), а строка base64 остаётся в пределах 76 символов, то есть
     * незаметна на фоне конверта Signal.
     */
    const val BUCKETS: Int = 56

    /** Верхняя граница шкалы громкости одного столбика. */
    const val MAX_LEVEL: Int = 255

    /**
     * Минимальный видимый уровень: полностью нулевой столбик выглядит как
     * «дырка» в дорожке, поэтому тишина рисуется тонкой чертой, а не пустотой.
     */
    const val MIN_VISIBLE_LEVEL: Int = 8

    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /**
     * Кодирует уровни в строку формата v1. Вход любой длины приводится к
     * [BUCKETS] ([resample]), значения клемпятся в 0..[MAX_LEVEL].
     */
    fun encode(levels: IntArray): String {
        if (levels.isEmpty()) return ""
        val resampled = resample(levels, BUCKETS)
        val bytes = ByteArray(BUCKETS) { i ->
            resampled[i].coerceIn(0, MAX_LEVEL).toByte()
        }
        return encoder.encodeToString(bytes)
    }

    /**
     * Разбирает строку формата v1 в ровно [BUCKETS] уровней 0..[MAX_LEVEL].
     *
     * Строка другой длины НЕ отбрасывается, а пересэмплируется: так огибающая от
     * клиента с другим [BUCKETS] (будущая версия) всё равно нарисуется. Мусор
     * или пустая строка → `null`, вызывающий подставляет [synthetic].
     */
    fun decode(b64: String): IntArray? {
        if (b64.isBlank()) return null
        val raw = runCatching { decoder.decode(b64.trim()) }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        val levels = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        return if (levels.size == BUCKETS) levels else resample(levels, BUCKETS)
    }

    /**
     * Приводит произвольное число измерений к `target` столбикам.
     *
     * Берётся МАКСИМУМ по отрезку, а не среднее: усреднение «съедает» короткие
     * всплески речи, и осциллограмма разговора превращается в ровную колбасу,
     * по которой невозможно оценить, где в записи пауза.
     */
    fun resample(source: IntArray, target: Int = BUCKETS): IntArray {
        require(target > 0) { "target должен быть положительным" }
        if (source.isEmpty()) return IntArray(target)
        if (source.size == target) return source.copyOf()
        val out = IntArray(target)
        for (i in 0 until target) {
            // Границы отрезка считаем в Long: source.size * target у длинной
            // записи (минуты × 20 измерений/с) переполняет Int.
            val from = (i.toLong() * source.size / target).toInt()
            val toExclusive = (((i + 1).toLong() * source.size / target).toInt())
                .coerceAtLeast(from + 1)
                .coerceAtMost(source.size)
            var peak = 0
            for (j in from until toExclusive) {
                if (source[j] > peak) peak = source[j]
            }
            out[i] = peak
        }
        return out
    }

    /**
     * Растягивает огибающую по фактическому максимуму, чтобы тихая запись не
     * выглядела плоской линией. Уровни ниже [MIN_VISIBLE_LEVEL] поднимаются до
     * него — тишина остаётся видимой чертой.
     *
     * Записи с почти нулевым максимумом (микрофон выключен/зажат) НЕ усиливаются:
     * иначе шум квантования растянулся бы в бодрую осциллограмму и пользователь
     * решил бы, что записалась речь.
     */
    fun normalize(levels: IntArray): IntArray {
        if (levels.isEmpty()) return levels
        val peak = levels.max()
        if (peak <= MIN_VISIBLE_LEVEL) {
            return IntArray(levels.size) { MIN_VISIBLE_LEVEL }
        }
        val scale = MAX_LEVEL.toDouble() / peak
        return IntArray(levels.size) { i ->
            (levels[i] * scale).toInt().coerceIn(MIN_VISIBLE_LEVEL, MAX_LEVEL)
        }
    }

    /**
     * Детерминированная псевдо-огибающая из строки-затравки (обычно
     * [com.aegis.core.common.model.AttachmentRef.objectId]).
     *
     * Нужна для аудио БЕЗ метаданных: вложение-аудиофайл из пикера и голосовые
     * от клиентов старых версий. Альтернативы хуже: пустая дорожка выглядит как
     * сломанный пузырь, а декодирование ради картинки — это полный проход
     * AAC-декодера на каждый видимый элемент списка.
     *
     * Детерминированность обязательна (иначе полоски «танцевали» бы на каждой
     * рекомпозиции), но это ЧИСТО ДЕКОРАЦИЯ: криптостойкости от FNV-1a здесь не
     * требуется, а сама затравка — уже опаковый случайный id, поэтому картинка
     * не раскрывает ничего о содержимом (ср. инвариант микро-кодов HUD,
     * `docs/ui_hud.md` §6).
     */
    fun synthetic(seed: String, buckets: Int = BUCKETS): IntArray {
        var hash = FNV_OFFSET
        for (ch in seed) {
            hash = (hash xor (ch.code.toLong() and 0xFF)) * FNV_PRIME
        }
        val out = IntArray(buckets)
        for (i in 0 until buckets) {
            hash = (hash xor (i.toLong() + 1)) * FNV_PRIME
            val noise = ((hash ushr 24) and 0xFF).toInt()
            // Огибающая речи: колокол по центру + шум, чтобы дорожка не читалась
            // как ровный забор и не притворялась настоящей осциллограммой.
            val position = i.toDouble() / (buckets - 1).coerceAtLeast(1)
            val envelope = 0.45 + 0.55 * kotlin.math.sin(position * Math.PI)
            out[i] = ((noise * envelope).toInt()).coerceIn(MIN_VISIBLE_LEVEL, MAX_LEVEL)
        }
        return out
    }

    /**
     * Огибающая для показа: из [waveformB64] либо синтетическая из [seed].
     * Единая точка, чтобы UI не повторял этот откат в каждом месте.
     */
    fun forDisplay(waveformB64: String?, seed: String): IntArray =
        waveformB64?.let { decode(it) } ?: synthetic(seed)

    /**
     * Пиковая громкость 16-битного PCM-фрагмента в шкале 0..[MAX_LEVEL].
     *
     * Берётся именно ПИК, а не RMS: столбик должен показывать «был ли здесь
     * звук», а RMS короткого согласного почти не отличается от тишины.
     * Читается little-endian — так отдаёт [android.media.AudioRecord] в
     * `ENCODING_PCM_16BIT`.
     */
    fun peakOfPcm16(buffer: ByteArray, lengthBytes: Int): Int {
        var peak = 0
        var i = 0
        val end = lengthBytes.coerceAtMost(buffer.size) - 1
        while (i < end) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            // Short.MIN_VALUE не имеет положительной пары — клемпим, иначе
            // abs() вернул бы отрицательное значение и пик «провалился» бы.
            val magnitude = if (sample.toShort() == Short.MIN_VALUE) {
                Short.MAX_VALUE.toInt()
            } else {
                kotlin.math.abs(sample.toShort().toInt())
            }
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return (peak * MAX_LEVEL / Short.MAX_VALUE.toInt()).coerceIn(0, MAX_LEVEL)
    }

    private const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME: Long = 0x100000001b3L
}
