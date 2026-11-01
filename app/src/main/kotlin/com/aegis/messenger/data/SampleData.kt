package com.aegis.messenger.data

import com.aegis.core.common.model.ChatSummary

/**
 * Демонстрационные данные для UI ФАЗЫ 1.
 *
 * ВНИМАНИЕ: это исключительно превью-данные для отладки внешнего вида
 * главного экрана. Реальные чаты будут поступать из зашифрованного хранилища
 * (data-storage) после реализации криптоядра и хранилища (P2/P3/P6).
 */
object SampleData {
    val chats: List<ChatSummary> = listOf(
        ChatSummary(
            id = "1",
            displayName = "whitehat_ari",
            lastMessagePreview = "Отпечаток совпал — сессия подтверждена.",
            timestampLabel = "21:32",
            unreadCount = 2,
            verified = true,
            e2eeActive = true,
        ),
        ChatSummary(
            id = "2",
            displayName = "juris_k",
            lastMessagePreview = "Скинул документы в защищённый контейнер.",
            timestampLabel = "20:05",
            unreadCount = 0,
            verified = true,
            e2eeActive = true,
        ),
        ChatSummary(
            id = "3",
            displayName = "medic_relay",
            lastMessagePreview = "Провёл обмен PSK по QR лично, всё ок.",
            timestampLabel = "Вчера",
            unreadCount = 0,
            verified = false,
            e2eeActive = true,
        ),
    )
}
