package com.aegis.messenger

import android.app.Application
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.diag.ProcSelf
import com.aegis.messenger.appearance.AppIconManager
import com.aegis.messenger.diag.DiagnosticNative
import com.aegis.messenger.diag.ProcessDeathReport
import java.io.File

/**
 * Класс приложения AEGIS. Владеет [AppContainer] (ручной DI).
 */
class AegisApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Хук падений ставим сразу: если журнал включат в [initialize], crash
        // после этого уже попадёт в файл. До включения [crash] — no-op.
        DiagnosticLog.installCrashHook()
        val nativeCrash = File(cacheDir, "diagnostics/aegis-native-crash.txt")
        // Сначала читаем прошлый sidecar, потом ставим хук (он обнуляет файл).
        DiagnosticLog.setNativeCrashFile(nativeCrash)
        val nativeHook = DiagnosticNative.install(nativeCrash)
        // Крошки из Kotlin попадают в нативное кольцо и оказываются в sidecar
        // рядом с сигналом — по ним видно, на какой операции умер процесс.
        DiagnosticLog.setNativeBreadcrumbSink(
            if (nativeHook) DiagnosticNative::breadcrumb else null,
        )
        DiagnosticLog.event(
            "app", "процесс создан",
            "pid" to android.os.Process.myPid(),
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "хукСигналов" to nativeHook,
            "sidecarFd" to DiagnosticNative.sidecarFd(),
            *ProcSelf.fields(),
        )
        // Самая ранняя точка, где известна причина смерти ПРОШЛОГО процесса.
        // Журнал на этот момент может быть ещё выключен — тогда разбор повторит
        // AppContainer сразу после enable(). dumpOnce гарантирует один вывод.
        ProcessDeathReport.dumpOnce(applicationContext)
        // Согласовать LAUNCHER-alias'ы до UI: после обновления APK или если OEM
        // убила процесс посреди смены иконки, иначе ярлык пропадает «насовсем».
        runCatching { AppIconManager.reconcile(this) }
            .onFailure { DiagnosticLog.event("ui", "ошибка reconcile иконки", "ошибка" to it.javaClass.simpleName) }
        container = AppContainer(applicationContext)
        // Автоблокировка по бездействию (§14.3): контроллер слушает жизненный
        // цикл Activity (уход в фон / возврат) и состояние аккаунта.
        registerActivityLifecycleCallbacks(container.idleLockController)
    }
}
