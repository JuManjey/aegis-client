package com.aegis.core.common.call.tone

/**
 * Звуковая палитра звонка (§7.5/§14.5/§14.6) — «гудки» и служебные тоны.
 *
 * ## Почему синтез, а не файлы в `res/raw`
 *
 * Тоны НЕ лежат в ресурсах и НЕ берутся из системы, а синтезируются на лету
 * ([CallToneSynth]) из декларативных «патчей» ([CallTonePalette]):
 *
 *  1. **Воспроизводимая сборка.** У проекта есть проверка детерминированности
 *     (`.github/workflows/reproducible-build.yml`). Ноль бинарных ассетов —
 *     ноль поводов для расхождения артефактов; тон задан кодом и проверяется
 *     обычным JVM-тестом.
 *  2. **Размер и F-Droid.** Девять зацикленных мелодий в 48 кГц — это мегабайты
 *     в APK. Патч занимает десятки строк.
 *  3. **Единый источник правды.** Тон и его смысл (фаза звонка) описаны рядом,
 *     в одном перечислении, а не в паре «файл + константа».
 *  4. **Никаких новых зависимостей** (§1): нужен только `kotlin.math`.
 *
 * Модуль намеренно платформонезависим (чистый Kotlin, без Android API) —
 * воспроизведение живёт в `:app` (`com.aegis.messenger.call.CallTonePlayer`),
 * а здесь только описание звука, которое можно отрендерить и проверить на JVM.
 *
 * ## Маршрутизация и приватность
 *
 * Тоны прогресса звонка идут по КАНАЛУ РАЗГОВОРА ([CallToneRoute.CALL], т.е. в
 * ухо, а не в громкоговоритель). Это не только привычное поведение телефонии,
 * но и требование §10.10: узнаваемый сигнал AEGIS из динамика — это метаданные,
 * которые слышат окружающие. Громко звучит только рингтон входящего
 * ([CallToneRoute.RINGER]) — его задача ровно в том, чтобы его услышали.
 */
enum class CallTone(val route: CallToneRoute, val looping: Boolean) {
    /**
     * «Гудки» исходящего вызова (фаза `DIALING`). Зацикленный тон с каденцией
     * «два импульса — пауза»: два детюненых импульса с нисходящим глиссандо,
     * цифровой чирп-акцент и шумовой «шум эфира» в хвосте.
     */
    OUTGOING_RINGBACK(CallToneRoute.CALL, looping = true),

    /**
     * Согласование (фаза `CONNECTING`): тихие «пинги рукопожатия». Через Tor эта
     * фаза длится секунды, и тишина читается как зависание — тон показывает,
     * что работа идёт.
     */
    NEGOTIATING(CallToneRoute.CALL, looping = true),

    /** Медиапоток поднят (переход в `ACTIVE`): восходящий аккорд «канал открыт». */
    CONNECTED(CallToneRoute.CALL, looping = false),

    /** Путь потерян (`reconnecting`): тихое дрожащее «сигнал потерян», зацикленно. */
    LINK_LOST(CallToneRoute.CALL, looping = true),

    /** Путь восстановлен: короткий восходящий «релинк». */
    RELINK(CallToneRoute.CALL, looping = false),

    /** Штатное завершение: нисходящее «отключение питания». */
    HANGUP(CallToneRoute.CALL, looping = false),

    /** Собеседник занят: жёсткий двухтональный зуммер (ДНК классического «занято»). */
    BUSY(CallToneRoute.CALL, looping = false),

    /** Отклонено / нет ответа / сеть не пропустила: два резких нисходящих удара. */
    DENIED(CallToneRoute.CALL, looping = false),

    /**
     * Рингтон входящего звонка в стиле приложения — альтернатива системной
     * мелодии (переключатель `NotificationSettings.callSynthRingtone`).
     * Персональная мелодия контакта (§14.5), если задана, всегда важнее.
     */
    INCOMING_RING(CallToneRoute.RINGER, looping = true),
}

/**
 * Канал вывода тона. Определяет и громкость (какой ползунок системы им
 * управляет), и физический выход (ухо/динамик), и нужно ли забирать
 * аудиосессию звонка (`com.aegis.messenger.call.CallAudioSession`).
 */
enum class CallToneRoute {
    /** Канал разговора: ухо/гарнитура, громкость вызова, `MODE_IN_COMMUNICATION`. */
    CALL,

