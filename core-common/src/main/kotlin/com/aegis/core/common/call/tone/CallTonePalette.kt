package com.aegis.core.common.call.tone

/**
 * Звуковое оформление звонка — «патчи» синтезатора для каждого тона
 * ([CallTone]). Это ДИЗАЙН, а не логика: менять цифры здесь безопасно, на
 * поведение звонка они не влияют.
 *
 * ## Общая идея звучания
 *
 * Тоны собраны из тех же приёмов, что и визуальный язык приложения
 * (`core-ui`: неон, сканлайны, HUD): расстроенные пилы вместо чистых синусов,
 * намеренный алиасинг ([ToneVoice.downsample]) и квантование
 * ([ToneVoice.bitcrushBits]) вместо гладкости, шумовые транзиенты вместо
 * «телефонного» щелчка. Ладовая основа — минор (ля минор), интервалы — кварты
 * и квинты: так тон читается как техника, а не как мелодия.
 *
 * ## Что сохранено от телефонии
 *
 * Узнаваемость важнее стиля, поэтому каденции остались телефонными:
 * «гудок» — импульс и длинная пауза, «занято» — короткие частые удары.
 * Пользователь понимает сигнал до того, как оценит его звучание.
 */
object CallTonePalette {

    /** Патч тона. Чистая функция: одинаковый вход — одинаковый звук. */
    fun spec(tone: CallTone): CallToneSpec = when (tone) {
        CallTone.OUTGOING_RINGBACK -> ringback()
        CallTone.NEGOTIATING -> negotiating()
        CallTone.CONNECTED -> connected()
        CallTone.LINK_LOST -> linkLost()
        CallTone.RELINK -> relink()
        CallTone.HANGUP -> hangup()
        CallTone.BUSY -> busy()
        CallTone.DENIED -> denied()
        CallTone.INCOMING_RING -> incomingRing()
    }

    // --- Ноты (равномерная темперация, A4 = 440) ----------------------------
    // Именованные константы вместо «магических» частот: патч ниже читается как
    // партитура, и интервалы видно глазами.

    private const val A2 = 110.00
    private const val BB3 = 233.08
    private const val GS3 = 207.65
    private const val A3 = 220.00
    private const val C4 = 261.63
    private const val D4 = 293.66
    private const val EB4 = 311.13
    private const val E4 = 329.63
    private const val F4 = 349.23
    private const val A4 = 440.00
    private const val B4 = 493.88
    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val A5 = 880.00
    private const val B5 = 987.77
    private const val D6 = 1174.66
    private const val E6 = 1318.51
    private const val G6 = 1567.98

    // --- Гудки исходящего вызова --------------------------------------------

    /**
     * «Гудки» (`DIALING`). Каденция телефонная: короткая серия импульсов и
     * длинная пауза — период 3200 мс.
     *
     * Импульс — расстроенный меандр с нисходящим глиссандо и пульсацией 12 Гц
     * (это и даёт «механический» характер вместо чистого зуммера), поверх —
     * тихая квинта синусом для объёма и короткий высокий чирп как «отправлен
     * пакет». Под всем — еле слышный шум эфира, который затухает к паузе.
     */
    private fun ringback() = CallToneSpec(
        totalMs = 3200,
        peak = 0.55,
        voices = listOf(
            pulse(atMs = 0, fromHz = BB3, toHz = A3, amp = 0.38, seed = 11L),
            fifth(atMs = 0, fromHz = F4, toHz = E4, amp = 0.20),
            chirp(atMs = 0, fromHz = D6, toHz = G6, amp = 0.10),

            pulse(atMs = 600, fromHz = A3, toHz = GS3, amp = 0.34, seed = 12L),
            fifth(atMs = 600, fromHz = E4, toHz = EB4, amp = 0.18),
            chirp(atMs = 600, fromHz = D6, toHz = G6, amp = 0.085),

            // Шум эфира тянется через оба импульса и уходит в паузу: он и
            // склеивает серию в один «гудок», и маскирует стык петли.
            ToneVoice(
                wave = ToneWave.NOISE,
                startMs = 0,
                durationMs = 1180,
                amplitude = 0.045,
                attackMs = 220,
                releaseMs = 520,
                downsample = 7,
                seed = 13L,
            ),
        ),
    )

    /** Импульс гудка: расстроенный меандр с прореживанием и пульсацией. */
    private fun pulse(atMs: Int, fromHz: Double, toHz: Double, amp: Double, seed: Long) = ToneVoice(
        wave = ToneWave.SQUARE,
        startMs = atMs,
        durationMs = 480,
        fromHz = fromHz,
        toHz = toHz,
        amplitude = amp,
        attackMs = 8,
        releaseMs = 140,
        detuneHz = 3.1,
        tremoloHz = 12.0,
        tremoloDepth = 0.22,
        bitcrushBits = 6,
        downsample = 3,
        seed = seed,
    )

