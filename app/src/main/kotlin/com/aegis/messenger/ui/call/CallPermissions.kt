package com.aegis.messenger.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Запрашивает runtime-разрешения, необходимые для звонка (P8, §7.5), ПЕРЕД тем как
 * поднять медиадвижок:
 *  - `RECORD_AUDIO` — всегда (голос);
 *  - `CAMERA` — дополнительно для видеозвонка.
 *
 * Возвращает функцию `ensure(video, onGranted)`: если нужные разрешения уже есть —
 * действие выполняется сразу; иначе показывается системный диалог, и действие
 * выполняется только при выдаче ВСЕХ требуемых разрешений (иначе тихо отменяется —
 * звонок просто не начинается).
 */
@Composable
fun rememberCallPermissionRequester(): (Boolean, () -> Unit) -> Unit {
    val context = LocalContext.current
    val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingVideo = remember { mutableStateOf(false) }

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val action = pendingAction.value
        pendingAction.value = null
        val audioOk = granted(Manifest.permission.RECORD_AUDIO)
        val cameraOk = !pendingVideo.value || granted(Manifest.permission.CAMERA)
        if (action != null && audioOk && cameraOk) action()
    }

    return remember {
        { video, onGranted ->
            val needed = buildList {
                if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (video && !granted(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            }
            if (needed.isEmpty()) {
                onGranted()
            } else {
                pendingAction.value = onGranted
                pendingVideo.value = video
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}
