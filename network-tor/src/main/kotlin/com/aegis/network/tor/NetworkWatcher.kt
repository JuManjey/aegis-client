package com.aegis.network.tor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.aegis.core.common.diag.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Наблюдатель сетевых переключений устройства.
 *
 * Зачем он нужен именно здесь. Встроенный Arti строит цепи поверх обычных
 * TCP-соединений и НЕ получает от Android никаких событий о смене сети. Когда
 * телефон уходит с Wi-Fi на мобильный интернет (или поднимается/падает VPN),
 * старые сокеты становятся «чёрными дырами»: они не закрыты, поэтому клиент
 * считает себя подключённым, а любой запрос молча висит до таймаута. Именно так
 * выглядит «Tor уснул»: индикатор зелёный, а сообщения не ходят.
 *
 * Здесь мы подписываемся на [ConnectivityManager] и превращаем поток системных
 * колбэков в ОДНО монотонно растущее число — [epoch]. Слушателю ([TorSupervisor])
 * не важны детали: важно, что «сеть под нами стала другой, соединения надо
 * пересобрать».
 *
 * Что НЕ делаем и почему:
 *  * не различаем Wi-Fi/мобильный/Ethernet в самом эпохе — маршрут выбирает ОС,
 *    а мы всё равно реагируем одинаково (пересобрать соединения);
 *  * не храним и не пишем в журнал ни SSID, ни IP-адреса, ни имена интерфейсов
 *    (§10.10, метаданные сети): в журнал уходит только тип транспорта и флаг
 *    валидности.
 *
 * Регистрация колбэка требует `ACCESS_NETWORK_STATE` (уже объявлено в манифесте).
 */
class NetworkWatcher(context: Context) {

    private val appContext = context.applicationContext

    private val connectivity: ConnectivityManager? =
        runCatching {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

    private val _epoch = MutableStateFlow(NetworkEpoch.UNKNOWN)

    /**
     * Текущее «поколение» сети. Меняется на каждое реальное переключение
     * доступного маршрута; сравнивать значения между собой смысла нет — только
     * замечать факт изменения.
     */
    val epoch: StateFlow<NetworkEpoch> = _epoch.asStateFlow()

    /** Есть ли сейчас хоть одна сеть с подтверждённым доступом в интернет. */
    val online: Boolean
        get() = _epoch.value.online

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Счётчик поколений. Атомарный, потому что [bump] зовут и системные колбэки
     * ConnectivityManager, и [start] с потока вызывающего — обычный `Long`
     * потерял бы инкремент, а на равенстве поколений держится решение сторожа
     * «пересобирать соединения или нет».
     */
    private val counter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Отпечаток параметров интерфейса последнего события. Сравнение по нему
     * отсекает шум: `onLinkPropertiesChanged` приходит и на смену DNS-серверов,
     * которая соединениям Tor безразлична.
     */
    @Volatile
    private var lastLinkFingerprint: String? = null

    /** Подписывается на события сети. Идемпотентно. */
    @Synchronized
    fun start() {
        if (callback != null) return
        val cm = connectivity ?: run {
            DiagnosticLog.w(DIAG_TAG) { "ConnectivityManager недоступен — переключения сети не отслеживаются" }
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bump("доступна", cm)

            /**
             * Сеть пропала.
             *
             * Состояние ВСЕГДА пересчитывается по АКТИВНОЙ сети, а не по той,
             * которая исчезла. Это была настоящая ошибка предыдущей версии: при
             * переходе Wi-Fi → мобильный система присылает `onAvailable(мобильный)`
             * и следом `onLost(Wi-Fi)`, и если по второму событию записать
             * «офлайн», сторож ([TorSupervisor]) на своей проверке `!online`
             * выходил досрочно и НЕ лечил соединение — ровно тот сценарий,
             * который нужно было починить в первую очередь.
             */
            override fun onLost(network: Network) = bump("потеряна", cm)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Реагируем только на появление/исчезновение подтверждённого
                // интернета. Без этого фильтра колбэк срабатывает на каждое
                // изменение пропускной способности — на мобильной сети это
                // десятки событий в минуту, и Tor перезапускался бы бесконечно.
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated != _epoch.value.online) bump("проверена", cm)
            }

            /**
             * Сменились параметры интерфейса при ТОЙ ЖЕ сети: новый IP, поднялся
             * или упал VPN, сменился DNS. Соединения Tor привязаны к адресу
             * источника, поэтому для них это равносильно смене сети, хотя ни
             * `onAvailable`, ни `onLost` при этом не приходят.
             */
            override fun onLinkPropertiesChanged(network: Network, props: android.net.LinkProperties) {
                val fingerprint = linkFingerprint(props)
                if (fingerprint == lastLinkFingerprint) return
                lastLinkFingerprint = fingerprint
                bump("интерфейс", cm)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val ok = runCatching { cm.registerNetworkCallback(request, cb) }.isSuccess
        if (!ok) {
            DiagnosticLog.w(DIAG_TAG) { "не удалось подписаться на события сети" }
            return
        }
        callback = cb
        // Первичный снимок: колбэк onAvailable для УЖЕ активной сети приходит
        // сразу после регистрации не на всех прошивках.
        bump("подписка", cm)
        DiagnosticLog.event(DIAG_TAG, "наблюдение за сетью запущено")
    }

    /**
     * Пересчитывает состояние по требованию (без события от системы).
     *
     * Нужен сторожу как «вторая опора»: колбэки ConnectivityManager при выходе из
     * долгого Doze приходят не всегда, и полагаться только на них означало бы
     * зависеть от прошивки. Поколение при этом НЕ увеличивается, если ничего не
     * изменилось, — иначе периодический опрос сам выглядел бы как бесконечная
     * череда переключений сети и заставлял Tor перезапускаться в цикле.
     */
    fun refresh() {
        val cm = connectivity ?: return
        val current = evaluate(cm)
        val previous = _epoch.value
        if (current.first == previous.online && current.second == previous.transport) return
        bump("опрос", cm)
    }

    /** Снимает подписку. Идемпотентно. */
    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
        DiagnosticLog.event(DIAG_TAG, "наблюдение за сетью остановлено")
    }