    /** Канал звонка: громкоговоритель, громкость рингтона. Режим не меняем. */
    RINGER,
}

/** Форма волны осциллятора. `NOISE` игнорирует частотные поля. */
enum class ToneWave {
    SINE,
    SQUARE,
    SAW,
    TRIANGLE,
    NOISE,
}

/**
 * Один «голос» патча: осциллятор + огибающая + эффекты на заданном отрезке
 * времени. Голоса складываются аддитивно, поэтому порядок в списке не важен.
 *
 * Поля с нулём/единицей по умолчанию означают «эффект выключен» — патч в
 * [CallTonePalette] читается как строка синтезатора, без лишнего шума.
 *
 * @param wave форма волны.
 * @param startMs смещение начала голоса от начала тона.
 * @param durationMs длительность голоса (вместе с атакой и затуханием).
 * @param fromHz частота в начале голоса (для `NOISE` не используется).
 * @param toHz частота в конце: `!= fromHz` даёт экспоненциальное глиссандо.
 * @param amplitude вклад голоса в сумму (0..1); итоговая громкость тона
 *   задаётся [CallToneSpec.peak] после нормализации.
 * @param attackMs нарастание от нуля — защищает от щелчка на старте.
 * @param releaseMs затухание в ноль — защищает от щелчка на стыке петли.
 * @param detuneHz расстройка второго осциллятора; даёт биения/хорус — основа
 *   «киберпанкового» звучания. Для `NOISE` не применяется.
 * @param tremoloHz частота амплитудной пульсации (0 — выкл).
 * @param tremoloDepth глубина пульсации 0..1.
 * @param ringModHz частота кольцевой модуляции (0 — выкл): металлический призвук.
 * @param bitcrushBits разрядность квантования 1..15 (0 — выкл): цифровая «грязь».
 * @param downsample коэффициент прореживания (sample & hold, 1 — выкл):
 *   намеренный алиасинг, «дешёвая цифра».
 * @param seed зерно ГПСЧ шума — рендер обязан быть детерминированным
 *   (иначе нет ни воспроизводимой сборки, ни проверяемого теста).
 */
data class ToneVoice(
    val wave: ToneWave,
    val startMs: Int,
    val durationMs: Int,
    val fromHz: Double = 0.0,
    val toHz: Double = fromHz,
    val amplitude: Double,
    val attackMs: Int = 4,
    val releaseMs: Int = 40,
    val detuneHz: Double = 0.0,
    val tremoloHz: Double = 0.0,
    val tremoloDepth: Double = 0.0,
    val ringModHz: Double = 0.0,
    val bitcrushBits: Int = 0,
    val downsample: Int = 1,
    val seed: Long = 1L,
) {
    init {
        require(startMs >= 0) { "startMs < 0" }
        require(durationMs > 0) { "durationMs <= 0" }
        require(amplitude > 0.0) { "amplitude <= 0" }
        require(downsample >= 1) { "downsample < 1" }
        require(bitcrushBits in 0..15) { "bitcrushBits вне 0..15" }
        require(tremoloDepth in 0.0..1.0) { "tremoloDepth вне 0..1" }
    }

    /** Момент окончания голоса от начала тона. */
    val endMs: Int get() = startMs + durationMs
}

/**
 * Полный патч тона: сетка голосов на общей длительности.
 *
 * Для зацикленных тонов ([CallTone.looping]) [totalMs] — это ПЕРИОД каденции:
 * тишина в хвосте патча и есть пауза между «гудками», а стык петли бесшумен,
 * потому что все голоса к этому моменту уже затухли.
 *
 * @param peak целевая пиковая амплитуда после нормализации (0..1) — ЕДИНСТВЕННАЯ
 *   ручка итоговой громкости тона; относительный баланс голосов она сохраняет.
 */
data class CallToneSpec(
    val totalMs: Int,
    val peak: Double,
    val voices: List<ToneVoice>,
) {
    init {
        require(totalMs > 0) { "totalMs <= 0" }
        require(peak > 0.0 && peak <= 1.0) { "peak вне (0..1]" }
        require(voices.isNotEmpty()) { "патч без голосов" }
        val overflow = voices.firstOrNull { it.endMs > totalMs }
        require(overflow == null) {
            "голос ${overflow?.wave} выходит за totalMs ($totalMs): ${overflow?.endMs}"
        }
    }
}
