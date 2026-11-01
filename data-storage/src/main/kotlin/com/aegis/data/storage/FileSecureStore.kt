package com.aegis.data.storage

import android.content.Context
import com.aegis.core.common.appearance.AppearanceSettings
import com.aegis.core.common.contact.DiscoverySettings
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.diag.DiagnosticSettings
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorNetworkSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Реализация [SecureStore] поверх приватного файла, обёрнутого аппаратным ключом
 * Keystore ([KeyStoreManager.wrap]). Настройки читаемы до разблокировки аккаунта
 * (нужны для применения FLAG_SECURE/Tor на старте), но не в открытом виде на диске.
 *
 * P6 (SQLCipher) заменит файловое хранение для крупных данных; настройки же
 * компактны и остаются в обёрнутом файле.
 */
class FileSecureStore(
    appContext: Context,
    private val keyStoreManager: KeyStoreManager,
    private val json: Json = SettingsCodec.json,
) : SecureStore {

    private val file: File = File(appContext.filesDir, FILE_NAME)
    private val torFile: File = File(appContext.filesDir, TOR_FILE_NAME)
    private val privacyFile: File = File(appContext.filesDir, PRIVACY_FILE_NAME)
    private val notificationFile: File = File(appContext.filesDir, NOTIFICATION_FILE_NAME)
    private val appearanceFile: File = File(appContext.filesDir, APPEARANCE_FILE_NAME)
    private val deviceFile: File = File(appContext.filesDir, DEVICE_FILE_NAME)
    private val diagnosticFile: File = File(appContext.filesDir, DIAGNOSTIC_FILE_NAME)
    private val discoveryFile: File = File(appContext.filesDir, DISCOVERY_FILE_NAME)

    /** Все файлы хранилища — для [clear]. Новый файл настроек обязан попасть сюда. */
    private val allFiles: List<File> = listOf(
        file,
        torFile,
        privacyFile,
        notificationFile,
        appearanceFile,
        deviceFile,
        diagnosticFile,
        discoveryFile,
    )

    override suspend fun loadSecuritySettings(): SecuritySettings = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext SecuritySettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(file.readBytes())
            SettingsCodec.decodeSecurity(plaintext.decodeToString(), json)
        }.getOrElse { e ->
            // Откат к умолчанию — это сброс всех настроек безопасности; без записи
            // в журнал он был бы неразличим с «пользователь ничего не менял».
            DiagnosticLog.e(DIAG_TAG, e) { "настройки безопасности не прочитаны — применены значения по умолчанию" }
            SecuritySettings.DEFAULT
        }
    }

    override suspend fun saveSecuritySettings(settings: SecuritySettings) = withContext(Dispatchers.IO) {
        val plaintext = SettingsCodec.encodeSecurity(settings, json).encodeToByteArray()
        writeWrapped(file, FILE_NAME, plaintext)
    }

    override suspend fun loadTorNetworkSettings(): TorNetworkSettings = withContext(Dispatchers.IO) {
        if (!torFile.isFile) return@withContext TorNetworkSettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(torFile.readBytes())
            json.decodeFromString(TorNetworkSettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(TorNetworkSettings.DEFAULT)
    }

    override suspend fun saveTorNetworkSettings(settings: TorNetworkSettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(TorNetworkSettings.serializer(), settings).encodeToByteArray()
        writeWrapped(torFile, TOR_FILE_NAME, plaintext)
    }

    override suspend fun loadPrivacySettings(): PrivacySettings = withContext(Dispatchers.IO) {
        if (!privacyFile.isFile) return@withContext PrivacySettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(privacyFile.readBytes())
            json.decodeFromString(PrivacySettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(PrivacySettings.DEFAULT)
    }

    override suspend fun savePrivacySettings(settings: PrivacySettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(PrivacySettings.serializer(), settings).encodeToByteArray()
        writeWrapped(privacyFile, PRIVACY_FILE_NAME, plaintext)
    }

    override suspend fun loadNotificationSettings(): NotificationSettings = withContext(Dispatchers.IO) {
        if (!notificationFile.isFile) return@withContext NotificationSettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(notificationFile.readBytes())
            json.decodeFromString(NotificationSettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(NotificationSettings.DEFAULT)
    }

    override suspend fun saveNotificationSettings(settings: NotificationSettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(NotificationSettings.serializer(), settings).encodeToByteArray()
        writeWrapped(notificationFile, NOTIFICATION_FILE_NAME, plaintext)
    }

    override suspend fun loadAppearanceSettings(): AppearanceSettings = withContext(Dispatchers.IO) {
        if (!appearanceFile.isFile) return@withContext AppearanceSettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(appearanceFile.readBytes())
            json.decodeFromString(AppearanceSettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(AppearanceSettings.DEFAULT)
    }

    override suspend fun saveAppearanceSettings(settings: AppearanceSettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(AppearanceSettings.serializer(), settings).encodeToByteArray()
        writeWrapped(appearanceFile, APPEARANCE_FILE_NAME, plaintext)
    }

    override suspend fun loadDeviceSettings(): DeviceSettings = withContext(Dispatchers.IO) {
        if (!deviceFile.isFile) return@withContext DeviceSettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(deviceFile.readBytes())
            json.decodeFromString(DeviceSettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(DeviceSettings.DEFAULT)
    }

    override suspend fun saveDeviceSettings(settings: DeviceSettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(DeviceSettings.serializer(), settings).encodeToByteArray()
        writeWrapped(deviceFile, DEVICE_FILE_NAME, plaintext)
    }

    override suspend fun loadDiagnosticSettings(): DiagnosticSettings = withContext(Dispatchers.IO) {
        if (!diagnosticFile.isFile) return@withContext DiagnosticSettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(diagnosticFile.readBytes())
            json.decodeFromString(DiagnosticSettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(DiagnosticSettings.DEFAULT)
    }

    override suspend fun saveDiagnosticSettings(settings: DiagnosticSettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(DiagnosticSettings.serializer(), settings).encodeToByteArray()
        writeWrapped(diagnosticFile, DIAGNOSTIC_FILE_NAME, plaintext)
    }

    override suspend fun loadDiscoverySettings(): DiscoverySettings = withContext(Dispatchers.IO) {
        if (!discoveryFile.isFile) return@withContext DiscoverySettings.DEFAULT
        runCatching {
            val plaintext = keyStoreManager.unwrap(discoveryFile.readBytes())
            json.decodeFromString(DiscoverySettings.serializer(), plaintext.decodeToString())
        }.getOrDefault(DiscoverySettings.DEFAULT)
    }

    override suspend fun saveDiscoverySettings(settings: DiscoverySettings) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(DiscoverySettings.serializer(), settings).encodeToByteArray()
        writeWrapped(discoveryFile, DISCOVERY_FILE_NAME, plaintext)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        allFiles.forEach { target ->
            runCatching { target.delete() }
            // Хвост прерванной атомарной записи ([writeWrapped]).
            runCatching { File(target.parentFile, "${target.name}.tmp").delete() }
        }
        Unit
    }

    /** Атомарная запись файла, обёрнутого аппаратным ключом Keystore. */
    private fun writeWrapped(target: File, name: String, plaintext: ByteArray) {
        val wrapped = keyStoreManager.wrap(plaintext)
        val tmp = File(target.parentFile, "$name.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(target)) {
            target.writeBytes(wrapped)
            tmp.delete()
        }
    }

    private companion object {
        const val DIAG_TAG = "secure-store"
        const val FILE_NAME = "settings.aegis"
        const val TOR_FILE_NAME = "tor_network.aegis"
        const val PRIVACY_FILE_NAME = "privacy.aegis"
        const val NOTIFICATION_FILE_NAME = "notifications.aegis"
        const val APPEARANCE_FILE_NAME = "appearance.aegis"
        const val DEVICE_FILE_NAME = "device.aegis"
        const val DIAGNOSTIC_FILE_NAME = "diagnostics.aegis"
        const val DISCOVERY_FILE_NAME = "discovery.aegis"
    }
}
