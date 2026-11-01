import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Конверт слоя B (WireEnvelope) сериализуется в JSON для транспорта (§6.1).
    alias(libs.plugins.kotlin.serialization)
}

// Релизный ключ — только из keystore.properties (в .gitignore; формат — README.md,
// раздел «Подпись»; в CI его пишет release.yml из секретов). Без него release собирается
// НЕПОДПИСАННЫМ: подпись общим debug-ключом из репозитория позволила бы кому угодно
// выпустить «обновление», которое Android поставит поверх настоящего приложения.
val releaseKeystore: Properties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "com.aegis.messenger"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.aegis.messenger"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 6
        versionName = "0.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Авто-обнаружение адреса релея (§12.2). Источник истины —
        // gradle.properties (`aegis.defaultRelayOnion` / `aegis.relayConfigPubkey`);
        // onion — публичный адрес, его коммитят. -P переопределяет на одну сборку.
        // Без onion свежая установка показывает «адрес не задан». Без pubkey
        // GET /v1/config отключается (fail-closed). Приватный ключ НЕ в сборке.
        val bakedRelayOnion = (project.findProperty("aegis.defaultRelayOnion") as String? ?: "").trim()
        val relayConfigPubkey = (project.findProperty("aegis.relayConfigPubkey") as String? ?: "").trim()
        if (bakedRelayOnion.isEmpty()) {
            logger.warn(
                "AEGIS: aegis.defaultRelayOnion пуст — в приложении будет баннер " +
                    "«адрес onion-релея не задан». Задайте его в gradle.properties " +
                    "или -Paegis.defaultRelayOnion=<hs>.onion",
            )
        }
        buildConfigField("String", "DEFAULT_RELAY_ONION", "\"$bakedRelayOnion\"")
        buildConfigField("String", "RELAY_CONFIG_PUBKEY", "\"$relayConfigPubkey\"")

        // HTTPS-адрес ТОГО ЖЕ релея для режима «Tor ВЫКЛ» (§12.2): та же БД, что
        // за onion, иначе собеседники в разных режимах не увидят сообщений друг
        // друга. Только https://host[:port] — сборка с http:// или путём падает:
        // незашифрованного транспорта нет ни в каком режиме. Пусто — режим без
        // Tor честно сообщает «сервер без Tor не настроен», пока адрес не придёт
        // в подписанном конфиге. Пины SPKI (`sha256/<base64>`, через запятую)
        // необязательны; без них — только системная проверка цепочки и имени.
        val bakedRelayHttps = (project.findProperty("aegis.defaultRelayHttps") as String? ?: "").trim()
        if (bakedRelayHttps.isNotEmpty() && !Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?$").matches(bakedRelayHttps)) {
            throw GradleException(
                "aegis.defaultRelayHttps=\"$bakedRelayHttps\": нужен https://host[:port] без пути (http:// запрещён)",
            )
        }
        // Опечатка в пине не должна молча выключать пиннинг: клиент отбросил бы
        // такой пин, и сборка ушла бы без него.
        val relayHttpsPins = (project.findProperty("aegis.relayHttpsPins") as String? ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        relayHttpsPins.firstOrNull { !Regex("^sha256/[A-Za-z0-9+/]{43}=$").matches(it) }?.let { bad ->
            throw GradleException("aegis.relayHttpsPins: \"$bad\" — нужен sha256/<base64 от 32 байт>")
        }
        if (relayHttpsPins.isNotEmpty() && bakedRelayHttps.isEmpty()) {
            throw GradleException("aegis.relayHttpsPins без aegis.defaultRelayHttps: пины относятся к HTTPS-адресу")
        }
        buildConfigField("String", "DEFAULT_RELAY_HTTPS", "\"$bakedRelayHttps\"")
        buildConfigField("String", "RELAY_HTTPS_PINS", "\"${relayHttpsPins.joinToString(",")}\"")

        // На ФАЗЕ 1 собираем только приоритетные ABI (см. gradle.properties).
        ndk {
            abiFilters += (project.findProperty("aegis.abiFilters") as String? ?: "arm64-v8a")
                .split(",").map { it.trim() }
        }

        // Кадровый слой поверх DTLS-SRTP (§1.14): нативный C++-шим
        // libaegis_frame.so реализует webrtc::FrameEncryptorInterface. Свою
        // крипту не пишем (§1) — AEAD зовётся из Rust-ядра по dlopen.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // c++_static: не тянем libc++_shared.so. std::vector в сигнатуре
                // Decrypt приходит от WebRTC по const-ref и нами не конструируется.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    // §1.14: регистрируем сборку C++ только если она не отключена. При
    // -Paegis.buildFrame=false (или отсутствии NDK/CMake) APK собирается БЕЗ
    // libaegis_frame.so — кадровый слой тогда честно no-op (WebRtcMediaEngine
    // проверяет NativeFrameCrypto.isAvailable), медиа остаётся под DTLS-SRTP.
    if ((project.findProperty("aegis.buildFrame") as String? ?: "true").toBoolean()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        // Необязательный общий debug-ключ (как его создать — README.md, раздел
        // «Подпись»): с ним APK, собранные на разных машинах, ставятся друг поверх
        // друга. Без файла — стандартный ~/.android/debug.keystore этой машины.
        // Подписывает ТОЛЬКО `com.aegis.messenger.debug`. Релизный ключ сюда не
        // кладётся (§12.1).
        val sharedDebugKeystore = file("signing/aegis-debug.jks")
        if (sharedDebugKeystore.isFile) {
            getByName("debug") {
                storeFile = sharedDebugKeystore
                storePassword = "android"
                keyAlias = "aegis-debug"
                keyPassword = "android"
            }
        }
        val releaseStoreFile = releaseKeystore.getProperty("storeFile")?.trim()
        if (!releaseStoreFile.isNullOrEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Reproducible builds — важны для доверия (§9, §12.1).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Секреты подписи НЕ коммитятся (§12.1); см. releaseKeystore выше.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Reproducible builds + приватность (P9, §9/§12.1): по умолчанию AGP вшивает
    // в APK/AAB подписанный Google блок с метаданными зависимостей
    // (`dependencies` в signing block). Он НЕдетерминирован (ломает
    // воспроизводимость) и раскрывает список библиотек — отключаем обоё.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Pluggable transports (§14.7) поставляются как исполняемые ELF-бинари
            // с именами lib*.so (liblyrebird.so/libsnowflake.so). Их нужно
            // РАСПАКОВЫВАТЬ в nativeLibraryDir: на Android 10+ (target API ≥ 29)
            // exec разрешён ТОЛЬКО из этого каталога, а не из data-dir/assets.
            // useLegacyPackaging=true ⇒ extractNativeLibs=true для всех .so
            // (включая libaegis_core/libsqlcipher/WebRTC — они от этого не
            // страдают). Так Arti (feature pt-client) может запустить PT.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Модули клиента
    implementation(project(":core-common"))
    implementation(project(":core-ui"))
    implementation(project(":crypto"))
    implementation(project(":network-tor"))
    implementation(project(":data-storage"))

    // AndroidX / жизненный цикл
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // (Де)сериализация полезной нагрузки обмена PSK (JSON QR/NFC, §6.2).
    implementation(libs.kotlinx.serialization.json)

    // P7 (§14.3): биометрический вход/разблокировка через BiometricPrompt.
    // Тянет androidx.fragment → MainActivity наследует FragmentActivity.
    implementation(libs.androidx.biometric)
    // Явно поднимаем androidx.fragment до версии, совместимой с activity 1.9.x:
    // иначе транзитивный fragment 1.2.5 (от biometric) роняет приложение с
    // «Can only use lower 16 bits for requestCode» при запросе runtime-разрешений.
    implementation(libs.androidx.fragment.ktx)

    // QR для ручного обмена PSK вне сети (§6.2/P4): генерация + сканирование.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // P8 (§7.5/§10.10): звонки поверх WebRTC (DTLS-SRTP). Если артефакт не
    // подтянется/не соберётся под ABI — WebRtcMediaEngine недоступен, звонки
    // работают на PlaceholderMediaEngine (сигналинг+ключи+SAS), см. docs/calls.md.
    implementation(libs.webrtc)

    // Тесты
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// Pluggable transports (§14.7): кросс-компиляция PT-бинарей (lyrebird=obfs4+
// meek_lite, snowflake) под ABI и укладка в app/src/main/jniLibs/<abi>/lib*.so.
//
// Best-effort (как :crypto:cargoNdkBuild): если Go/NDK не найдены — задача
// пропускается и APK собирается БЕЗ PT-бинарей. Тогда «прямые» (vanilla) мосты
// и obfs4-мосты с системным PT работать не будут, но приложение не блокируется.
// Свои PT мы НЕ пишем (§1) — берём аудированные реализации Tor Project.
//
// Управление: -Paegis.buildPt=false — отключить; PT можно собрать и вручную:
//   ANDROID_NDK_HOME=... pt/build_pt.sh arm64-v8a x86_64
// ---------------------------------------------------------------------------

/** Собирать ли PT при обычной сборке (можно отключить: -Paegis.buildPt=false). */
val aegisBuildPt: Boolean =
    (project.findProperty("aegis.buildPt") as String? ?: "true").toBoolean()

val aegisAbiFilters: List<String> =
    (project.findProperty("aegis.abiFilters") as String? ?: "arm64-v8a")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

val ptBuildScript: File = rootProject.file("pt/build_pt.sh")

fun resolveOnPath(name: String): File? {
    val extra = listOf("/opt/homebrew/bin", "/usr/local/go/bin", "${System.getProperty("user.home")}/go/bin")
    val dirs = buildList {
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(it) }
        addAll(extra)
    }
    return dirs.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
}

fun ptLocalProperty(name: String): String? {
    val f = rootProject.file("local.properties")
    if (!f.isFile) return null
    return Properties().apply { f.inputStream().use { load(it) } }.getProperty(name)
}

fun ptResolveNdk(): File? {
    sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")
        .mapNotNull { System.getenv(it) }.map { File(it) }
        .firstOrNull { it.isDirectory }?.let { return it }
    val sdk = ptLocalProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    if (sdk != null) {
        val byVersion = File(sdk, "ndk/${libs.versions.ndk.get()}")
        if (byVersion.isDirectory) return byVersion
    }
    return null
}

val buildPluggableTransports by tasks.registering {
    group = "aegis"
    description = "Кросс-компилирует pluggable transports (obfs4/meek/snowflake) под ABI в jniLibs."

    val go = resolveOnPath("go")
    val ndk = ptResolveNdk()

    onlyIf {
        when {
            !aegisBuildPt -> {
                logger.lifecycle("[aegis] buildPluggableTransports пропущен: aegis.buildPt=false.")
                false
            }
            !ptBuildScript.isFile -> {
                logger.warn("[aegis] buildPluggableTransports пропущен: не найден $ptBuildScript.")
                false
            }
            go == null -> {
                logger.warn("[aegis] buildPluggableTransports пропущен: go не найден в PATH. " +
                    "PT-бинари не будут вложены (obfs4/snowflake/meek недоступны в рантайме).")
                false
            }
            ndk == null -> {
                logger.warn("[aegis] buildPluggableTransports пропущен: NDK не найден.")
                false
            }
            else -> true
        }
    }

    doLast {
        val goDir = go!!.parentFile.absolutePath
        val args = buildList {
            add("bash"); add(ptBuildScript.absolutePath); addAll(aegisAbiFilters)
        }
        logger.lifecycle("[aegis] Сборка PT: ${aegisAbiFilters.joinToString(",")} (NDK=${ndk!!.absolutePath})")
        val process = ProcessBuilder(args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .apply {
                environment()["ANDROID_NDK_HOME"] = ndk.absolutePath
                environment()["PATH"] = goDir + File.pathSeparator + (System.getenv("PATH") ?: "")
                // build_pt.sh передаёт линкеру -checklinkname=0 (нужен Go 1.23+).
                // Системный go 1.22 с GOTOOLCHAIN=local из‑за этого падает, и APK
                // уходит без мостов. Пин совпадает с комментарием в pt/build_pt.sh.
                environment()["AEGIS_GO_TOOLCHAIN"] =
                    System.getenv("AEGIS_GO_TOOLCHAIN") ?: "go1.26.5"
            }
            .start()
        val code = process.waitFor()
        if (code != 0) {
            logger.warn("[aegis] Сборка PT завершилась с кодом $code — APK соберётся без PT-бинарей.")
        }
    }
}

// PT-бинари должны лежать в jniLibs до упаковки APK.
tasks.named("preBuild").configure { dependsOn(buildPluggableTransports) }

gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.project == project && it.name.contains("Release") }
    if (buildsRelease && android.signingConfigs.findByName("release") == null) {
        logger.warn(
            "[aegis] keystore.properties не найден — release-APK будет НЕПОДПИСАННЫМ. " +
                "Создайте keystore.properties (README.md, раздел «Подпись»); раздавать debug-сборку нельзя.",
        )
    }
}
