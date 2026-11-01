pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Модули НЕ должны объявлять собственные репозитории — единая точка правды.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AEGIS"

// --- Модули клиента (см. §0/§9 технического задания) ---
include(":app")            // UI, навигация, экраны (§13)
include(":core-common")    // общие контракты: профили безопасности §8, ключи настроек §14
include(":core-ui")        // дизайн-система: палитра §13, тема Material3
include(":crypto")         // фасад крипто-каскада (слой A) + мост к Rust (JNI/UniFFI)
include(":network-tor")    // транспорт через встроенный Tor (Arti) — §7.2
include(":data-storage")   // локальное хранилище SQLCipher + Keystore/StrongBox — §6.3
