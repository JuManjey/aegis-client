package com.aegis.messenger.multidevice

import android.util.Base64
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.device.LinkedDevice
import com.aegis.crypto.CryptoModule
import com.aegis.messenger.account.AccountCreationResult
import com.aegis.messenger.psk.PrekeyBundle

/**
 * Общая инфраструктура для живой проверки self-sync между ДВУМЯ устройствами
 * ОДНОГО аккаунта (мультидевайс §7.1.1, `docs/multidevice.md` §4.2/§6/§6.2).
 *
 * Устройства делят одну мнемонику (тот же `accountId`), но у каждого свой Signal
 * identity (MD-1). Follow-up №2.3: `msg`/`revoke` идут двойным конвейером — слой A
 * (каскад на `selfSyncSecret`) → слой B (per-device Signal-сессия между моими
 * устройствами, forward secrecy). Здесь реплицируется ровно то, что делает
 * `SelfSyncManager.fanOut` (сеть в тестах инертна: релей-клиент = null), чтобы
 * подать конверт в продуктовый `onWire` так же, как это сделал бы приёмный цикл.
 */
object SelfSyncTestSupport {

    private const val PASSWORD = "password123"

    /** Создаёт первичное устройство (deviceId=1) с включённым мультидевайсом. Возвращает мнемонику. */
    suspend fun primary(dev: MdTestDevice, login: String = "primary"): String {
        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = login,
            password = PASSWORD,
            multiDeviceAllowed = true,
        )
        check(created is AccountCreationResult.Success) { "createAccount: $created" }
        dev.secureStore.saveDeviceSettings(DeviceSettings(localDeviceId = 1, label = "Primary"))
        dev.reloadDeviceSettings()
        return (created as AccountCreationResult.Success).mnemonic
    }

    /**
     * Поднимает линкованное устройство [deviceId] на той же [mnemonic] (тот же
     * аккаунт, свой Signal identity). [registry] — начальный реестр моих устройств.
     */
    suspend fun linked(
        dev: MdTestDevice,
        mnemonic: String,
        deviceId: Int,
        label: String = "Tablet",
        registry: List<LinkedDevice> = emptyList(),
    ) {
        val res = dev.accountManager.recoverAccount(
            mnemonic = mnemonic,
            password = PASSWORD,
            login = "user",
            multiDeviceAllowed = true,
        )
        check(res is AccountCreationResult.Success) { "recoverAccount: $res" }
        dev.secureStore.saveDeviceSettings(
            DeviceSettings(localDeviceId = deviceId, label = label, linkedDevices = registry),
        )
        dev.reloadDeviceSettings()
    }

    fun accountId(dev: MdTestDevice): String = dev.accountManager.currentIdentity()!!.accountId

    /** self-sync секрет аккаунта (общий моим устройствам). Вызывающий затирает сам. */
    fun selfSyncSecret(mnemonic: String): ByteArray = CryptoModule.psk.deriveSelfSyncSecret(mnemonic)!!

    /** prekey-бандл устройства (для бутстрапа его self-sync Signal-сессии, §5/§6.2). */
    fun bundleOf(dev: MdTestDevice): String = dev.signalSessionManager.publishBundleJson()

    /**
     * Строит self-sync конверт `kind` (msg/revoke) двойным конвейером слой A→B от
     * [sender] (его localDeviceId → `senderDevice`) к устройству [targetDeviceId]
     * (его [targetBundle]) — ровно как `SelfSyncManager.fanOut`. Каждый вызов
     * продвигает ratchet отправителя (PreKey → затем Whisper), поэтому повторный
     * вызов с тем же контентом даёт ДРУГОЙ валидный шифртекст (для проверки дедупа).
     */
    fun buildWire(
        sender: MdTestDevice,
        accountId: String,
        senderDevice: Int,
        targetDeviceId: Int,
        targetBundle: String,
        secret: ByteArray,
        kind: String,
        contentJson: String,
    ): String {
        val inner = CryptoModule.createCascadeCipher(secret).encrypt(contentJson.toByteArray(Charsets.UTF_8))
        val env = sender.signalSessionManager.encryptTo(
            accountId, targetDeviceId, targetBundle, inner, PrekeyBundle.identityB64(targetBundle),
        )
        val ctB64 = Base64.encodeToString(env.ciphertext, Base64.NO_WRAP)
        return """{"channel":"self-sync","kind":"$kind","senderDevice":$senderDevice,"ciphertextB64":"$ctB64","sigType":"${env.type}"}"""
    }

    fun msgContent(contactId: String, msgId: String, text: String, outgoing: Boolean = false): String =
        """{"contactId":"$contactId","msgId":"$msgId","outgoing":$outgoing,"text":"$text","timestampMs":1000}"""

    fun revokeContent(deviceId: Int): String = """{"deviceId":$deviceId}"""
}
