package com.aegis.core.common.security

import kotlinx.serialization.Serializable

/**
 * Профиль (пресет) безопасности (§8, §10.13 плана).
 *
 * Пресет — это именованный набор значений [SecuritySettings], который одним
 * действием выставляет все связанные опции. По умолчанию активен [PARANOID].
 *
 * [CUSTOM] появляется автоматически, как только пользователь меняет любой
 * параметр относительно выбранного пресета.
 */
@Serializable
enum class SecurityPreset {
    PARANOID,
    BALANCED,
    FAST,
    CUSTOM;

    companion object {
        /** Пресет по умолчанию — «Параноик» (безопасность важнее удобства, §8). */
        val DEFAULT: SecurityPreset = PARANOID
    }
}
