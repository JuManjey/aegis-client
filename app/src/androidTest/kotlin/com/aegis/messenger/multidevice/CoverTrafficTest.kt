package com.aegis.messenger.multidevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.security.MetadataMinimization
import com.aegis.messenger.messaging.CoverTraffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Проверка прикрытия факта/объёма отправки (cover-traffic, `docs/multidevice.md`
 * §12, §3a; `meta.minimize` §8): фиктивные дропы неотличимы по формату от реальных
 * per-device адресов, включены ТОЛЬКО в «Параноике», паддинг в границах.
 *
 * [CoverTraffic] — чистый Kotlin-хелпер (кроме планировщика [CoverTraffic.start],
 * который здесь не гоняем — как и в [FanoutJitterTest] тестируем примитивы, а не
 * реальный сетевой цикл). `android.util.Base64` требует рантайма Android, поэтому
 * тест инструментальный и лежит в пакете `com.aegis.messenger.multidevice`.
 */
@RunWith(AndroidJUnit4::class)
class CoverTrafficTest {

    private fun coverAt(level: MetadataMinimization, seed: Long = 1) =
        CoverTraffic(levelProvider = { level }, random = Random(seed))

    /** Cover-traffic выключен вне «Параноика»: enabled=false, паддинг = 0. */
    @Test
    fun disabled_whenNotMaximum() {
        for (level in listOf(MetadataMinimization.BASIC, MetadataMinimization.MODERATE)) {
            val cover = coverAt(level)
            assertFalse("$level ⇒ cover-traffic выключен", cover.enabled)
            repeat(200) { assertEquals("$level ⇒ padCount()==0", 0, cover.padCount()) }
            assertTrue("$level ⇒ decoyDrops пуст", cover.decoyDrops(cover.padCount()).isEmpty())
        }
    }

    /** «Параноик»: cover-traffic включён, паддинг в границах [0..MAX] и иногда > 0. */
    @Test
    fun maximum_enabled_padCountInBounds() {
        val cover = coverAt(MetadataMinimization.MAXIMUM, seed = 42)
        assertTrue("MAXIMUM ⇒ cover-traffic включён", cover.enabled)
        val samples = List(500) { cover.padCount() }
        assertTrue("паддинг в [0..MAX]", samples.all { it in 0..CoverTraffic.MAX_PAD_DROPS })
        assertTrue("паддинг иногда ненулевой", samples.any { it > 0 })
        assertTrue("паддинг иногда достигает MAX", samples.any { it == CoverTraffic.MAX_PAD_DROPS })
    }

    /** decoy-mailbox неотличим по формату от реального per-device HKDF-адреса (§4.1). */
    @Test
    fun randomMailboxId_matchesRealFormat() {
        val cover = coverAt(MetadataMinimization.MAXIMUM, seed = 7)
        val ids = List(50) { cover.randomMailboxId() }
        val urlSafeNoPad = Regex("^[A-Za-z0-9_-]{43}$")
        for (id in ids) {
            // 32 байта → url-safe base64 без паддинга = ровно 43 символа (как реальный
            // mailbox), проходит серверный validate_id (8..64 url-safe base64).
            assertTrue("формат url-safe base64 без паддинга, 43 симв.: $id", urlSafeNoPad.matches(id))
            assertFalse("нет '=' паддинга", id.contains('='))
        }
        assertEquals("id уникальны (свежая случайность)", ids.size, ids.toSet().size)
    }

    /** Шумовой payload — правдоподобного размера и действительно случайный. */
    @Test
    fun randomDecoyPayload_sizeInBoundsAndVaries() {
        val cover = coverAt(MetadataMinimization.MAXIMUM, seed = 11)
        val payloads = List(200) { cover.randomDecoyPayload() }
        assertTrue(
            "размер в [MIN..MAX]",
            payloads.all { it.size in CoverTraffic.DECOY_MIN_BYTES..CoverTraffic.DECOY_MAX_BYTES },
        )
        assertTrue("размеры варьируются", payloads.map { it.size }.toSet().size > 1)
        assertFalse("два соседних payload не совпадают", payloads[0].contentEquals(payloads[1]))
    }

    /** decoyDrops(n): ровно n дропов, mailbox валидного формата, свежие. */
    @Test
    fun decoyDrops_countAndFormat() {
        val cover = coverAt(MetadataMinimization.MAXIMUM, seed = 3)
        assertTrue("n<=0 ⇒ пусто", cover.decoyDrops(0).isEmpty())
        val drops = cover.decoyDrops(3)
        assertEquals("ровно n дропов", 3, drops.size)
        val urlSafeNoPad = Regex("^[A-Za-z0-9_-]{43}$")
        assertTrue("mailbox валиден", drops.all { urlSafeNoPad.matches(it.first) })
        assertTrue(
            "payload в границах",
            drops.all { it.second.size in CoverTraffic.DECOY_MIN_BYTES..CoverTraffic.DECOY_MAX_BYTES },
        )
        assertEquals("mailbox уникальны в батче", drops.size, drops.map { it.first }.toSet().size)
    }

    /** Интервал фоновых дропов — в границах пресета «Параноик». */
    @Test
    fun nextIntervalMs_inBounds() {
        val cover = coverAt(MetadataMinimization.MAXIMUM, seed = 99)
        val samples = List(500) { cover.nextIntervalMs() }
        assertTrue(
            "интервал в [MIN..MAX]",
            samples.all { it in CoverTraffic.COVER_MIN_INTERVAL_MS..CoverTraffic.COVER_MAX_INTERVAL_MS },
        )
        assertNotEquals("интервалы варьируются", 1, samples.toSet().size)
    }
}
