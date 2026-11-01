package com.aegis.network.tor

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Адрес для локального TCP-форвардера TURN.
 *
 * WebRTC на Android 15/16 + Samsung падает в native TurnPort в двух известных
 * случаях:
 *  1) `turn:127.0.0.1` — NetworkMonitor/BindSocketToNetwork для `lo` даёт SIGSEGV;
 *  2) `turn:<wlan-ipv4>` — TCP self-connect на свой Wi-Fi адрес роняет процесс
 *     на SM-S928B / Android 16 ещё до accept() форвардера.
 *
 * `127.0.0.2` остаётся loopback (весь 127.0.0.0/8 на `lo`), но не совпадает с
 * специальным путём `127.0.0.1` и не является адресом wlan. Это предпочтительный
 * bind для ICE URI.
 */
object LocalBindAddress {
    const val LOOPBACK = "127.0.0.1"

    /**
     * Альтернативный loopback для WebRTC. Ядро принимает bind/connect на любой
     * адрес из 127.0.0.0/8; Samsung не применяет к нему сломанный `lo`-handle.
     */
    const val LOOPBACK_ALT = "127.0.0.2"

    fun isLoopbackHost(host: String): Boolean =
        host == LOOPBACK || host == LOOPBACK_ALT || host.startsWith("127.")

    /**
     * Кандидаты bind-адреса в порядке безопасности для WebRTC: сначала
     * 127.0.0.2, затем dummy, затем LAN, в конце классический 127.0.0.1.
     */
    fun bindCandidates(): List<String> {
        val out = linkedSetOf<String>()
        out.add(LOOPBACK_ALT)
        pickDummyIpv4()?.let { out.add(it) }
        pickNonLoopbackIpv4()?.let { out.add(it) }
        out.add(LOOPBACK)
        return out.toList()
    }

    fun pickNonLoopbackIpv4(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        var best: Pair<Int, String>? = null
        for (iface in ifaces) {
            val up = runCatching { iface.isUp }.getOrDefault(false)
            val loop = runCatching { iface.isLoopback }.getOrDefault(true)
            if (!up || loop) continue
            val name = iface.name ?: continue
            val score = scoreInterface(name)
            if (score <= 0) continue
            for (addr in iface.inetAddresses) {
                val host = ipv4Host(addr) ?: continue
                if (best == null || score > best.first) best = score to host
            }
        }
        return best?.second
    }

    fun pickDummyIpv4(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (iface in ifaces) {
            val up = runCatching { iface.isUp }.getOrDefault(false)
            if (!up) continue
            val name = iface.name ?: continue
            if (scoreInterface(name) != 0) continue
            for (addr in iface.inetAddresses) {
                ipv4Host(addr)?.let { return it }
            }
        }
        return null
    }

    fun pickOrLoopback(): String = bindCandidates().first()

    internal fun scoreInterface(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") || n.startsWith("wifi") -> 4
            n.startsWith("eth") || n.startsWith("en") -> 3
            n.startsWith("rmnet") || n.startsWith("ccmni") ||
                n.startsWith("v4-") || n.startsWith("wwan") -> 2
            n.startsWith("dummy") -> 0
            else -> 1
        }
    }

    private fun ipv4Host(addr: java.net.InetAddress): String? {
        if (addr !is Inet4Address) return null
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress || addr.isMulticastAddress
        ) {
            return null
        }
        return addr.hostAddress
    }
}
