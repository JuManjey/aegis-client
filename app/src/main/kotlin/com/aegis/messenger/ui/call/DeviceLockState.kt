package com.aegis.messenger.ui.call

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Заблокирован ли экран устройства прямо сейчас (§14.3).
 *
 * Нужно экрану звонка: во время входящего вызова окно показывается ПОВЕРХ
 * блокировки (`MainActivity.applyLockScreenFlags`), и всё, что можно прочитать не
 * разблокировав телефон, обязано быть обезличенным — имя контакта, код сверки
 * SAS и детали этапов появляются только после разблокировки.
 *
 * Слушаем системные широковещательные сообщения экрана и дополнительно
 * переспрашиваем состояние, ПОКА телефон считается заблокированным:
 * `ACTION_USER_PRESENT` приходит не на всех прошивках и не при снятии
 * небезопасной блокировки, а «залипнуть» в обезличенном виде после разблокировки
 * нельзя. Опрос идёт только в заблокированном состоянии и только пока экран
 * звонка в композиции, поэтому на расход батареи в покое не влияет.
 */
@Composable
fun rememberDeviceLocked(): Boolean {
    val context = LocalContext.current
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(keyguard?.isKeyguardLocked == true) }

    DisposableEffect(keyguard) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                locked = keyguard?.isKeyguardLocked == true
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Защищённые системные широковещания, но флаг обязателен на Android 14+.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        locked = keyguard?.isKeyguardLocked == true
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(locked) {
        while (locked) {
            delay(POLL_MS)
            locked = keyguard?.isKeyguardLocked == true
        }
    }
    return locked
}

private const val POLL_MS = 400L
