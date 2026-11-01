plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aegis.network.tor"
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
    // Встроенный Tor (Arti) живёт в нативном ядре :crypto (единый .so, §9/§10.11).
    implementation(project(":crypto"))
    implementation(libs.kotlinx.coroutines.android)
    // Сборка/разбор JSON-конфига и статуса на границе с нативным Tor (runtime API).
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
