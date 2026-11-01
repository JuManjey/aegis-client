package com.aegis.messenger.account

import android.content.Context
import android.util.Base64
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.account.AccountIdentity
import com.aegis.core.common.account.AccountKdfParams
import com.aegis.core.common.account.AccountLogin
import com.aegis.core.common.account.AccountProfile
import com.aegis.core.common.account.AccountState
import com.aegis.core.common.account.SealedAccountRecord
import com.aegis.core.common.account.UnlockBackoff
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.crypto.AccountCrypto
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.data.storage.AccountStore
import com.aegis.data.storage.EmergencyWipe
import com.aegis.data.storage.KeyStoreManager
import com.aegis.data.storage.WipeReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher

/** Результат создания/восстановления аккаунта. */
sealed interface AccountCreationResult {
    /** Успех. [mnemonic] показывается пользователю ОДИН раз для сохранения (§7.1). */
    data class Success(val profile: AccountProfile, val mnemonic: String) : AccountCreationResult
    data class Failure(val reason: String) : AccountCreationResult

    /**
     * Операция заменила бы аккаунт, который сейчас хранится на устройстве.
     * Ничего не изменено: продолжить можно только повторным вызовом с
     * `replaceExisting = true` — тогда устройство сначала будет полностью
     * очищено от данных прежнего аккаунта (§7.4).
     */
    data object ReplaceConfirmationRequired : AccountCreationResult
}

/** Результат разблокировки (входа). */
sealed interface UnlockResult {
    data class Success(val profile: AccountProfile) : UnlockResult

    /**
     * Неверный пароль. [lockoutMs] — задержка до следующей попытки (0 — можно
     * сразу); [attemptsBeforeWipe] — сколько неудач осталось до очистки, если
     * она включена, иначе `null`.
     */
    data class WrongPassword(val lockoutMs: Long = 0L, val attemptsBeforeWipe: Int? = null) : UnlockResult

    /** Слишком много неверных паролей подряд: пароль не проверялся, ждать [remainingMs]. */
    data class LockedOut(val remainingMs: Long) : UnlockResult
    data object NoAccount : UnlockResult
    /**
     * Введён duress-пароль (§7.4): вместо разблокировки выполнена тихая очистка,
     * состояние сброшено к [AccountState.None]. UI не должен ничем это выдавать.
     */
    data object DuressWiped : UnlockResult

    /** Исчерпан лимит неверных паролей при включённой очистке: данные уничтожены, как при duress. */
    data object WipedAfterFailedAttempts : UnlockResult
    data class Error(val reason: String) : UnlockResult
}

/** Результат смены логина / добавления пароля к уже существующему аккаунту. */
sealed interface AccountUpdateResult {
    data object Success : AccountUpdateResult
    data class Failure(val reason: String) : AccountUpdateResult
}

/**
 * Оркестратор жизненного цикла аккаунта (P1, §7.1). Соединяет нативную крипту
 * ([AccountCrypto]), запечатанное хранилище ([AccountStore]) и аппаратный
 * Keystore. Аккаунт = криптопара; кошелёк добавится позже без переработки (§7.1).
 *
 * Секрет (мнемоника) в открытом виде существует только в памяти после
 * разблокировки ([unlockedMnemonic]) и затирается при [lock]/[wipe]. Вместе с
 * ней открываются и закрываются хранилища переписки и PSK ([AccountBoundStorage]):
 * их ключ выводится из мнемоники, поэтому при закрытом аккаунте они нечитаемы.
 *
 * ИНВАРИАНТ: данные на устройстве (переписка, контакты, PSK, Signal-сессии,
 * заявки, настройки) принадлежат ровно одной личности. Часть хранилищ
 * (настройки, заявки на ник, реестр устройств) зашифрована только ключом
 * УСТРОЙСТВА (Keystore), поэтому новая личность прочла бы их без пароля.
 * Отсюда правило: новая личность ([createAccount], [recoverAccount] чужой
 * фразой) получает устройство только после полной очистки ([emergencyWipe]).
 */