    /**
     * Текущее состояние связи по АКТИВНОЙ сети: (есть ли подтверждённый интернет,
     * тип транспорта).
     *
     * Источник истины — только `activeNetwork`: именно её ОС выбрала маршрутом по
     * умолчанию, и именно через неё пойдут соединения Tor. Судить по сети из
     * колбэка нельзя — колбэк может рассказывать про уже отмершую Wi-Fi, пока
     * трафик давно идёт через мобильную.
     */
    private fun evaluate(cm: ConnectivityManager): Pair<Boolean, String> {
        val active = runCatching { cm.activeNetwork }.getOrNull()
        val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return online to transportOf(caps)
    }

    private fun bump(reason: String, cm: ConnectivityManager) {
        val (online, transport) = evaluate(cm)
        val generation = counter.incrementAndGet()
        _epoch.value = NetworkEpoch(
            id = generation,
            online = online,
            transport = transport,
            atElapsed = SystemClock.elapsedRealtime(),
        )
        DiagnosticLog.event(
            DIAG_TAG, "сеть изменилась",
            "причина" to reason,
            "поколение" to generation,
            "онлайн" to online,
            "транспорт" to transport,
        )
    }

    /**
     * Отпечаток интерфейса: имя + число адресов, БЕЗ самих адресов (§10.10 —
     * адреса в журнал и в память не тащим). Этого достаточно, чтобы заметить смену
     * IP или подъём VPN, и недостаточно, чтобы что-то о сети раскрыть.
     */
    private fun linkFingerprint(props: android.net.LinkProperties): String =
        "${props.interfaceName}/${props.linkAddresses.size}"

    /** Тип транспорта одним словом — без имён интерфейсов и адресов (§10.10). */
    private fun transportOf(caps: NetworkCapabilities?): String = when {
        caps == null -> "нет"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильный"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "другой"
    }

    private companion object {
        const val DIAG_TAG = "net-watch"
    }
}

/**
 * Снимок «поколения» сети.
 *
 * @param id монотонный счётчик переключений; 0 — состояние ещё не известно.
 * @param online есть ли ПОДТВЕРЖДЁННЫЙ (validated) доступ в интернет.
 * @param transport тип транспорта для журнала (`wifi`, `мобильный`, `vpn`, …).
 * @param atElapsed монотонное время события ([SystemClock.elapsedRealtime]).
 */
data class NetworkEpoch(
    val id: Long,
    val online: Boolean,
    val transport: String,
    val atElapsed: Long,
) {
    companion object {
        val UNKNOWN = NetworkEpoch(id = 0L, online = false, transport = "нет", atElapsed = 0L)
    }
}