    /** Квинта синусом над импульсом — объём без грязи. */
    private fun fifth(atMs: Int, fromHz: Double, toHz: Double, amp: Double) = ToneVoice(
        wave = ToneWave.SINE,
        startMs = atMs,
        durationMs = 480,
        fromHz = fromHz,
        toHz = toHz,
        amplitude = amp,
        attackMs = 12,
        releaseMs = 160,
        detuneHz = 1.9,
    )

    /** Короткий высокий чирп: «пакет ушёл». */
    private fun chirp(atMs: Int, fromHz: Double, toHz: Double, amp: Double) = ToneVoice(
        wave = ToneWave.SINE,
        startMs = atMs,
        durationMs = 60,
        fromHz = fromHz,
        toHz = toHz,
        amplitude = amp,
        attackMs = 2,
        releaseMs = 45,
        bitcrushBits = 5,
    )

    // --- Согласование --------------------------------------------------------

    /**
     * «Пинги рукопожатия» (`CONNECTING`). Намеренно тихий (peak 0.26) и редкий
     * тон: он сообщает «идёт работа», а не привлекает внимание. Два восходящих
     * микро-блипа и шумовой тик, затем секунда тишины.
     */
    private fun negotiating() = CallToneSpec(
        totalMs = 1200,
        peak = 0.26,
        voices = listOf(
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 0,
                durationMs = 45,
                fromHz = A5,
                toHz = E6,
                amplitude = 0.10,
                attackMs = 2,
                releaseMs = 32,
                bitcrushBits = 5,
            ),
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 150,
                durationMs = 45,
                fromHz = D6,
                toHz = G6,
                amplitude = 0.085,
                attackMs = 2,
                releaseMs = 32,
                bitcrushBits = 5,
            ),
            ToneVoice(
                wave = ToneWave.NOISE,
                startMs = 320,
                durationMs = 40,
                amplitude = 0.030,
                attackMs = 2,
                releaseMs = 34,
                downsample = 11,
                seed = 21L,
            ),
        ),
    )

    // --- Соединение установлено ---------------------------------------------

    /**
     * «Канал открыт» (переход в `ACTIVE`). Восходящая квинта пилой + октава
     * синусом + затухающий шиммер: единственный тон палитры, который звучит
     * утвердительно-мажорно, потому что это единственное безусловно хорошее
     * событие в жизни звонка.
     */
    private fun connected() = CallToneSpec(
        totalMs = 640,
        peak = 0.62,
        voices = listOf(
            ToneVoice(
                wave = ToneWave.SAW,
                startMs = 0,
                durationMs = 260,
                fromHz = E4,
                toHz = E5,
                amplitude = 0.30,
                attackMs = 4,
                releaseMs = 100,
                detuneHz = 4.3,
                downsample = 2,
                seed = 31L,
            ),
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 90,
                durationMs = 330,
                fromHz = E5,
                toHz = B5,
                amplitude = 0.22,
                attackMs = 10,
                releaseMs = 180,
                detuneHz = 2.1,
            ),
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 180,
                durationMs = 400,
                fromHz = E6,
                amplitude = 0.10,
                attackMs = 30,
                releaseMs = 300,
                detuneHz = 1.3,
                tremoloHz = 9.0,
                tremoloDepth = 0.5,
            ),
            ToneVoice(
                wave = ToneWave.NOISE,
                startMs = 0,
                durationMs = 120,
                amplitude = 0.06,
                attackMs = 2,
                releaseMs = 100,
                downsample = 4,
                seed = 32L,
            ),
        ),
    )

    // --- Потеря и восстановление пути ---------------------------------------

    /**
     * «Сигнал потерян» (`reconnecting`, фаза остаётся `ACTIVE`). Глубокая
     * пульсация 7.5 Гц на расстроенном синусе — звук читается как тревога, но
     * на peak 0.34 не перекрывает речь собеседника, которая может вернуться в
     * любой момент.
     */
    private fun linkLost() = CallToneSpec(
        totalMs = 1600,
        peak = 0.34,
        voices = listOf(
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 0,
                durationMs = 520,
                fromHz = EB4 * 2,
                toHz = C5,
                amplitude = 0.13,
                attackMs = 40,
                releaseMs = 220,
                detuneHz = 5.5,
                tremoloHz = 7.5,
                tremoloDepth = 0.85,
            ),
            ToneVoice(
                wave = ToneWave.NOISE,
                startMs = 0,
                durationMs = 540,
                amplitude = 0.035,
                attackMs = 60,
                releaseMs = 260,
                downsample = 9,
                seed = 41L,
            ),
        ),
    )

    /** «Релинк»: короткий восходящий блип, зеркальный к [linkLost]. */
    private fun relink() = CallToneSpec(
        totalMs = 340,
        peak = 0.46,
        voices = listOf(
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 0,
                durationMs = 180,
                fromHz = F4 * 2,
                toHz = C5 * 2,
                amplitude = 0.22,
                attackMs = 4,
                releaseMs = 120,
                detuneHz = 2.0,
            ),
            ToneVoice(
                wave = ToneWave.NOISE,
                startMs = 0,
                durationMs = 50,
                amplitude = 0.04,
                attackMs = 2,
                releaseMs = 44,
                downsample = 5,
                seed = 51L,
            ),
        ),
    )

    // --- Завершение ----------------------------------------------------------

    /**
     * «Отключение питания» (штатное завершение). Нисходящее глиссандо на две
     * октавы с прореживанием и квантованием — самый «аналогово-умирающий» звук
     * палитры.
     */
    private fun hangup() = CallToneSpec(
        totalMs = 560,
        peak = 0.50,
        voices = listOf(
            ToneVoice(
                wave = ToneWave.SAW,
                startMs = 0,
                durationMs = 380,
                fromHz = A4,
                toHz = A2,
                amplitude = 0.28,
                attackMs = 3,
                releaseMs = 150,
                detuneHz = 3.7,
                bitcrushBits = 6,
                downsample = 4,
                seed = 61L,
            ),
            ToneVoice(
                wave = ToneWave.SINE,
                startMs = 60,
                durationMs = 420,
                fromHz = A3,
                toHz = A2 / 2,
                amplitude = 0.16,
                attackMs = 8,
                releaseMs = 240,
            ),
            ToneVoice(
                wave = ToneWave.NOISE,
                startMs = 0,
                durationMs = 90,
                amplitude = 0.05,
                attackMs = 2,
                releaseMs = 80,
                downsample = 6,
                seed = 62L,
            ),
        ),
    )

    /**
     * «Занято». Каденция и пара частот (480/620 Гц) взяты из классического
     * телефонного сигнала — это делает тон понятным без объяснений; кольцевая
     * модуляция 55 Гц и грубое квантование добавляют нужную злость.
     * Три удара и стоп: зацикливать «занято» незачем, звонка уже нет.
     */
    private fun busy() = CallToneSpec(
        totalMs = 1560,
        peak = 0.62,
        voices = buildList {
            for ((index, at) in listOf(0, 520, 1040).withIndex()) {
                add(
                    ToneVoice(
                        wave = ToneWave.SQUARE,
                        startMs = at,
                        durationMs = 240,
                        fromHz = 480.0,
                        amplitude = 0.30,
                        attackMs = 4,
                        releaseMs = 40,
                        ringModHz = 55.0,
                        bitcrushBits = 4,
                        downsample = 3,
                        seed = 70L + index,
                    ),
                )
                add(
                    ToneVoice(
                        wave = ToneWave.SQUARE,
                        startMs = at,
                        durationMs = 240,
                        fromHz = 620.0,
                        amplitude = 0.26,
                        attackMs = 4,
                        releaseMs = 40,
                        bitcrushBits = 4,
                        downsample = 3,
                        seed = 80L + index,
                    ),
                )
            }
        },
    )

    /**
     * «Отказ»: отклонено, нет ответа, сеть не пропустила медиапоток. Два
     * резких нисходящих удара с шумовым транзиентом — интонация отрицания.
     */
    private fun denied() = CallToneSpec(
        totalMs = 760,
        peak = 0.62,
        voices = listOf(
            deniedStab(atMs = 0, durationMs = 160, fromHz = E4, toHz = C4, seed = 91L),
            deniedStab(atMs = 220, durationMs = 220, fromHz = B4 / 2, toHz = D4 / 2, seed = 92L),
            deniedNoise(atMs = 0, seed = 93L),
            deniedNoise(atMs = 220, seed = 94L),
        ),
    )

    private fun deniedStab(atMs: Int, durationMs: Int, fromHz: Double, toHz: Double, seed: Long) =
        ToneVoice(
            wave = ToneWave.SQUARE,
            startMs = atMs,
            durationMs = durationMs,
            fromHz = fromHz,
            toHz = toHz,
            amplitude = 0.32,
            attackMs = 3,
            releaseMs = 60,
            detuneHz = 6.0,
            bitcrushBits = 4,
            downsample = 5,
            seed = seed,
        )

    private fun deniedNoise(atMs: Int, seed: Long) = ToneVoice(
        wave = ToneWave.NOISE,
        startMs = atMs,
        durationMs = 70,
        amplitude = 0.09,
        attackMs = 2,
        releaseMs = 55,
        downsample = 5,
        seed = seed,
    )

    // --- Рингтон входящего ---------------------------------------------------

    /**
     * Рингтон входящего звонка — подпись приложения. Единственный тон, который
     * звучит громко ([CallToneRoute.RINGER], peak 0.92), поэтому он же
     * единственный, который сделан как музыкальная фраза:
     *
     *  * шумовой транзиент-«глитч» на старте — внимание;
     *  * арпеджио ля-минора расстроенными пилами (A3–C4–E4–A4) — узнаваемый мотив;
     *  * субпульс 110 Гц с пульсацией в темп арпеджио — вес в динамике телефона;
     *  * вторая фраза выше (A4–E4–C5) с шиммером 880 Гц — нарастание;
     *  * пауза до 2600 мс, чтобы петля не превращалась в непрерывный вой.
     */
    private fun incomingRing(): CallToneSpec {
        val step = 140
        val phraseOne = arpeggio(
            startMs = 0,
            stepMs = step,
            notes = listOf(A3, C4, E4, A4),
            amplitude = 0.30,
            seedBase = 100L,
        )
        val phraseTwo = arpeggio(
            startMs = 620,
            stepMs = step,
            notes = listOf(A4, E4),
            amplitude = 0.26,
            seedBase = 110L,
        )
        return CallToneSpec(
            totalMs = 2600,
            peak = 0.92,
            voices = buildList {
                add(
                    ToneVoice(
                        wave = ToneWave.NOISE,
                        startMs = 0,
                        durationMs = 70,
                        amplitude = 0.10,
                        attackMs = 2,
                        releaseMs = 60,
                        downsample = 5,
                        seed = 120L,
                    ),
                )
                addAll(phraseOne)
                addAll(phraseTwo)
                // Субпульс: «дышит» синхронно с арпеджио (1/140 мс ≈ 7.14 Гц).
                add(
                    ToneVoice(
                        wave = ToneWave.SINE,
                        startMs = 0,
                        durationMs = 560,
                        fromHz = A2,
                        amplitude = 0.22,
                        attackMs = 10,
                        releaseMs = 200,
                        tremoloHz = 1000.0 / step,
                        tremoloDepth = 0.6,
                    ),
                )
                // Кульминация фразы: длинная нота с шиммером и шумовым хвостом.
                add(
                    ToneVoice(
                        wave = ToneWave.SAW,
                        startMs = 900,
                        durationMs = 260,
                        fromHz = C5,
                        amplitude = 0.26,
                        attackMs = 4,
                        releaseMs = 120,
                        detuneHz = 4.0,
                        bitcrushBits = 7,
                        downsample = 2,
                        seed = 121L,
                    ),
                )
                add(
                    ToneVoice(
                        wave = ToneWave.SINE,
                        startMs = 900,
                        durationMs = 420,
                        fromHz = A5,
                        amplitude = 0.09,
                        attackMs = 20,
                        releaseMs = 260,
                        detuneHz = 2.5,
                        tremoloHz = 15.0,
                        tremoloDepth = 0.8,
                    ),
                )
                add(
                    ToneVoice(
                        wave = ToneWave.NOISE,
                        startMs = 900,
                        durationMs = 400,
                        amplitude = 0.05,
                        attackMs = 40,
                        releaseMs = 320,
                        downsample = 9,
                        seed = 122L,
                    ),
                )
                // Высокий «маркер конца фразы» — цифровой акцент перед паузой.
                add(
                    ToneVoice(
                        wave = ToneWave.SINE,
                        startMs = 1240,
                        durationMs = 70,
                        fromHz = G5,
                        toHz = E6,
                        amplitude = 0.10,
                        attackMs = 2,
                        releaseMs = 60,
                        bitcrushBits = 5,
                    ),
                )
            },
        )
    }

    /** Арпеджио расстроенными пилами: по голосу на ноту, шаг [stepMs]. */
    private fun arpeggio(
        startMs: Int,
        stepMs: Int,
        notes: List<Double>,
        amplitude: Double,
        seedBase: Long,
    ): List<ToneVoice> = notes.mapIndexed { index, hz ->
        ToneVoice(
            wave = ToneWave.SAW,
            startMs = startMs + index * stepMs,
            // Нота длиннее шага: соседние голоса перекрываются и фраза звучит
            // связно, а не как последовательность отдельных писков.
            durationMs = stepMs + 10,
            fromHz = hz,
            amplitude = amplitude,
            attackMs = 3,
            releaseMs = 80,
            detuneHz = 3.5,
            bitcrushBits = 7,
            downsample = 2,
            seed = seedBase + index,
        )
    }
}
