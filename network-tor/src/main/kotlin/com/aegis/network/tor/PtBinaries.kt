package com.aegis.network.tor

import android.content.Context
import com.aegis.core.common.security.PtBinaryNames
import java.io.File

/**
 * Резолвинг вложенных PT-бинарей (§14.7) на устройстве.
 *
 * PT поставляются в APK как исполняемые ELF-бинари с именами `lib*.so`
 * (`liblyrebird.so`, `libsnowflake.so`) и распаковываются установщиком в
 * `applicationInfo.nativeLibraryDir` — ЕДИНСТВЕННЫЙ каталог, из которого на
 * Android 10+ (target API ≥ 29) разрешён `exec` (W^X). Именно оттуда Arti
 * (feature `pt-client`) запускает управляемый транспорт.
 *
 * Свою крипту/PT мы не пишем (§1) — используем официальные бинари Tor Project.
 */
object PtBinaries {

    /** Каталог с распакованными нативными библиотеками приложения. */
    fun libDir(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir)

    /**
     * Абсолютный путь к вложенному PT-бинарю по базовому имени (`lyrebird`/
     * `snowflake`), либо `null`, если бинарь не вложен в эту сборку/ABI.
     */
    fun binaryPath(context: Context, baseName: String): String? {
        val f = File(libDir(context), "lib$baseName.so")
        return if (f.isFile) f.absolutePath else null
    }

    /** Путь к PT-бинарю, реализующему заданный протокол (`obfs4`/`snowflake`/…). */
    fun binaryPathForProtocol(context: Context, protocol: String): String? {
        val base = PtBinaryNames.binaryFor(protocol) ?: return null
        return binaryPath(context, base)
    }

    /**
     * Набор доступных (реально вложенных) PT-протоколов в этой сборке под текущее
     * ABI. Используется для индикации в UI и фильтрации неработоспособных мостов.
     */
    fun availableProtocols(context: Context): Set<String> {
        val out = linkedSetOf<String>()
        if (binaryPath(context, "lyrebird") != null) out += PtBinaryNames.LYREBIRD_PROTOCOLS
        if (binaryPath(context, "snowflake") != null) out += PtBinaryNames.SNOWFLAKE_PROTOCOLS
        return out
    }
}
