// Корневой build-скрипт AEGIS.
// Плагины объявляем здесь с `apply false` — конкретные модули подключают их сами.
// Это стандартная схема для многомодульного Gradle-проекта (single source of truth).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
