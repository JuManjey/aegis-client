package com.aegis.messenger.ui.components

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aegis.messenger.R

/**
 * Предупреждение в отлаживаемой сборке: через режим разработчика к её процессу
 * подключается отладчик, и знающий PIN телефона получит данные открытого
 * аккаунта или станет перебирать пароль в обход ограничения попыток.
 * Проверяется флаг установленного APK, а не `BuildConfig.DEBUG`, — так баннер
 * появится и у пересобранного с `debuggable=true` релиза.
 */
@Composable
fun DebugBuildBanner(modifier: Modifier = Modifier) {
    val debuggable = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!debuggable) return
    AegisHudBanner(
        text = stringResource(R.string.debug_build_warning),
        tone = HudBannerTone.DANGER,
        modifier = modifier,
    )
}
