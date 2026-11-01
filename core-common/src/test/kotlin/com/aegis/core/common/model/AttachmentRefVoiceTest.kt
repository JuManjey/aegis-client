package com.aegis.core.common.model

import com.aegis.core.common.audio.VoiceWaveform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Совместимость формата ссылки на вложение при добавлении голосовых (§14.4).
 *
 * Это ЕДИНСТВЕННОЕ место, где проверяется, что новая версия клиента не ломает
 * переписку со старой. Конфигурация JSON здесь повторяет `WireJson` из `:app`
 * (`ignoreUnknownKeys = true`, `encodeDefaults = true`) — тестировать её оттуда
 * нельзя, потому что там она `internal` и живёт в Android-модуле.
 */
class AttachmentRefVoiceTest {

    private val wireJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Схема ссылки на вложение ДО появления голосовых: ровно то, что умеет
     * разобрать уже установленное у людей приложение.
     */
    @Serializable
    private data class LegacyAttachmentRef(
        val objectId: String,
        val keyB64: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val kind: AttachmentKind,
        val v: Int = 1,
    )

    private fun voiceRef() = AttachmentRef(
        objectId = "b".repeat(43),
        keyB64 = "a".repeat(44),
        fileName = "voice-message.aac",
        mimeType = "audio/aac",
        sizeBytes = 18_432,
        // Голосовое уходит именно как FILE — в этом и состоит совместимость.
        kind = AttachmentKind.FILE,
        voice = VoiceMeta(
            durationMs = 7_400,
            waveformB64 = VoiceWaveform.encode(IntArray(VoiceWaveform.BUCKETS) { 120 }),
        ),
    )

    /**
     * ГЛАВНАЯ гарантия: конверт с голосовым, разобранный СТАРОЙ схемой, не падает
     * и даёт осмысленное вложение-файл.
     *
     * Если бы голос обозначался новым значением `AttachmentKind.AUDIO`, здесь
     * было бы исключение — а в приложении это означало бы откат разбора
     * `WireContent` на «сырой текст», то есть служебный JSON в ленте чата вместо
     * сообщения (см. обоснование в [VoiceMeta]).
     */
    @Test
    fun `legacy client parses a voice attachment as a plain file`() {
        val json = wireJson.encodeToString(AttachmentRef.serializer(), voiceRef())

        val legacy = wireJson.decodeFromString(LegacyAttachmentRef.serializer(), json)

        assertEquals(AttachmentKind.FILE, legacy.kind)
        assertEquals("audio/aac", legacy.mimeType)
        assertEquals("voice-message.aac", legacy.fileName)
        assertEquals(18_432L, legacy.sizeBytes)
        // Ключ и id блоба доехали: старый клиент реально скачает и расшифрует звук.
        assertEquals(voiceRef().objectId, legacy.objectId)
        assertEquals(voiceRef().keyB64, legacy.keyB64)
    }

    /** Обратное направление: ссылка от СТАРОГО клиента читается новой схемой. */
    @Test
    fun `new client parses a legacy attachment with voice absent`() {
        val legacyJson = wireJson.encodeToString(
            LegacyAttachmentRef.serializer(),
            LegacyAttachmentRef(
                objectId = "c".repeat(43),
                keyB64 = "d".repeat(44),
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024,
                kind = AttachmentKind.IMAGE,
            ),
        )

        val parsed = wireJson.decodeFromString(AttachmentRef.serializer(), legacyJson)

        assertNull("отсутствующее поле должно давать null, а не ошибку", parsed.voice)
        assertFalse(parsed.isVoice)
        assertFalse(parsed.isPlayableAudio)
        assertEquals(AttachmentKind.IMAGE, parsed.kind)
    }

    @Test
    fun `voice metadata survives a full roundtrip`() {
        val original = voiceRef()
        val restored = wireJson.decodeFromString(
            AttachmentRef.serializer(),
            wireJson.encodeToString(AttachmentRef.serializer(), original),
        )
        assertEquals(original, restored)
        assertEquals(7_400L, restored.voice?.durationMs)
        assertTrue(restored.isVoice)
        assertTrue(restored.isPlayableAudio)
    }

    /**
     * Огибающая доезжает без искажений: получатель рисует дорожку ДО скачивания
     * блоба, поэтому расхождение здесь было бы видно пользователю напрямую.
     */
    @Test
    fun `waveform survives the wire and decodes back to levels`() {
        val levels = IntArray(VoiceWaveform.BUCKETS) { (it * 5) % 256 }
        val ref = voiceRef().copy(
            voice = VoiceMeta(durationMs = 1_000, waveformB64 = VoiceWaveform.encode(levels)),
        )
        val restored = wireJson.decodeFromString(
            AttachmentRef.serializer(),
            wireJson.encodeToString(AttachmentRef.serializer(), ref),
        )
        val decoded = VoiceWaveform.decode(restored.voice!!.waveformB64)
        assertEquals(levels.toList(), decoded!!.toList())
    }

    /**
     * Голосовое от будущей версии без огибающей (или с испорченной) всё равно
     * рисуется — плеер обязан работать, даже если картинку восстановить нельзя.
     */
    @Test
    fun `voice without waveform still renders via synthetic fallback`() {
        val ref = voiceRef().copy(voice = VoiceMeta(durationMs = 3_000))
        assertTrue(ref.isVoice)
        val levels = VoiceWaveform.forDisplay(ref.voice?.waveformB64, ref.objectId)
        assertEquals(VoiceWaveform.BUCKETS, levels.size)
        assertTrue(levels.any { it > VoiceWaveform.MIN_VISIBLE_LEVEL })
    }

    /** Аудиофайл из пикера (без метаданных) тоже получает плеер — по MIME. */
    @Test
    fun `picked audio file is playable without voice metadata`() {
        val mp3 = AttachmentRef(
            objectId = "e".repeat(43),
            keyB64 = "f".repeat(44),
            fileName = "track.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 2_000_000,
            kind = AttachmentKind.FILE,
        )
        assertFalse(mp3.isVoice)
        assertTrue(mp3.isPlayableAudio)
    }

    /** Регистр MIME из чужого пикера не должен ломать выбор рендера. */
    @Test
    fun `playable audio detection ignores mime case`() {
        val ref = voiceRef().copy(voice = null, mimeType = "AUDIO/AAC")
        assertTrue(ref.isPlayableAudio)
    }

    /** Не-аудио остаётся карточкой файла. */
    @Test
    fun `non audio attachment is not playable`() {
        val pdf = AttachmentRef(
            objectId = "g".repeat(43),
            keyB64 = "h".repeat(44),
            fileName = "doc.pdf",
            mimeType = "application/pdf",
            sizeBytes = 512,
            kind = AttachmentKind.FILE,
        )
        assertFalse(pdf.isPlayableAudio)
    }
}