class AccountManager(
    private val appContext: Context,
    private val accountStore: AccountStore,
    private val emergencyWipe: EmergencyWipe,
    private val keyStoreManager: KeyStoreManager,
    private val storage: AccountBoundStorage,
    private val unlockThrottle: UnlockThrottle,
    private val crypto: AccountCrypto = AccountCrypto,
) {
    private val secureRandom = SecureRandom()
    private val persistMutex = Mutex()

    private fun str(resId: Int): String = LocaleManager.wrap(appContext).getString(resId)

    /**
     * Ключ-обёртка мнемоники текущего (разблокированного) аккаунта, base64.
     * Нужен для включения биометрического входа (обернуть его биометрическим
     * ключом Keystore). `null`, пока заблокировано; затирается при [lock]/[wipe].
     */
    @Volatile
    private var currentWrappingKeyB64: String? = null

    private val _state = MutableStateFlow<AccountState>(AccountState.None)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /**
     * Мнемоника только что СОЗДАННОГО аккаунта — показывается пользователю ОДИН
     * раз для сохранения (§7.1). Пока не `null`, auth-gate держит экран восстановления
     * перед входом. Очищается через [acknowledgeRecovery].
     */
    private val _pendingRecovery = MutableStateFlow<String?>(null)
    val pendingRecovery: StateFlow<String?> = _pendingRecovery.asStateFlow()

    /** Мнемоника разблокированного аккаунта (в памяти). `null`, пока заблокировано. */
    @Volatile
    var unlockedMnemonic: String? = null
        private set

    /**
     * Вход по отпечатку был снят автоматически: ключ прежнего формата или набор
     * отпечатков на устройстве изменился. Экран входа объясняет это один раз.
     */
    private val _biometricReset = MutableStateFlow(false)
    val biometricReset: StateFlow<Boolean> = _biometricReset.asStateFlow()

    /**
     * Загружает состояние аккаунта при старте приложения (auth-gate).
     *
     * Идемпотентен по отношению к ЖИВОЙ сессии: если аккаунт уже разблокирован,
     * состояние не понижается до [AccountState.Locked]. Метод зовётся из
     * корневого composable, а тот пересоздаётся вместе с Activity — поворот
     * экрана или свайп приложения из списка недавних при живом процессе. Сброс
     * Unlocked→Locked в этот момент означал «аккаунт заблокировали»: приём
     * останавливался, а активный звонок обрывался (`AppContainer` по `Locked`
     * делает `callManager.hangUp()`) — ровно в момент нажатия «Принять».
     *
     * Исчезновение записи аккаунта — наоборот, повод состояние сбросить: так
     * выглядит внешнее стирание (§7.4, duress/экстренная очистка).
     */
    suspend fun initialize() {
        val record = accountStore.load()?.let { persistMutex.withLock { dropStaleBiometric(it) } }
        val current = _state.value
        if (current is AccountState.Unlocked && record != null) {
            DiagnosticLog.event(
                DIAG_TAG, "старт: сессия уже разблокирована, состояние сохранено",
                "accountId" to DiagnosticLog.shortId(record.profile.identity.accountId),
            )
            return
        }
        _state.value = if (record == null) {
            AccountState.None
        } else {
            AccountState.Locked(record.profile)
        }
        DiagnosticLog.event(
            DIAG_TAG, "старт",
            "состояние" to _state.value.label,
            "accountId" to DiagnosticLog.shortId(record?.profile?.identity?.accountId),
            "естьПароль" to (record?.profile?.hasPassword == true),
            "мультидевайс" to (record?.profile?.multiDeviceAllowed == true),
            "биометрия" to (record?.biometricWrappedKeyB64 != null),
        )
    }

    /**
     * Создаёт новый аккаунт. Для [AccountCreationMode.AUTOMATIC] пароль
     * игнорируется — защита хранения только аппаратным Keystore (§7.1, как Session).
     *
     * Новый аккаунт — новая личность, поэтому он ВСЕГДА создаётся на чистом
     * устройстве: перед записью выполняется полная очистка ([EmergencyWipe]),
     * в том числе остатков без записи аккаунта. Если на устройстве есть аккаунт,
     * без [replaceExisting] ничего не меняется и возвращается
     * [AccountCreationResult.ReplaceConfirmationRequired].
     */
    suspend fun createAccount(
        mode: AccountCreationMode,
        login: String,
        password: String,
        multiDeviceAllowed: Boolean = false,
        wordCount: Int = 24,
        replaceExisting: Boolean = false,
    ): AccountCreationResult = runCatchingResult {
        DiagnosticLog.event(
            DIAG_TAG, "создание аккаунта",
            "режим" to mode.name,
            "логин" to DiagnosticLog.presence(login),
            "слова" to wordCount,
            "мультидевайс" to multiDeviceAllowed,
            "заменаРазрешена" to replaceExisting,
        )
        val mnemonic = crypto.generateMnemonic(wordCount)
        persistNewAccount(
            mnemonic = mnemonic,
            mode = mode,
            login = login,
            password = password,
            multiDeviceAllowed = multiDeviceAllowed,
            showRecovery = true,
            localData = LocalDataPolicy.WIPE,
            replaceExisting = replaceExisting,
        )
    }

    /**
     * Восстанавливает аккаунт из мнемоники (§7.1). Логин можно указать сразу
     * или задать позже в настройках/профиле; пароль задаёт защиту на этом устройстве.
     *
     * Данные устройства сохраняются, если фраза принадлежит аккаунту, который на
     * нём и хранится («забыли пароль»), либо если аккаунта нет вовсе: перенос и
     * линковка ([AccountTransfer], [DeviceLinking]) раскладывают данные ДО этого
     * вызова. Фраза ДРУГОГО аккаунта требует [replaceExisting] и полной очистки
     * устройства, иначе возвращается [AccountCreationResult.ReplaceConfirmationRequired].
     */
    suspend fun recoverAccount(
        mnemonic: String,
        password: String,
        login: String = "",
        multiDeviceAllowed: Boolean = false,
        replaceExisting: Boolean = false,
    ): AccountCreationResult {
        val trimmed = mnemonic.trim().lowercase()
        if (!crypto.validateMnemonic(trimmed)) {
            DiagnosticLog.w(DIAG_TAG) { "восстановление: мнемоника не прошла проверку" }
            return AccountCreationResult.Failure(str(R.string.acct_err_bad_mnemonic))
        }
        DiagnosticLog.event(
            DIAG_TAG, "восстановление аккаунта",
            "логин" to DiagnosticLog.presence(login),
            "мультидевайс" to multiDeviceAllowed,
            "заменаРазрешена" to replaceExisting,
        )
        val mode = if (password.isEmpty()) AccountCreationMode.AUTOMATIC else AccountCreationMode.MANUAL
        return runCatchingResult {
            persistNewAccount(
                mnemonic = trimmed,
                mode = mode,
                login = login,
                password = password,
                multiDeviceAllowed = multiDeviceAllowed,
                showRecovery = false,
                localData = LocalDataPolicy.KEEP_FOR_SAME_ACCOUNT,
                replaceExisting = replaceExisting,
            )
        }
    }

    /**
     * Открывает хранилища ключом из [mnemonic] до появления записи аккаунта:
     * перенос и линковка раскладывают данные раньше, чем [recoverAccount]
     * переводит состояние в Unlocked. Аккаунта на устройстве быть не должно —
     * это проверяют вызывающие.
     */
    fun openStorageForImport(mnemonic: String) {
        storage.open(mnemonic.trim().lowercase())
    }

    /** Закрывает хранилища после сорвавшегося импорта, если аккаунт так и не появился. */
    fun closeStorageAfterFailedImport() {
        if (_state.value is AccountState.None) storage.close()
    }

    /** Подтвердить, что пользователь сохранил мнемонику — снять экран восстановления. */
    fun acknowledgeRecovery() {
        _pendingRecovery.value = null
    }

    /** Разблокирует существующий аккаунт паролем (или без — для авто-аккаунта). */
    suspend fun unlock(password: String): UnlockResult {
        val record = accountStore.load() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "вход: аккаунта нет" }
            return UnlockResult.NoAccount
        }

        // Во время задержки пароль не проверяется вовсе — и duress тоже: иначе
        // перебор шёл бы дальше под видом проверки пароля-обманки.
        if (record.profile.hasPassword) {
            val wait = unlockThrottle.remainingMs()
            if (wait > 0) {
                DiagnosticLog.w(DIAG_TAG) { "вход: задержка после неверных паролей, осталось ${wait / 1000} с" }
                return UnlockResult.LockedOut(wait)
            }
        }

        // Duress-пароль (§7.4/§8): если введён пароль-обманка — НЕ разблокируем, а
        // тихо запускаем crypto-shredding (ступень 1, мгновенно) и возвращаем UI к
        // онбордингу (пустой «приманочный» аккаунт). Проверяем ДО обычного входа.
        if (matchesDuress(record, password)) {
            silentWipe()
            DiagnosticLog.event(DIAG_TAG, "вход: аккаунт сброшен")
            return UnlockResult.DuressWiped
        }

        return try {
            val wrappingKeyB64 = resolveWrappingKey(record, password)
                ?: run {
                    DiagnosticLog.w(DIAG_TAG) { "вход: нет ключа-обёртки" }
                    return UnlockResult.Error(str(R.string.acct_err_no_unwrap_key))
                }
            val mnemonic = crypto.openMnemonic(record.sealedMnemonicB64, wrappingKeyB64)
                ?: run {
                    DiagnosticLog.w(DIAG_TAG) { "вход: неверный пароль" }
                    return registerWrongPassword(record)
                }
            storage.open(mnemonic)
            unlockThrottle.reset()
            unlockedMnemonic = mnemonic
            currentWrappingKeyB64 = wrappingKeyB64
            _state.value = AccountState.Unlocked(record.profile)
            DiagnosticLog.event(
                DIAG_TAG, "вход успешен",
                "accountId" to DiagnosticLog.shortId(record.profile.identity.accountId),
                "естьПароль" to record.profile.hasPassword,
            )
            UnlockResult.Success(record.profile)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "вход не удался" }
            UnlockResult.Error(e.message ?: str(R.string.acct_err_unlock))
        }
    }

    /**
     * Разблокировка по биометрии (§14.3): [decryptCipher] уже подтверждён через
     * `BiometricPrompt` и способен снять аппаратную обёртку с ключа-обёртки
     * мнемоники. Пароль вводить не требуется.
     */
    suspend fun unlockWithBiometric(decryptCipher: Cipher): UnlockResult {
        val record = accountStore.load() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "биометрия: аккаунта нет" }
            return UnlockResult.NoAccount
        }
        val wrapped = record.biometricWrappedKeyB64
            ?: run {
                DiagnosticLog.w(DIAG_TAG) { "биометрия: не настроена" }
                return UnlockResult.Error(str(R.string.acct_err_biometric_not_set))
            }
        return try {
            val wrappingKeyBytes = decryptCipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
            val wrappingKeyB64 = wrappingKeyBytes.decodeToString()
            val mnemonic = crypto.openMnemonic(record.sealedMnemonicB64, wrappingKeyB64)
                ?: run {
                    DiagnosticLog.w(DIAG_TAG) { "биометрия: ключ не подошёл" }
                    return UnlockResult.WrongPassword()
                }
            storage.open(mnemonic)
            unlockThrottle.reset()
            unlockedMnemonic = mnemonic
            currentWrappingKeyB64 = wrappingKeyB64
            _state.value = AccountState.Unlocked(record.profile)
            DiagnosticLog.event(
                DIAG_TAG, "вход по биометрии",
                "accountId" to DiagnosticLog.shortId(record.profile.identity.accountId),
            )
            UnlockResult.Success(record.profile)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "биометрия: вход не удался" }
            UnlockResult.Error(e.message ?: str(R.string.acct_err_biometric_unlock))
        }
    }

    /**
     * Блокирует аккаунт: затирает мнемонику в памяти, закрывает хранилища и
     * возвращает к экрану входа. Приём при закрытом аккаунте ключ не держит:
     * входящие копятся в очереди нерасшифрованными.
     *
     * Хранилища закрываются здесь, синхронно, а не по смене состояния: иначе
     * запоздалое закрытие могло бы прийтись на уже повторно открытый аккаунт.
     */
    fun lock() {
        forgetSecrets()
        storage.close()
        val profile = currentProfile()
        if (profile != null) _state.value = AccountState.Locked(profile)
        DiagnosticLog.event(
            DIAG_TAG, "блокировка",
            "accountId" to DiagnosticLog.shortId(profile?.identity?.accountId),
        )
    }

    /**
     * Полное удаление аккаунта = экстренная очистка (§7.4). [includeBestEffort]
     * дополнительно запускает ступень 2 (перезапись свободного места). Необратимо.
     *
     * Не отменяется: начатая очистка, прерванная уходом экрана (поворот, входящий
     * звонок), оставила бы устройство наполовину стёртым и в состоянии
     * «аккаунт открыт» без записи аккаунта на диске.
     */
    suspend fun wipe(includeBestEffort: Boolean = false): WipeReport = persistMutex.withLock {
        withContext(NonCancellable) {
            DiagnosticLog.event(DIAG_TAG, "очистка аккаунта", "ступень2" to includeBestEffort)
            val report = emergencyWipe.wipe(includeBestEffort = includeBestEffort)
            afterWipe()
            _state.value = AccountState.None
            DiagnosticLog.event(DIAG_TAG, "очистка завершена")
            report
        }
    }

    // --- Duress-пароль (§7.4/§8) ---

    /**
     * Настраивает пароль-обманку: при вводе на экране входа он запускает тихую
     * очистку вместо разблокировки. Доступно только для аккаунта с паролем и
     * только когда он разблокирован. Duress-пароль обязан отличаться от основного.
     */
    suspend fun setDuressPassword(duressPassword: String): Boolean {
        val record = accountStore.load() ?: return false
        if (!record.profile.hasPassword) return false
        if (duressPassword.length < AccountLogin.PASSWORD_MIN_CHARS) return false
        // Duress не должен совпадать с основным паролем (иначе вход невозможен).
        if (crypto.openMnemonic(record.sealedMnemonicB64, currentWrappingKeyB64 ?: return false) == null) {
            return false
        }
        val duressParams = AccountKdfParams(saltB64 = randomB64(SALT_LENGTH))
        val verifier = crypto.deriveWrappingKeyB64(duressPassword, duressParams)
        // Если verifier совпал бы с реальным ключом-обёрткой — это тот же пароль.
        if (constantTimeEquals(verifier, currentWrappingKeyB64)) return false
        accountStore.save(record.copy(duressKdfParams = duressParams, duressVerifierB64 = verifier))
        return true
    }

    /** Отключает duress-пароль. */
    suspend fun clearDuressPassword() {
        val record = accountStore.load() ?: return
        accountStore.save(record.copy(duressKdfParams = null, duressVerifierB64 = null))
    }

    /** Настроен ли duress-пароль. */
    suspend fun isDuressConfigured(): Boolean =
        accountStore.load()?.duressVerifierB64 != null

    // --- Ограничение попыток ввода пароля (§14.3) ---

    /** Сколько ждать до следующей попытки ввода пароля (0 — можно сейчас). */
    suspend fun unlockLockoutRemainingMs(): Long {
        val record = accountStore.load() ?: return 0L
        return if (record.profile.hasPassword) unlockThrottle.remainingMs() else 0L
    }

    /**
     * Включает очистку после [UnlockBackoff.WIPE_AFTER_FAILURES] неверных паролей
     * подряд. Только для аккаунта с паролем. `false`, если не применено.
     */
    suspend fun setWipeAfterFailedUnlocks(enabled: Boolean): Boolean = persistMutex.withLock {
        val record = accountStore.load() ?: return@withLock false
        if (!record.profile.hasPassword) return@withLock false
        if (record.wipeAfterFailedUnlocks != enabled) {
            accountStore.save(record.copy(wipeAfterFailedUnlocks = enabled))
            DiagnosticLog.event(DIAG_TAG, "очистка после неверных паролей", "включена" to enabled)
        }
        true
    }

    suspend fun isWipeAfterFailedUnlocksEnabled(): Boolean =
        accountStore.load()?.wipeAfterFailedUnlocks == true

    private suspend fun registerWrongPassword(record: SealedAccountRecord): UnlockResult {
        if (!record.profile.hasPassword) return UnlockResult.WrongPassword()
        val failures = unlockThrottle.recordFailure()
        if (record.wipeAfterFailedUnlocks && failures >= UnlockBackoff.WIPE_AFTER_FAILURES) {
            DiagnosticLog.event(DIAG_TAG, "вход: лимит неверных паролей — очистка", "неудач" to failures)
            silentWipe()
            return UnlockResult.WipedAfterFailedAttempts
        }
        return UnlockResult.WrongPassword(
            lockoutMs = UnlockBackoff.delayAfter(failures),
            attemptsBeforeWipe = if (record.wipeAfterFailedUnlocks) {
                UnlockBackoff.attemptsBeforeWipe(failures)
            } else {
                null
            },
        )
    }

    // --- Биометрический вход (§14.3) ---

    /**
     * Включает биометрический вход: оборачивает ключ-обёртку мнемоники аппаратным
     * биометрическим ключом Keystore. [encryptCipher] должен быть уже подтверждён
     * через `BiometricPrompt`. Доступно только для разблокированного аккаунта.
     */
    suspend fun enableBiometricUnlock(encryptCipher: Cipher): Boolean {
        val record = accountStore.load() ?: return false
        val wrappingKeyB64 = currentWrappingKeyB64 ?: return false
        return try {
            val ciphertext = encryptCipher.doFinal(wrappingKeyB64.encodeToByteArray())
            val ivB64 = Base64.encodeToString(encryptCipher.iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            accountStore.save(record.copy(biometricWrappedKeyB64 = ctB64, biometricIvB64 = ivB64))
            DiagnosticLog.event(DIAG_TAG, "биометрия включена")
            true
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "биометрия: не удалось включить" }
            false
        }
    }

    /** Отключает биометрический вход и снимает привязку ключа. */
    suspend fun disableBiometricUnlock() {
        val record = accountStore.load() ?: return
        accountStore.save(record.copy(biometricWrappedKeyB64 = null, biometricIvB64 = null))
        keyStoreManager.removeBiometricBinding()
        DiagnosticLog.event(DIAG_TAG, "биометрия выключена")
    }

    /** Настроен ли биометрический вход (есть обёрнутый ключ и активна привязка). */
    suspend fun isBiometricUnlockConfigured(): Boolean {
        val record = accountStore.load() ?: return false
        return record.biometricWrappedKeyB64 != null && keyStoreManager.isBiometricBindingActive()
    }

    /**
     * Зовётся, когда биометрический ключ не удалось открыть. Если ключ
     * аннулирован (на устройстве изменился набор отпечатков), снимает вход по
     * отпечатку и возвращает `true`.
     */
    suspend fun dropBiometricIfInvalidated(): Boolean = persistMutex.withLock {
        val record = accountStore.load() ?: return@withLock false
        dropStaleBiometric(record).biometricWrappedKeyB64 == null && record.biometricWrappedKeyB64 != null
    }

    /** Экран входа показал объяснение про снятый вход по отпечатку. */
    fun acknowledgeBiometricReset() {
        _biometricReset.value = false
    }

    /**
     * Снимает вход по отпечатку, ключ которого больше не годится: прежний формат
     * (принимал и PIN устройства, см. [KeyStoreManager.removeLegacyBiometricKey])
     * или аннулирован новым отпечатком. Вызывать под [persistMutex].
     */
    private suspend fun dropStaleBiometric(record: SealedAccountRecord): SealedAccountRecord {
        val legacyRemoved = keyStoreManager.removeLegacyBiometricKey()
        if (record.biometricWrappedKeyB64 == null) return record
        if (!legacyRemoved && keyStoreManager.isBiometricBindingActive()) return record
        keyStoreManager.removeBiometricBinding()
        val updated = record.copy(biometricWrappedKeyB64 = null, biometricIvB64 = null)
        accountStore.save(updated)
        _biometricReset.value = true
        DiagnosticLog.event(DIAG_TAG, "биометрия снята", "прежнийФормат" to legacyRemoved)
        return updated
    }

    /** IV (base64) для построения расшифровывающего Cipher биометрического входа. */
    suspend fun biometricIvB64(): String? = accountStore.load()?.biometricIvB64

    /**
     * Разрешает/запрещает использование аккаунта на нескольких устройствах
     * одновременно (§7.1.1). Флаг — клиентская политика (в zero-trust-модели
     * сервер её не навязывает §7.6): управляет предупреждениями и доступностью
     * переноса аккаунта (§7.1.2). Сохраняется в записи аккаунта и в профиле
     * текущего состояния. `false`, если аккаунта нет.
     */
    suspend fun setMultiDeviceAllowed(allowed: Boolean): Boolean = persistMutex.withLock {
        val record = accountStore.load() ?: return@withLock false
        if (record.profile.multiDeviceAllowed == allowed) return@withLock true
        val updated = record.copy(profile = record.profile.copy(multiDeviceAllowed = allowed))
        accountStore.save(updated)
        when (val s = _state.value) {
            is AccountState.Unlocked -> _state.value = AccountState.Unlocked(updated.profile)
            is AccountState.Locked -> _state.value = AccountState.Locked(updated.profile)
            AccountState.None -> {}
        }
        DiagnosticLog.event(DIAG_TAG, "мультидевайс", "разрешён" to allowed)
        true
    }

    /** Разрешён ли мультидевайс для текущего аккаунта (§7.1.1). */
    suspend fun isMultiDeviceAllowed(): Boolean =
        accountStore.load()?.profile?.multiDeviceAllowed ?: false

    /** Идентичность текущего (разблокированного) аккаунта, если доступна. */
    fun currentIdentity(): AccountIdentity? = currentProfile()?.identity

    /**
     * Публичный логин текущего аккаунта («ник» для показа собеседнику) или
     * пустая строка. Используется записью каталога и нагрузками рукопожатия
     * (`docs/contact_requests.md`): это НЕ идентификатор и ничего не
     * аутентифицирует — доверенным остаётся `accountId`.
     */
    fun currentLogin(): String = currentProfile()?.login.orEmpty()

    /**
     * Разрешён ли мультидевайс для текущего профиля прямо сейчас (§7.1.1),
     * синхронно (для enforcement в self-sync/fan-out без обращения к диску).
     */
    fun multiDeviceAllowedNow(): Boolean = currentProfile()?.multiDeviceAllowed ?: false

    /**
     * Задаёт или меняет публичный логин разблокированного аккаунта (§14.1).
     * Идентичность (accountId / ключи) не меняется. Уже обменявшиеся контакты
     * продолжают видеть старую метку из QR, пока не проведут обмен заново.
     */
    suspend fun setLogin(login: String): AccountUpdateResult = persistMutex.withLock {
        if (_state.value !is AccountState.Unlocked) {
            return AccountUpdateResult.Failure(str(R.string.acct_err_not_unlocked))
        }
        val normalized = AccountLogin.normalize(login)
        AccountLogin.validate(normalized)?.let {
            return AccountUpdateResult.Failure(loginErrorMessage(it))
        }
        val record = accountStore.load()
            ?: return AccountUpdateResult.Failure(str(R.string.acct_err_not_unlocked))
        if (record.profile.login == normalized) return AccountUpdateResult.Success
        val updated = record.copy(profile = record.profile.copy(login = normalized))
        accountStore.save(updated)
        publishUnlocked(updated.profile)
        DiagnosticLog.event(DIAG_TAG, "логин обновлён", "логин" to DiagnosticLog.presence(normalized))
        AccountUpdateResult.Success
    }

    /**
     * Добавляет логин и пароль входа к авто-аккаунту без пароля (§7.1 / §14.3).
     *
     * Мнемоника перезапечатывается ключом Argon2id(пароль); аппаратный auto-unlock
     * ключ уничтожается. После этого вход требует пароль, и в настройках
     * становятся доступны биометрия и duress. Вызывать только в разблокированном
     * состоянии (ключ-обёртка уже в памяти).
     */
    suspend fun addLoginAndPassword(login: String, password: String): AccountUpdateResult =
        persistMutex.withLock {
            if (_state.value !is AccountState.Unlocked) {
                return AccountUpdateResult.Failure(str(R.string.acct_err_not_unlocked))
            }
            val normalized = AccountLogin.normalize(login)
            AccountLogin.validate(normalized)?.let {
                return AccountUpdateResult.Failure(loginErrorMessage(it))
            }
            if (password.length < AccountLogin.PASSWORD_MIN_CHARS) {
                return AccountUpdateResult.Failure(str(R.string.account_password_too_short))
            }
            val record = accountStore.load()
                ?: return AccountUpdateResult.Failure(str(R.string.acct_err_not_unlocked))
            if (record.profile.hasPassword) {
                return AccountUpdateResult.Failure(str(R.string.acct_err_already_has_password))
            }
            val mnemonic = unlockedMnemonic
                ?: return AccountUpdateResult.Failure(str(R.string.acct_err_not_unlocked))
            val currentKey = currentWrappingKeyB64
                ?: return AccountUpdateResult.Failure(str(R.string.acct_err_not_unlocked))
            return try {
                withContext(Dispatchers.IO) {
                    val opened = crypto.openMnemonic(record.sealedMnemonicB64, currentKey)
                    if (opened == null || opened != mnemonic) {
                        return@withContext AccountUpdateResult.Failure(str(R.string.acct_err_add_password))
                    }
                    val kdfParams = AccountKdfParams(saltB64 = randomB64(SALT_LENGTH))
                    val wrappingKeyB64 = crypto.deriveWrappingKeyB64(password, kdfParams)
                    val sealed = crypto.sealMnemonic(mnemonic, wrappingKeyB64)
                    val profile = record.profile.copy(
                        login = normalized,
                        creationMode = AccountCreationMode.MANUAL,
                        hasPassword = true,
                    )
                    accountStore.save(
                        record.copy(
                            profile = profile,
                            kdfParams = kdfParams,
                            sealedMnemonicB64 = sealed,
                            autoUnlockKeyB64 = null,
                            biometricWrappedKeyB64 = null,
                            biometricIvB64 = null,
                        ),
                    )
                    currentWrappingKeyB64 = wrappingKeyB64
                    publishUnlocked(profile)
                    DiagnosticLog.event(
                        DIAG_TAG, "добавлены логин и пароль",
                        "логин" to DiagnosticLog.presence(normalized),
                        "accountId" to DiagnosticLog.shortId(profile.identity.accountId),
                    )
                    AccountUpdateResult.Success
                }
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) { "не удалось добавить логин/пароль" }
                AccountUpdateResult.Failure(e.message ?: str(R.string.acct_err_add_password))
            }
        }

    private fun currentProfile(): AccountProfile? = when (val s = _state.value) {
        is AccountState.Locked -> s.profile
        is AccountState.Unlocked -> s.profile
        AccountState.None -> null
    }

    private fun publishUnlocked(profile: AccountProfile) {
        _state.value = AccountState.Unlocked(profile)
    }

    // --- Внутреннее ---

    /** Судьба локальных данных при записи нового аккаунта (см. инвариант класса). */
    private enum class LocalDataPolicy {
        /** Новая личность: устройство очищается всегда. */
        WIPE,

        /** Восстановление: данные остаются, если хранимый аккаунт — тот же (или его нет). */
        KEEP_FOR_SAME_ACCOUNT,
    }

    private suspend fun persistNewAccount(
        mnemonic: String,
        mode: AccountCreationMode,
        login: String,
        password: String,
        multiDeviceAllowed: Boolean,
        showRecovery: Boolean,
        localData: LocalDataPolicy,
        replaceExisting: Boolean,
    ): AccountCreationResult = persistMutex.withLock {
        val normalizedLogin = AccountLogin.normalize(login)
        AccountLogin.validateForPersist(normalizedLogin, allowEmpty = true)?.let {
            return@withLock AccountCreationResult.Failure(loginErrorMessage(it))
        }
        if (mode == AccountCreationMode.MANUAL && password.length < AccountLogin.PASSWORD_MIN_CHARS) {
            return@withLock AccountCreationResult.Failure(str(R.string.account_password_too_short))
        }
        // Не отменяется: экран онбординга уходит из композиции при повороте или
        // входящем звонке, и отмена между очисткой и записью оставила бы
        // устройство без данных прежнего аккаунта и без нового.
        withContext(NonCancellable + Dispatchers.IO) {
            val identity = crypto.deriveIdentity(mnemonic)
            val stored = accountStore.load()
            val replacesStored = stored != null && (
                localData == LocalDataPolicy.WIPE ||
                    stored.profile.identity.accountId != identity.accountId
                )
            val wipeLocalData = localData == LocalDataPolicy.WIPE || replacesStored
            // Открытый аккаунт стирать нельзя: его фоновые циклы и экраны живут
            // на секретах, которые очистка уничтожит, а перезапускаются они только
            // на переходе в Unlocked. UI сюда не ведёт — это страховка контракта.
            if (wipeLocalData && _state.value is AccountState.Unlocked) {
                return@withContext AccountCreationResult.Failure(str(R.string.acct_err_replace_unlocked))
            }
            if (replacesStored && !replaceExisting) {
                DiagnosticLog.event(
                    DIAG_TAG, "замена аккаунта требует подтверждения",
                    "хранимый" to DiagnosticLog.shortId(stored?.profile?.identity?.accountId),
                    "новый" to DiagnosticLog.shortId(identity.accountId),
                )
                return@withContext AccountCreationResult.ReplaceConfirmationRequired
            }

            // Всё, что может упасть (Argon2id, запечатывание), — ДО очистки: сбой
            // здесь оставляет прежний аккаунт нетронутым.
            val hasPassword = mode == AccountCreationMode.MANUAL && password.isNotEmpty()

            val kdfParams = AccountKdfParams(saltB64 = randomB64(SALT_LENGTH))
            val (wrappingKeyB64, autoUnlockKeyB64) = if (hasPassword) {
                crypto.deriveWrappingKeyB64(password, kdfParams) to null
            } else {
                val autoKey = randomB64(KEY_LENGTH)
                autoKey to autoKey
            }

            val sealed = crypto.sealMnemonic(mnemonic, wrappingKeyB64)
            val profile = AccountProfile(
                identity = identity,
                login = normalizedLogin,
                creationMode = mode,
                hasPassword = hasPassword,
                multiDeviceAllowed = multiDeviceAllowed,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            val record = SealedAccountRecord(
                profile = profile,
                kdfParams = kdfParams,
                sealedMnemonicB64 = sealed,
                autoUnlockKeyB64 = autoUnlockKeyB64,
            )

            if (wipeLocalData) {
                DiagnosticLog.event(
                    DIAG_TAG, "очистка устройства перед новым аккаунтом",
                    "былАккаунт" to (stored != null),
                )
                emergencyWipe.wipe(includeBestEffort = false)
                afterWipe()
            }
            try {
                storage.open(mnemonic)
                accountStore.save(record)
            } catch (e: Exception) {
                // Прежний аккаунт уже стёрт: честное состояние — «аккаунта нет»,
                // а не экран входа в аккаунт, которого на диске больше не существует.
                if (wipeLocalData) _state.value = AccountState.None
                throw e
            }
            unlockThrottle.reset()

            unlockedMnemonic = mnemonic
            currentWrappingKeyB64 = wrappingKeyB64
            _pendingRecovery.value = if (showRecovery) mnemonic else null
            _state.value = AccountState.Unlocked(profile)
            DiagnosticLog.event(
                DIAG_TAG, "аккаунт сохранён",
                "accountId" to DiagnosticLog.shortId(identity.accountId),
                "отпечаток" to DiagnosticLog.fp(identity.fingerprint),
                "режим" to mode.name,
                "естьПароль" to hasPassword,
                "мультидевайс" to multiDeviceAllowed,
                "логин" to DiagnosticLog.presence(normalizedLogin),
            )
            AccountCreationResult.Success(profile, mnemonic)
        }
    }

    private fun loginErrorMessage(error: AccountLogin.Error): String = str(
        when (error) {
            AccountLogin.Error.Empty -> R.string.account_login_empty
            AccountLogin.Error.TooShort -> R.string.account_login_too_short
            AccountLogin.Error.TooLong -> R.string.account_login_too_long
            AccountLogin.Error.InvalidChars -> R.string.account_login_invalid
            AccountLogin.Error.LooksLikeEmail -> R.string.account_login_looks_email
            AccountLogin.Error.LooksLikePhone -> R.string.account_login_looks_phone
        },
    )

    /** Проверяет, совпадает ли введённый пароль с настроенным duress-паролем. */
    private fun matchesDuress(record: SealedAccountRecord, password: String): Boolean {
        val params = record.duressKdfParams ?: return false
        val expected = record.duressVerifierB64 ?: return false
        return runCatching {
            val candidate = crypto.deriveWrappingKeyB64(password, params)
            constantTimeEquals(candidate, expected)
        }.getOrDefault(false)
    }

    /**
     * Тихая очистка по duress или по лимиту неверных паролей (§7.4). Ступень 1
     * (crypto-shredding) — мгновенная и является главной гарантией; ступень 2 не
     * запускаем, чтобы очистка была незаметной и не подвисала на глазах у
     * принуждающего. Состояние → None.
     */
    private suspend fun silentWipe() = persistMutex.withLock {
        withContext(NonCancellable) {
            runCatching { emergencyWipe.wipe(includeBestEffort = false) }
            afterWipe()
            _state.value = AccountState.None
        }
    }

    /** После очистки: секреты и ключ хранилищ — из памяти, счётчик неверных паролей — в ноль. */
    private suspend fun afterWipe() {
        forgetSecrets()
        storage.close()
        unlockThrottle.reset()
    }

    /** Затирает секреты разблокированного аккаунта в памяти (мнемоника, ключ-обёртка). */
    private fun forgetSecrets() {
        unlockedMnemonic = null
        currentWrappingKeyB64 = null
        _pendingRecovery.value = null
    }

    private fun constantTimeEquals(a: String, b: String?): Boolean {
        if (b == null) return false
        return MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
    }

    private fun resolveWrappingKey(record: SealedAccountRecord, password: String): String? =
        if (record.profile.hasPassword) {
            crypto.deriveWrappingKeyB64(password, record.kdfParams)
        } else {
            record.autoUnlockKeyB64
        }

    private fun randomB64(lengthBytes: Int): String {
        val bytes = ByteArray(lengthBytes)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private inline fun runCatchingResult(block: () -> AccountCreationResult): AccountCreationResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "создание/восстановление аккаунта упало" }
            AccountCreationResult.Failure(e.message ?: str(R.string.acct_err_create))
        }

    private companion object {
        const val DIAG_TAG = "account"
        const val SALT_LENGTH = 16
        const val KEY_LENGTH = 32
    }
}
