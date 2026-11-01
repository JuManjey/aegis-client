import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aegis.crypto"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Нативные .so кладём в src/main/jniLibs/<abi>/ — их собирает cargoNdkBuild.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.kotlinx.coroutines.android)
    // Разбор JSON, приходящего с границы JNI (публичная инфа аккаунта).
    // Плагин сериализации здесь НЕ нужен: сериализаторы @Serializable-классов
    // генерируются в :core-common, а тут используется только рантайм.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Интеграция сборки нативного Rust-модуля (aegis-core) через cargo-ndk.
//
// ФАЗА 1: каркас. Задача устойчива — если Rust-тулчейн/cargo-ndk не найдены,
// сборка НЕ падает, а лишь печатает предупреждение (Kotlin-код грузит нативную
// библиотеку с graceful fallback, см. NativeAegisCore).
// ---------------------------------------------------------------------------

/** ABI, под которые собираем нативные библиотеки (переопределяемо: -Paegis.abiFilters=...). */
val aegisAbiFilters: List<String> =
    (project.findProperty("aegis.abiFilters") as String? ?: "arm64-v8a")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** Собирать ли Rust при обычной сборке (можно отключить: -Paegis.buildRust=false). */
val aegisBuildRust: Boolean =
    (project.findProperty("aegis.buildRust") as String? ?: "true").toBoolean()

/**
 * Включать ли слой B (libsignal, фича `signal`) в нативную сборку
 * (можно отключить: -Paegis.signal=false). Требует `protoc` в PATH — build.rs
 * libsignal генерирует код через prost-build.
 */
val aegisSignal: Boolean =
    (project.findProperty("aegis.signal") as String? ?: "true").toBoolean()

/**
 * Включать ли встроенный Tor (Arti, фича `tor`) в нативную сборку
 * (можно отключить: -Paegis.tor=false). Тяжёлая зависимость (сотни крейтов):
 * собирается best-effort — если кросс-компиляция под Android не удалась, сборка
 * НЕ падает, а повторяется без фичи `tor` (Kotlin переключится на FakeTorTransport).
 */
val aegisTor: Boolean =
    (project.findProperty("aegis.tor") as String? ?: "true").toBoolean()

val rustProjectDir: File = rootProject.file("rust/aegis-core")
val jniLibsOutputDir: File = file("src/main/jniLibs")

/** Ищет исполняемый файл в PATH и в ~/.cargo/bin (Android Studio часто не наследует PATH из ~/.zshrc). */
fun resolveExecutable(name: String): File? {
    val candidates = buildList {
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(File(it, name)) }
        add(File(System.getProperty("user.home"), ".cargo/bin/$name"))
    }
    return candidates.firstOrNull { it.isFile && it.canExecute() }
}

/** Читает свойство из local.properties (если файл есть). */
fun localProperty(name: String): String? {
    val localProps = rootProject.file("local.properties")
    if (!localProps.isFile) return null
    return Properties().apply { localProps.inputStream().use { load(it) } }.getProperty(name)
}

/**
 * Определяет путь к Android NDK в порядке приоритета:
 *  1) env ANDROID_NDK_HOME / ANDROID_NDK_ROOT (например, из CLI-сессии),
 *  2) sdk.dir (local.properties) или ANDROID_HOME + /ndk/<версия из каталога>,
 *  3) local.properties ndk.dir (устаревший способ, для обратной совместимости).
 */
fun resolveNdkDir(): File? {
    sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
        ?.let { return it }

    val ndkVersion = libs.versions.ndk.get()
    val sdkDir = localProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    if (sdkDir != null) {
        val byVersion = File(sdkDir, "ndk/$ndkVersion")
        if (byVersion.isDirectory) return byVersion
    }

    return localProperty("ndk.dir")?.let { File(it) }?.takeIf { it.isDirectory }
}

