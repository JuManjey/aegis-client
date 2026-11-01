package com.aegis.network.tor

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.TorMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Заглушка Tor-транспорта, когда нативный Arti не собран (нет фичи `tor`).
 *
 * НЕ имитирует успешный bootstrap и НЕ выдаёт SOCKS-порт: индикатор должен
 * честно показывать, что Tor в этой сборке недоступен. Иначе демонстрации
 * снимают «Tor активен» с APK без onion-трафика.
 */
class FakeTorTransport : TorTransport {

    private val _status = MutableStateFlow<TorStatus>(TorStatus.Unavailable)
    override val status: StateFlow<TorStatus> = _status.asStateFlow()

    @Volatile
    private var changedAt = 0L
    override val statusChangedAtElapsed: Long
        get() = changedAt

    override val socksPort: Int?
        get() = null

    override suspend fun applyMode(mode: TorMode) {
        when (mode) {
            TorMode.OFF -> {
                DiagnosticLog.event(DIAG_TAG, "заглушка выключена")
                publish(TorStatus.Disabled)
            }
            TorMode.ALWAYS -> {
                DiagnosticLog.w(DIAG_TAG) {
                    "нативный Tor не собран — статус Unavailable, SOCKS нет"
                }
                publish(TorStatus.Unavailable)
            }
        }
    }

    override suspend fun restart(reason: String) {
        DiagnosticLog.event(DIAG_TAG, "заглушка: restart игнорируется", "причина" to reason)
        if (_status.value !is TorStatus.Disabled) publish(TorStatus.Unavailable)
    }

    override suspend fun shutdown() {
        publish(TorStatus.Disabled)
    }

    private fun publish(next: TorStatus) {
        if (_status.value == next) return
        changedAt = runCatching { android.os.SystemClock.elapsedRealtime() }
            .getOrElse { System.nanoTime() / 1_000_000L }
        _status.value = next
    }

    private companion object {
        const val DIAG_TAG = "tor-fake"
    }
}
