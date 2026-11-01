package com.aegis.messenger.call

import com.aegis.core.common.security.TorMode
import com.aegis.network.tor.TurnInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallIcePlanTest {

    private val forwarder = IceTurnServer("turn:127.0.0.2:40123?transport=tcp", "1:u", "c")

    @Test
    fun torModeIsRelayOnlyThroughForwarder() {
        val plan = CallIcePlan.forMode(TorMode.ALWAYS, listOf(forwarder))
        assertTrue(plan.mediaOverTor)
        assertTrue(plan.relayOnly)
        assertTrue(plan.torIceTimings)
        assertEquals(listOf(forwarder), plan.servers)
    }

    @Test
    fun torModeWithoutOnionTurnDoesNotStart() {
        assertRelayRequired(emptyList())
        // STUN или клирнет-TURN в режиме Tor означали бы прямое соединение.
        assertRelayRequired(listOf(IceTurnServer("stun:relay.example.com:3478", "", "")))
        assertRelayRequired(listOf(IceTurnServer("turn:relay.example.com:3478?transport=tcp", "u", "c")))
        assertRelayRequired(listOf(forwarder, IceTurnServer("stun:relay.example.com:3478", "", "")))
        assertRelayRequired(listOf(IceTurnServer("turn:127.0.0.2:40123?transport=udp", "u", "c")))
    }

    @Test
    fun directModeIsAllWithOwnStunAndTurn() {
        val turn = TurnInfo(
            onion = "",
            port = 0,
            username = "1:u",
            credential = "c",
            realm = "aegis",
            ttlSec = 3600,
            uris = listOf(
                "turn:relay.example.com:3478?transport=udp",
                "turns:relay.example.com:5349?transport=tcp",
                "stun:relay.example.com:3478",
            ),
        )
        val plan = CallIcePlan.forMode(TorMode.OFF, CallIcePlan.clearnetServers(turn, relayHost = "relay.example.com"))
        assertFalse(plan.mediaOverTor)
        assertFalse(plan.relayOnly)
        assertFalse(plan.torIceTimings)
        assertTrue(plan.hasRelay)
        assertEquals("stun:relay.example.com:3478", plan.servers.first().uri)
        assertEquals("", plan.servers.first().username)
        assertTrue(plan.servers.drop(1).all { it.username == "1:u" && it.credential == "c" })
        assertEquals(3, plan.servers.size)
    }

    @Test
    fun directModeWithoutServersStillStarts() {
        val plan = CallIcePlan.forMode(TorMode.OFF, emptyList())
        assertFalse(plan.relayOnly)
        assertFalse(plan.hasRelay)
    }

    @Test
    fun clearnetServersRejectUnsafeEntries() {
        val turn = TurnInfo(
            onion = "x.onion",
            port = 3478,
            username = "u",
            credential = "c",
            realm = "",
            ttlSec = 0,
            uris = listOf(
                // Публичный STUN третьей стороны получил бы IP пользователя.
                "stun:stun.l.google.com:19302",
                "turn:127.0.0.1:3478",
                "turn:192.168.1.10:3478",
                "turn:10.0.0.1:3478",
                "turn:172.20.0.1:3478",
                "turn:100.64.1.1:3478",
                "turn:169.254.0.1:3478",
                "turn:localhost:3478",
                "turn:relay.onion:3478",
                "turn:[2001:db8::1]:3478",
                "turn:203.0.113.10:3478?transport=tcp",
                "turn:relay.example.com.evil.example:3478",
                "http://relay.example.com",
                "turn:turn.relay.example.com:3478?transport=udp",
                "turns:relay.example.com:5349?transport=tcp",
            ),
        )
        assertEquals(
            listOf("turn:turn.relay.example.com:3478?transport=udp", "turns:relay.example.com:5349?transport=tcp"),
            CallIcePlan.clearnetServers(turn, relayHost = "relay.example.com").map { it.uri },
        )
    }

    @Test
    fun siblingSubdomainsOfRelayAreOwnServer() {
        val turn = TurnInfo(
            "", 0, "u", "c", "", 0,
            uris = listOf("turn:turn.example.org:3478", "stun:example.org:3478", "turn:other.net:3478"),
        )
        assertEquals(
            listOf("stun:example.org:3478", "turn:turn.example.org:3478"),
            CallIcePlan.clearnetServers(turn, relayHost = "relay.example.org").map { it.uri },
        )
    }

    @Test
    fun legacyRelayStunFieldIsUsedWhenUrisAreMissing() {
        val legacy = TurnInfo("x.onion", 3478, "u", "c", "", 0, stun = "relay.example.com:3478")
        assertEquals(
            listOf("stun:relay.example.com:3478"),
            CallIcePlan.clearnetServers(legacy, relayHost = "relay.example.com").map { it.uri },
        )
        val none = TurnInfo("x.onion", 3478, "u", "c", "", 0)
        assertTrue(CallIcePlan.clearnetServers(none, relayHost = "relay.example.com").isEmpty())
    }

    private fun assertRelayRequired(servers: List<IceTurnServer>) {
        try {
            CallIcePlan.forMode(TorMode.ALWAYS, servers)
            fail("режим Tor без onion-TURN должен отказывать: $servers")
        } catch (_: CallIcePlan.RelayRequiredException) {
        }
    }
}
