package com.aegis.messenger.appearance

import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.aegis.core.common.appearance.AppLanguage
import java.util.Locale

/**
 * Смена языка интерфейса (§14.6) — per-app locale, применяемая на всех версиях
 * (minSdk 26), без зависимости от AppCompat.
 *
 * Почему отдельное хранилище, а не общий обёрнутый Keystore-файл настроек:
 * локаль нужно применить в [android.app.Activity.attachBaseContext] — ДО
 * инициализации контейнера и до готовности Keystore, синхронно. Поэтому тег
 * языка держим в обычном [android.content.SharedPreferences] (быстрое синхронное
 * чтение). Язык — не секрет, а его утечка ничего не раскрывает.
 *
 * Применение: [wrap] оборачивает базовый контекст Activity выбранной локалью;
 * при смене языка вызывается [persist] + пересоздание Activity (`recreate`).
 */
object LocaleManager {

    private const val PREFS = "aegis_locale"
    private const val KEY_TAG = "language_tag"

    /** Сохранённый BCP-47 тег языка; пустой ⇒ «по системе». */
    fun persistedTag(context: Context): String =
        prefs(context).getString(KEY_TAG, "").orEmpty()

    /** Текущий выбранный язык (для UI). */
    fun currentLanguage(context: Context): AppLanguage =
        AppLanguage.fromTag(persistedTag(context))

    /** Сохраняет выбранный язык (пустой тег для [AppLanguage.SYSTEM]). */
    fun persist(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_TAG, language.tag).apply()
    }

    /**
     * Оборачивает контекст выбранной локалью. Для [AppLanguage.SYSTEM] (пустой
     * тег) возвращает исходный контекст без изменений (следуем системе).
     */
    fun wrap(base: Context): Context {
        val tag = persistedTag(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
