package com.aegis.messenger.diag

import android.util.Log
import java.io.File

/**
 * Мост к C++ sidecar'у native-сигналов (`diag_crash_handler.cpp` в
 * `libaegis_frame.so`). Java не ловит SIGSEGV/SIGABRT/SIGTRAP в WebRTC — без
 * этого файла в журнале виден только обрыв строк.
 *
 * Best-effort: если шим не собран (`-Paegis.buildFrame=false`) — хук не ставится,
 * журнал Java продолжает работать.
 */
object DiagnosticNative {
    private const val TAG = "AegisDiag"

    private val libLoaded: Boolean = runCatching {
        System.loadLibrary("aegis_frame")
        true
    }.getOrElse {
        Log.w(TAG, "libaegis_frame.so недоступна — native crash sidecar выключен: ${it.message}")
        false
    }

    /** Доступна ли нативная часть диагностики в этой сборке. */
    val isAvailable: Boolean get() = libLoaded

    fun install(crashFile: File): Boolean {
        if (!libLoaded) return false
        crashFile.parentFile?.mkdirs()
        return runCatching { nativeInstall(crashFile.absolutePath) }.getOrDefault(false)
    }

    /**
     * WebRTC при initialize перехватывает SIGSEGV. Ставим наш хук снова поверх,
     * предыдущий остаётся в цепочке (tombstone Android не теряется).
     */
    fun rebind(): Boolean {
        if (!libLoaded) return false
        return runCatching { nativeRebind() }.getOrDefault(false)
    }

    /**
     * Кладёт «хлебную крошку» в нативное кольцо (8 последних записей). При
     * нативном падении они попадают в sidecar ПЕРЕД обходом стека, поэтому
     * отвечают на вопрос «на какой операции умерли» даже когда Java-журнал
     * оборвался, а стек размотать не удалось.
     *
     * Стоимость — один JNI-вызов и memcpy: можно ставить перед каждым опасным
     * нативным шагом (createPeerConnection, setLocalDescription, TurnPort, …).
     */
    fun breadcrumb(text: String) {
        if (!libLoaded) return
        runCatching { nativeBreadcrumb(text) }
    }

    /**
     * Дескриптор sidecar-файла или отрицательное значение, если хук не встал.
     * Пишем в журнал при старте звонка: «хук был, а отчёта нет» — это отдельный
     * диагноз (процесс убит извне SIGKILL/LMK, а не упал сам).
     */
    fun sidecarFd(): Int {
        if (!libLoaded) return -1
        return runCatching { nativeFd() }.getOrDefault(-1)
    }

    private external fun nativeInstall(path: String): Boolean
    private external fun nativeRebind(): Boolean
    private external fun nativeBreadcrumb(text: String)
    private external fun nativeFd(): Int
}
