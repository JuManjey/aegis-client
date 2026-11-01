package com.aegis.core.common.security

import com.aegis.core.common.AegisConstants
import kotlinx.serialization.Serializable

/**
 * Полный набор управляемых параметров безопасности (§8, §10.13, §14).
 *
 * Инварианты (жёсткие правила ТЗ), которые НЕЛЬЗЯ ослабить настройками:
 *  - [uiFlagSecure] всегда `true` (§14.3: запрет скриншотов включён принудительно
 *    и глобально, отключить нельзя). Поле оставлено только для полноты модели.
 *  - [autoDeleteDays] всегда в диапазоне [AUTO_DELETE_MIN_DAYS..AUTO_DELETE_MAX_DAYS];
 *    больше 7 дней задать нельзя, отключить нельзя (§7.3).
 *
 * Значения нормализуются в [init], поэтому невозможно сконструировать
 * объект, нарушающий инварианты.
 *
 * Маршрут медиа звонков отдельной настройкой больше не задаётся: он выводится
 * из [netTor] ([torEnabled]). Прежний ключ `callsMediaOverTor` в сохранённом
 * JSON игнорируется (все места разбора — с `ignoreUnknownKeys`).
 */
@Serializable
data class SecuritySettings(
    val netTor: TorMode = TorMode.ALWAYS,
    /**
     * Выключение Tor подтверждено в диалоге с предупреждением. Нужно, чтобы
     * отличить осознанный [TorMode.OFF] от унаследованного: раньше OFF ставился
     * радиокнопкой без предупреждения о раскрытии IP (и вдобавок ломал сеть).
     * Такой OFF при чтении возвращается к Tor ВКЛ ([normalized]). Меняется
     * только вместе с режимом ([withTorMode]).
     */
    val torOffConfirmed: Boolean = false,
    /** Передача ключа контейнера (PSK) по каналу Signal. По умолчанию ВЫКЛ (только вручную). */
    val keysOverSignal: Boolean = false,
    /** Обязательная ручная верификация отпечатка перед началом чата. */
    val keysRequireVerify: Boolean = true,
    /**
     * Удалённое добавление контактов (`docs/contact_requests.md`). Отдельная ось
     * от [keysOverSignal]: там секрет слоя A ПЕРЕДАЁТСЯ по сети, здесь —
     * ВЫВОДИТСЯ из подписанного рукопожатия, поэтому [ContactRequestMode.ADDRESS]
     * допустим даже в «Параноике» (доверие к адресу = доверие к QR).
     */
    val contactRequests: ContactRequestMode = ContactRequestMode.ADDRESS,
    /**
     * Отправлять ли собеседнику явный отказ при отклонении заявки. По умолчанию
     * ВКЛ: иначе инициатор не отличит «отказали» от «не дошло» и будет повторять.
     * Выключение чуть строже к метаданным (собеседник не узнаёт даже факт
     * решения), но ухудшает предсказуемость для него.
     */
    val contactDeclineNotify: Boolean = true,
    /** Каскад шифров XChaCha20→AES→Serpent→Twofish для контейнера (слой A). */
    val cryptoCascade: Boolean = true,
    /** Срок хранения переписки в днях (жёсткий потолок — 7, §7.3). */
    val autoDeleteDays: Int = AegisConstants.AUTO_DELETE_DEFAULT_DAYS,
    val callsEnabled: Boolean = true,
    /**
     * Автозагрузка входящих вложений (§14.4). По умолчанию ВЫКЛ («осторожно»):
     * через Tor это медленно, поэтому крупные блобы качаем только по явному действию.
     * При включении автоматически качаются лишь вложения не крупнее
     * [AegisConstants.ATTACHMENT_AUTO_DOWNLOAD_MAX_BYTES].
     */
    val autoDownloadMedia: Boolean = false,
    /** Хранение ключей в аппаратном StrongBox/TEE. */
    val storeHwKeystore: Boolean = true,
    /** Разблокировка приложения биометрией/PIN. */
    val storeBiometricLock: Boolean = true,
    /**
     * Таймаут автоблокировки в минутах (§14.3). [AegisConstants.AUTO_LOCK_OFF] (0)
     * отключает автоблокировку (тогда блокировка только ручная/по duress).
     */
    val autoLockMinutes: Int = AegisConstants.AUTO_LOCK_DEFAULT_MINUTES,
    /**
     * Продолжать принимать сообщения и звонки, когда аккаунт закрыт (автоблокировкой
     * или вручную).
     *
     * ЗАЧЕМ ЭТА НАСТРОЙКА СУЩЕСТВУЕТ. Приём держится на long-poll (через Tor или,
     * в режиме без Tor, по HTTPS), а не на push (§7.6). При блокировке приём останавливался целиком: сервис
     * гасился, замок процессора отпускался, цепи Tor отмирали. Снаружи это и
     * выглядело как «приложение засыпает»: телефон полежал заблокированным — и
     * дозвониться было нельзя до следующего входа.
     *
     * КАК РАБОТАЕТ. Ключа хранилищ при закрытом аккаунте нет, поэтому входящие не
     * расшифровываются: конверты складываются в очередь как есть и разбираются
     * после входа. Уведомления — без отправителя и текста; звонок звонит без
     * имени, ответить можно после входа.
     *
     * ЧТО МЕНЯЕТСЯ В МОДЕЛИ УГРОЗ, честно. На устройстве (под ключом Keystore)
     * лежат адреса моих ящиков на релее и очередь шифртекста: снявший обёртку
     * Keystore видит, когда приходят конверты, и может забрать их с релея раньше
     * нас — но не прочитать. Работает foreground-сервис, Tor не засыпает.
     *
     * По умолчанию ВЫКЛ: такие вещи включает пользователь, а не обновление.
     * Не путать с [autoLockMinutes] = 0: там не блокируется и интерфейс.
     */
    val deliverWhileLocked: Boolean = false,
    /** FLAG_SECURE. Всегда true — принудительно и глобально (§14.3). */
    val uiFlagSecure: Boolean = true,
    val uiBlockRooted: RootedDevicePolicy = RootedDevicePolicy.BLOCK,
    /** Пароль-обманка (duress), запускающий экстренную очистку вместо входа. */
    val wipeDuress: Boolean = true,
    val metaMinimize: MetadataMinimization = MetadataMinimization.MAXIMUM,
    /**
     * Смягчение тайминг-корреляции fan-out (§14.7, `docs/multidevice.md` §12/§1.40).
     * [FanoutJitterMode.AUTO] (по умолчанию) — следовать [metaMinimize]; иначе явный
     * уровень независимо от пресета.
     */
    val fanoutJitter: FanoutJitterMode = FanoutJitterMode.AUTO,
    /**
     * Прикрытие факта/объёма отправки cover-traffic'ом (§14.7, `docs/multidevice.md`
     * §12/§1.41). [CoverTrafficMode.AUTO] (по умолчанию) — включён только при
     * `meta.minimize=MAXIMUM`; иначе явное вкл/выкл независимо от пресета.
     */
    val coverTraffic: CoverTrafficMode = CoverTrafficMode.AUTO,
) {
    init {
        require(autoDeleteDays in AegisConstants.AUTO_DELETE_MIN_DAYS..AegisConstants.AUTO_DELETE_MAX_DAYS) {
            "autoDeleteDays должен быть в диапазоне " +
                "${AegisConstants.AUTO_DELETE_MIN_DAYS}..${AegisConstants.AUTO_DELETE_MAX_DAYS}, получено $autoDeleteDays"
        }
        require(autoLockMinutes in AegisConstants.AUTO_LOCK_OFF..AegisConstants.AUTO_LOCK_MAX_MINUTES) {
            "autoLockMinutes должен быть в диапазоне " +
                "${AegisConstants.AUTO_LOCK_OFF}..${AegisConstants.AUTO_LOCK_MAX_MINUTES}, получено $autoLockMinutes"
        }
    }

    /**
     * Идёт ли трафик через Tor. Отсюда же выводится маршрут медиа звонков:
     * Tor ВКЛ — только relay через onion-TURN, Tor ВЫКЛ — напрямую.
     */
    val torEnabled: Boolean
        get() = netTor == TorMode.ALWAYS

    /** Смена режима транспорта — только вместе с отметкой подтверждения выключения. */
    fun withTorMode(mode: TorMode): SecuritySettings =
        copy(netTor = mode, torOffConfirmed = mode == TorMode.OFF)

    /**
     * Приводит прочитанные настройки к согласованному виду: неподтверждённый
     * OFF (унаследован от версии без диалога-предупреждения) — Tor ВКЛ, отметка
     * подтверждения без OFF сбрасывается. Вызывается при каждом чтении из хранилища.
     */
    fun normalized(): SecuritySettings = when {
        netTor == TorMode.OFF && !torOffConfirmed -> copy(netTor = TorMode.ALWAYS)
        netTor == TorMode.ALWAYS && torOffConfirmed -> copy(torOffConfirmed = false)
        else -> this
    }

    /**
     * Определяет, какому пресету соответствует текущий набор значений.
     * Если ни одному из «канонических» пресетов — возвращает [SecurityPreset.CUSTOM].
     */
    fun matchingPreset(): SecurityPreset = when (this) {
        forPreset(SecurityPreset.PARANOID) -> SecurityPreset.PARANOID
        forPreset(SecurityPreset.BALANCED) -> SecurityPreset.BALANCED
        forPreset(SecurityPreset.FAST) -> SecurityPreset.FAST
        else -> SecurityPreset.CUSTOM
    }

    /**
     * Эффективный уровень джиттера fan-out (§14.7, `docs/multidevice.md` §12/§1.40).
     * [FanoutJitterMode.AUTO] следует [metaMinimize], остальные — явный уровень.
     */
    fun effectiveFanoutJitter(): MetadataMinimization = when (fanoutJitter) {
        FanoutJitterMode.AUTO -> metaMinimize
        FanoutJitterMode.OFF -> MetadataMinimization.BASIC
        FanoutJitterMode.MODERATE -> MetadataMinimization.MODERATE
        FanoutJitterMode.MAXIMUM -> MetadataMinimization.MAXIMUM
    }

    /**
     * Включён ли cover-traffic (§14.7, `docs/multidevice.md` §12/§1.41).
     * [CoverTrafficMode.AUTO] — только при `meta.minimize=MAXIMUM`; [CoverTrafficMode.ON]/
     * [CoverTrafficMode.OFF] — явно.
     */
    fun coverTrafficActive(): Boolean = when (coverTraffic) {
        CoverTrafficMode.AUTO -> metaMinimize == MetadataMinimization.MAXIMUM
        CoverTrafficMode.ON -> true
        CoverTrafficMode.OFF -> false
    }

    companion object {
        /**
         * Канонические значения для пресета (§8). FLAG_SECURE везде true —
         * это инвариант, а не различие между пресетами.
         *
         * ВАЖНО: по техническому заданию (§8) `autoDeleteDays` для ВСЕХ пресетов = 7
         * (жёсткий максимум). Более ранняя таблица техплана (30/0) переопределена
         * разделом §8 ТЗ — источником истины.
         *
         * Tor: «Параноик» и «Баланс» — ВКЛ (в «Балансе» звонки теперь тоже идут
         * через Tor), «Быстро» — ВЫКЛ.
         */
        fun forPreset(preset: SecurityPreset): SecuritySettings = when (preset) {
            SecurityPreset.PARANOID -> SecuritySettings(
                netTor = TorMode.ALWAYS,
                keysOverSignal = false,
                keysRequireVerify = true,
                // Добавление по адресу разрешено: секрет слоя A не передаётся по
                // сети, а выводится из подписанного рукопожатия. Публичный ник —
                // нет: он делает пользователя перечислимым (см. ContactRequestMode).
                contactRequests = ContactRequestMode.ADDRESS,
                contactDeclineNotify = true,
                cryptoCascade = true,
                autoDeleteDays = AegisConstants.AUTO_DELETE_MAX_DAYS,
                callsEnabled = true,
                autoDownloadMedia = false,
                storeHwKeystore = true,
                storeBiometricLock = true,
                autoLockMinutes = AegisConstants.AUTO_LOCK_DEFAULT_MINUTES,
                uiFlagSecure = true,
                uiBlockRooted = RootedDevicePolicy.BLOCK,
                wipeDuress = true,
                metaMinimize = MetadataMinimization.MAXIMUM,
            )

            SecurityPreset.BALANCED -> SecuritySettings(
                netTor = TorMode.ALWAYS,
                keysOverSignal = true,
                keysRequireVerify = true,
                contactRequests = ContactRequestMode.ADDRESS_AND_ALIAS,
                contactDeclineNotify = true,
                cryptoCascade = false,
                autoDeleteDays = AegisConstants.AUTO_DELETE_MAX_DAYS,
                callsEnabled = true,
                autoDownloadMedia = false,
                storeHwKeystore = true,
                storeBiometricLock = true,
                autoLockMinutes = 5,
                uiFlagSecure = true,
                uiBlockRooted = RootedDevicePolicy.WARN,
                wipeDuress = false,
                metaMinimize = MetadataMinimization.MODERATE,
            )

            SecurityPreset.FAST -> SecuritySettings(
                netTor = TorMode.OFF,
                // Пресет выбирается явным действием; выбор обязан пройти тот же
                // диалог-предупреждение, что и переключатель Tor.
                torOffConfirmed = true,
                keysOverSignal = true,
                keysRequireVerify = false,
                contactRequests = ContactRequestMode.ADDRESS_AND_ALIAS,
                contactDeclineNotify = true,
                cryptoCascade = false,
                autoDeleteDays = AegisConstants.AUTO_DELETE_MAX_DAYS,
                callsEnabled = true,
                autoDownloadMedia = true,
                storeHwKeystore = true,
                storeBiometricLock = false,
                autoLockMinutes = AegisConstants.AUTO_LOCK_OFF,
                uiFlagSecure = true,
                uiBlockRooted = RootedDevicePolicy.OFF,
                wipeDuress = false,
                metaMinimize = MetadataMinimization.BASIC,
            )

            // CUSTOM не имеет канонических значений — стартуем от Параноика.
            SecurityPreset.CUSTOM -> forPreset(SecurityPreset.PARANOID)
        }

        /** Значения по умолчанию для нового аккаунта — пресет «Параноик». */
        val DEFAULT: SecuritySettings = forPreset(SecurityPreset.DEFAULT)
    }
}
