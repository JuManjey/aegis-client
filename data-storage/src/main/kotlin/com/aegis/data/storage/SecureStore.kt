package com.aegis.data.storage

import com.aegis.core.common.appearance.AppearanceSettings
import com.aegis.core.common.contact.DiscoverySettings
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticSettings
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorNetworkSettings

/**
 * Защищённое хранилище конфигурации и данных (§6.3).
 *
 * Профиль/настройки безопасности хранятся в зашифрованном виде; на ФАЗЕ 1 —
 * контракт. Реализация (P6) сядет на SQLCipher (данные) + EncryptedSharedPrefs/
 * DataStore под ключом из [KeyStoreManager] (настройки).
 */
interface SecureStore {

    /** Загрузить настройки безопасности (или дефолт «Параноик» при первом запуске). */
    suspend fun loadSecuritySettings(): SecuritySettings

    /** Сохранить настройки безопасности атомарно. */
    suspend fun saveSecuritySettings(settings: SecuritySettings)

    /** Загрузить настройки сети/Tor: мосты, PT, onion-релей (§14.7). */
    suspend fun loadTorNetworkSettings(): TorNetworkSettings

    /** Сохранить настройки сети/Tor атомарно. */
    suspend fun saveTorNetworkSettings(settings: TorNetworkSettings)

    /** Загрузить настройки приватности (§14.2) или дефолт при первом запуске. */
    suspend fun loadPrivacySettings(): PrivacySettings

    /** Сохранить настройки приватности атомарно. */
    suspend fun savePrivacySettings(settings: PrivacySettings)

    /** Загрузить настройки уведомлений (§14.5) или дефолт при первом запуске. */
    suspend fun loadNotificationSettings(): NotificationSettings

    /** Сохранить настройки уведомлений атомарно. */
    suspend fun saveNotificationSettings(settings: NotificationSettings)

    /** Загрузить настройки оформления (§14.6) или дефолт при первом запуске. */
    suspend fun loadAppearanceSettings(): AppearanceSettings

    /** Сохранить настройки оформления атомарно. */
    suspend fun saveAppearanceSettings(settings: AppearanceSettings)

    /** Загрузить идентичность этого устройства (§7.1.1) или дефолт (первичное). */
    suspend fun loadDeviceSettings(): DeviceSettings

    /** Сохранить идентичность этого устройства атомарно. */
    suspend fun saveDeviceSettings(settings: DeviceSettings)

    /**
     * Загрузить настройки диагностики (журнал отладки) или дефолт «выключено».
     * Хранятся так же, как остальные — под обёрткой Keystore, — чтобы факт
     * включённой записи не читался с диска посторонним процессом.
     */
    suspend fun loadDiagnosticSettings(): DiagnosticSettings

    /** Сохранить настройки диагностики атомарно. */
    suspend fun saveDiagnosticSettings(settings: DiagnosticSettings)

    /**
     * Загрузить состояние удалённого добавления контактов
     * (`docs/contact_requests.md`) или дефолт «теги не созданы».
     *
     * СОДЕРЖИТ СЕКРЕТЫ (теги обнаружения), поэтому обязательно под обёрткой
     * Keystore — как и остальные файлы этого хранилища.
     */
    suspend fun loadDiscoverySettings(): DiscoverySettings

    /** Сохранить состояние удалённого добавления атомарно. */
    suspend fun saveDiscoverySettings(settings: DiscoverySettings)

    /**
     * Удаляет ВСЕ файлы хранилища (часть экстренной очистки, §7.4). После вызова
     * каждый `load*` возвращает значение по умолчанию.
     *
     * Уничтожения мастер-ключа Keystore для этого мало: процесс продолжает жить,
     * и первая же запись настройки создала бы новый ключ, а соседние файлы
     * прежнего аккаунта (теги обнаружения, реестр устройств) остались бы лежать
     * мусором рядом с данными нового.
     */
    suspend fun clear()
}
