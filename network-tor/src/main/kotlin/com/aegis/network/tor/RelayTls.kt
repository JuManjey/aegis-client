package com.aegis.network.tor

import com.aegis.core.common.security.HttpsRelayAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS для режима «Tor ВЫКЛ»: системное хранилище доверия плюс две проверки
 * внутри рукопожатия — до того, как по соединению уйдёт первый байт HTTP (а в
 * нём путь с id ящика):
 *  - версия не ниже TLS 1.2 (1.3 — где её поддерживает платформа);
 *  - пины SPKI, если заданы: в ПРОВЕРЕННОЙ цепочке должен найтись хотя бы один
 *    ключ из списка. Проверенной, а не присланной: иначе подделку с валидным
 *    листом спасло бы приложенное к ней настоящее промежуточное звено.
 *
 * Сокеты остаются платформенными (обёртка над сокетом сломала бы SNI в
 * HttpsURLConnection), поэтому проверки живут в менеджере доверия.
 */
internal object RelayTls {

    fun socketFactory(
        expectedHost: String,
        pins: List<String>,
        systemTrust: X509TrustManager = systemTrustManager(),
        chainCleaner: ChainCleaner = platformChainCleaner(systemTrust),
    ): SSLSocketFactory {
        val guard = GuardedTrustManager(systemTrust, pins, chainCleaner, expectedHost)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(guard), null)
        return context.socketFactory
    }

    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Проверенная цепочка. На Android — `X509TrustManagerExtensions`: та же
     * проверка, что у платформы (с Network Security Config), и цепочка до корня.
     * Вне Android (JVM-тесты) класса нет — тогда проверка системным менеджером и
     * присланная цепочка.
     */
    fun platformChainCleaner(system: X509TrustManager): ChainCleaner {
        val onAndroid = System.getProperty("java.vm.name").orEmpty().contains("Dalvik")
        return if (onAndroid) {
            val extensions = android.net.http.X509TrustManagerExtensions(system)
            ChainCleaner { chain, authType, host -> extensions.checkServerTrusted(chain, authType, host) }
        } else {
            ChainCleaner { chain, authType, _ ->
                system.checkServerTrusted(chain, authType)
                chain.toList()
            }
        }
    }

    /** SHA-256 от SubjectPublicKeyInfo сертификата в base64 (формат пина без префикса). */
    fun spkiSha256(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    val MODERN_PROTOCOLS: Set<String> = setOf("TLSv1.2", "TLSv1.3")
}

/** Проверяет цепочку и возвращает её проверенный вариант (до доверенного корня). */
internal fun interface ChainCleaner {
    @Throws(CertificateException::class)
    fun clean(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate>
}

internal class GuardedTrustManager(
    private val system: X509TrustManager,
    pins: List<String>,
    private val cleaner: ChainCleaner,
    private val expectedHost: String,
) : X509ExtendedTrustManager() {

    private val pinHashes: Set<String> = HttpsRelayAddress.normalizePins(pins)
        .map { it.removePrefix(HttpsRelayAddress.PIN_PREFIX) }
        .toSet()

    // Проверку имени хоста (endpoint identification) JSSE поручает
    // X509ExtendedTrustManager: без вызова системного метода с сокетом/движком
    // обёртка молча её отключила бы (HttpsURLConnection JDK сам имя не сверяет).
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        verify(chain, authType, (socket as? SSLSocket)?.handshakeSession) {
            (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        verify(chain, authType, engine?.handshakeSession) {
            (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        verify(chain, authType, session = null) {}
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        throw CertificateException("клиентская аутентификация не используется")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        throw CertificateException("клиентская аутентификация не используется")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("клиентская аутентификация не используется")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    private fun verify(
        chain: Array<X509Certificate>,
        authType: String,
        session: SSLSession?,
        endpointCheck: () -> Unit,
    ) {
        requireModernProtocol(session?.protocol)
        endpointCheck()
        val validated = cleaner.clean(chain, authType, session?.peerHost ?: expectedHost)
        if (pinHashes.isEmpty()) return
        if (validated.none { RelayTls.spkiSha256(it) in pinHashes }) {
            throw CertificateException("ключ сертификата HTTPS-релея не совпал ни с одним пином SPKI")
        }
    }

    companion object {
        /** Без известной версии протокола соединение не продолжаем (fail-closed). */
        fun requireModernProtocol(protocol: String?) {
            if (protocol !in RelayTls.MODERN_PROTOCOLS) {
                throw CertificateException("версия TLS ниже 1.2 или не определена: ${protocol ?: "нет"}")
            }
        }
    }
}
