plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Нужен для @Serializable-модели файла PSK-стора (PskStoreFile).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aegis.data.storage"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.kotlinx.coroutines.android)
    // Сериализация записей аккаунта/настроек для запечатанного хранения.
    implementation(libs.kotlinx.serialization.json)
    // P6 (§6.3): зашифрованное локальное хранилище (сообщения/контакты/Signal-
    // сессии) — SQLCipher (AES-256). Ключ БД на диске запечатан ключом аккаунта и
    // Keystore/StrongBox (см. AegisDatabase.loadOrCreatePassphrase).
    // androidx.sqlite даёт SupportSQLiteOpenHelper, который реализует SQLCipher.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)
    // P6 (§14.3): биометрическая привязка ключа Keystore (BiometricPrompt).
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
}
