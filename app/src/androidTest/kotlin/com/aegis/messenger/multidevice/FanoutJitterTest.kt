package com.aegis.messenger.multidevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.security.MetadataMinimization
import com.aegis.messenger.messaging.FanoutJitter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Проверка смягчения тайминг-корреляции fan-out (`docs/multidevice.md` §12,
 * `meta.minimize` §8): рандомизация ПОРЯДКА per-device дропов + ДЖИТТЕР задержек.
 *
 * [FanoutJitter] — чистый Kotlin-хелпер, поэтому крипта/устройства не нужны:
 * инъектируем детерминированный [Random] (seed) и перехватывающий `sleep`, чтобы
 * проверить инварианты без реальных задержек. Тест лежит в пакете
 * `com.aegis.messenger.multidevice`, чтобы попадать под тот же прогон
 * `connectedDebugAndroidTest`, что и остальные multi-device-тесты.
 */
@RunWith(AndroidJUnit4::class)
class FanoutJitterTest {

    private val items = (1..12).toList()

    /** BASIC (пресет «Быстро»): исходный порядок и НУЛЕВАЯ задержка (прежнее поведение). */
    @Test
    fun basic_keepsOrder_andHasNoDelay() = runBlocking {
        val delays = mutableListOf<Long>()
        val jitter = FanoutJitter(
            levelProvider = { MetadataMinimization.BASIC },
            random = Random(1),
            sleep = { ms -> delays += ms },
        )
        assertEquals("BASIC не меняет порядок", items, jitter.order(items))
        assertEquals("BASIC ⇒ maxDelayMs == 0", 0L, jitter.maxDelayMs())
        repeat(items.size) { jitter.awaitJitter() }
        assertTrue("BASIC ⇒ awaitJitter не спит вовсе", delays.isEmpty())
    }

    /** MODERATE (пресет «Баланс»): перестановка + задержки в границах [0..800]. */
    @Test
    fun moderate_shuffles_andBoundsDelay() = runBlocking {
        val delays = mutableListOf<Long>()
        val jitter = FanoutJitter(
            levelProvider = { MetadataMinimization.MODERATE },
            random = Random(42),
            sleep = { ms -> delays += ms },
        )
        assertEquals(FanoutJitter.MODERATE_JITTER_MAX_MS, jitter.maxDelayMs())
        assertPermutation(items, jitter.order(items))
        repeat(1000) { jitter.awaitJitter() }
        assertTrue("все задержки в [0..MAX]", delays.all { it in 0..FanoutJitter.MODERATE_JITTER_MAX_MS })
        assertTrue("джиттер реально ненулевой хотя бы иногда", delays.any { it > 0L })
    }

    /** MAXIMUM (пресет «Параноик», дефолт): перестановка + задержки в границах [0..2500]. */
    @Test
    fun maximum_shuffles_andBoundsDelay() = runBlocking {
        val delays = mutableListOf<Long>()
        val jitter = FanoutJitter(
            levelProvider = { MetadataMinimization.MAXIMUM },
            random = Random(7),
            sleep = { ms -> delays += ms },
        )
        assertEquals(FanoutJitter.MAXIMUM_JITTER_MAX_MS, jitter.maxDelayMs())
        val ordered = jitter.order(items)
        assertPermutation(items, ordered)
        assertNotEquals("на 12 элементах и этом seed порядок должен измениться", items, ordered)
        repeat(1000) { jitter.awaitJitter() }
        assertTrue("все задержки в [0..MAX]", delays.all { it in 0..FanoutJitter.MAXIMUM_JITTER_MAX_MS })
    }

    /** Ключевая гарантия «доставка не теряется»: [order] — всегда перестановка. */
    @Test
    fun order_isAlwaysPermutation_noLossNoDup() {
        for (level in MetadataMinimization.entries) {
            val jitter = FanoutJitter(levelProvider = { level }, random = Random(level.ordinal.toLong()))
            assertPermutation(items, jitter.order(items))
            assertEquals("пустой список остаётся пустым", emptyList<Int>(), jitter.order(emptyList<Int>()))
            assertEquals("один элемент не теряется", listOf(99), jitter.order(listOf(99)))
        }
    }

    private fun <T> assertPermutation(expected: List<T>, actual: List<T>) {
        assertEquals("размер сохраняется", expected.size, actual.size)
        assertEquals(
            "мультимножество совпадает (ничего не потеряно/не продублировано)",
            expected.groupingBy { it }.eachCount(),
            actual.groupingBy { it }.eachCount(),
        )
    }
}