val cargoNdkBuild by tasks.registering {
    group = "aegis"
    description = "Собирает нативный Rust-модуль aegis-core (cargo-ndk) в jniLibs."

    val cargoNdk = resolveExecutable("cargo-ndk")
    val ndkDir = resolveNdkDir()

    // Условия запуска: включено флагом, крейт существует, инструменты найдены.
    onlyIf {
        when {
            !aegisBuildRust -> {
                logger.lifecycle("[aegis] cargoNdkBuild пропущен: aegis.buildRust=false.")
                false
            }
            !rustProjectDir.resolve("Cargo.toml").isFile -> {
                logger.lifecycle("[aegis] cargoNdkBuild пропущен: не найден ${rustProjectDir}/Cargo.toml.")
                false
            }
            cargoNdk == null -> {
                logger.warn("[aegis] cargoNdkBuild пропущен: cargo-ndk не найден в PATH/~/.cargo/bin. " +
                    "Нативная крипто-библиотека не будет собрана (каркас соберётся без неё).")
                false
            }
            ndkDir == null -> {
                logger.warn("[aegis] cargoNdkBuild пропущен: NDK не найден (ndk.dir/ANDROID_NDK_HOME).")
                false
            }
            else -> true
        }
    }

    doLast {
        val cargo = resolveExecutable("cargo")
        val protoc = resolveExecutable("protoc")
        val extraPath = listOfNotNull(
            cargoNdk?.parentFile?.absolutePath,
            cargo?.parentFile?.absolutePath,
            protoc?.parentFile?.absolutePath,
        ).joinToString(File.pathSeparator)

        // Слой B (signal) требует protoc; слой транспорта (tor) — тяжёлая, но
        // самодостаточная сборка Arti. Обе фичи best-effort.
        val enableSignal = aegisSignal && protoc != null
        if (aegisSignal && protoc == null) {
            logger.warn("[aegis] Слой B (signal) пропущен: protoc не найден. " +
                "Установи protoc (brew install protobuf) для сборки libsignal.")
        }

        // Запускает cargo-ndk с заданным списком фич; возвращает код возврата.
        fun runBuild(features: List<String>): Int {
            val args = mutableListOf((cargo ?: File("cargo")).absolutePath, "ndk")
            aegisAbiFilters.forEach { abi -> args += listOf("-t", abi) }
            args += listOf("-o", jniLibsOutputDir.absolutePath, "build", "--release")
            if (features.isNotEmpty()) args += listOf("--features", features.joinToString(","))
            logger.lifecycle("[aegis] cargo-ndk build, фичи: ${features.ifEmpty { listOf("(нет)") }.joinToString(",")}")
            // ProcessBuilder вместо Project.exec {} (последний deprecated и в этой
            // версии Gradle трактуется как ошибка компиляции build-скрипта).
            val process = ProcessBuilder(args)
                .directory(rustProjectDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .apply {
                    environment()["ANDROID_NDK_HOME"] = ndkDir!!.absolutePath
                    environment()["PATH"] = extraPath + File.pathSeparator + (System.getenv("PATH") ?: "")
                }
                .start()
            return process.waitFor()
        }

        // Желаемый набор фич, затем откат при неудаче: (signal+tor) → без tor →
        // без signal → базовая. Так APK соберётся, даже если тяжёлый Arti или
        // libsignal не кросс-компилируются в этом окружении (§ best-effort).
        val desired = buildList {
            if (enableSignal) add("signal")
            if (aegisTor) add("tor")
        }
        val fallbacks: List<List<String>> = listOfNotNull(
            desired,
            if ("tor" in desired) desired.filter { it != "tor" } else null,
            if ("signal" in desired) desired.filter { it != "signal" } else null,
            emptyList(),
        ).distinct()

        var built = false
        for ((index, features) in fallbacks.withIndex()) {
            if (runBuild(features) == 0) {
                built = true
                if (index > 0) {
                    logger.warn("[aegis] Нативная сборка удалась с усечённым набором фич: " +
                        "${features.ifEmpty { listOf("(нет)") }.joinToString(",")}. " +
                        "Полный набор (${desired.joinToString(",")}) не скомпилировался в этом окружении.")
                }
                break
            } else if (index < fallbacks.lastIndex) {
                logger.warn("[aegis] Сборка с фичами " +
                    "[${features.joinToString(",")}] не удалась — пробую меньший набор.")
            }
        }
        if (!built) {
            logger.warn("[aegis] Нативная библиотека не собралась ни с одним набором фич. " +
                "Каркас соберётся без .so (Kotlin использует graceful fallback).")
        }
    }
}

// Собираем Rust перед компиляцией Android-модуля (best-effort через onlyIf).
tasks.named("preBuild").configure { dependsOn(cargoNdkBuild) }
