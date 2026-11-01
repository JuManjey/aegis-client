package com.aegis.network.tor

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClientTest {

    private class FakeTransport(
        override val kind: RelayTransportKind = RelayTransportKind.HTTPS,
        private val respond: (method: String, path: String) -> RelayHttpResponse,
    ) : RelayHttpTransport {
        val calls = mutableListOf<Triple<String, String, Int>>()

        override fun request(
            method: String,
            path: String,
            body: ByteArray?,
            contentType: String?,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): RelayHttpResponse {
            calls += Triple(method, path, timeoutMs)
            return respond(method, path)
        }
    }

    private fun ok(body: String) = RelayHttpResponse(200, body.toByteArray())

    @Test
    fun pollDecodesItemsAndUsesLongPollTimeout() {
        val data = Base64.getEncoder().encodeToString("envelope".toByteArray())
        val transport = FakeTransport { _, _ -> ok("""{"items":[{"seq":7,"dataB64":"$data","ts":1}],"next":7}""") }
        val result = RelayClient(transport).poll("mbx", after = 3, waitSec = 30)
        assertEquals(7, result.next)
        assertArrayEquals("envelope".toByteArray(), result.items.single().data)
        assertEquals(Triple("GET", "/v1/mailbox/mbx?after=3&wait=30", 50_000), transport.calls.single())
    }

    @Test
    fun pollFailureKeepsCursor() {
        val transport = FakeTransport { _, _ -> RelayHttpResponse(500, ByteArray(0)) }
        val result = RelayClient(transport).poll("mbx", after = 9, waitSec = 30)
        assertEquals(9, result.next)
        assertTrue(result.items.isEmpty())
    }

    @Test(expected = IOException::class)
    fun pollRejectsCorruptBase64() {
        val transport = FakeTransport { _, _ -> ok("""{"items":[{"seq":1,"dataB64":"@@@"}],"next":1}""") }
        RelayClient(transport).poll("mbx", after = 0, waitSec = 0)
    }

    @Test
    fun postReturnsSeqOnlyOnSuccess() {
        assertEquals(5L, RelayClient(FakeTransport { _, _ -> ok("""{"seq":5}""") }).post("mbx", byteArrayOf(1)))
        assertNull(RelayClient(FakeTransport { _, _ -> RelayHttpResponse(507, ByteArray(0)) }).post("mbx", byteArrayOf(1)))
    }

    @Test
    fun legacyTurnResponseHasOnionAndNoUris() {
        val turn = RelayClient.parseTurn(
            """{"onion":"turn.onion","port":3478,"username":"1:a","credential":"c","realm":"aegis","ttl":3600,"stun":""}""",
        )!!
        assertTrue(turn.hasOnion)
        assertEquals(emptyList<String>(), turn.uris)
    }

    @Test
    fun clearnetOnlyTurnResponseIsUsable() {
        val turn = RelayClient.parseTurn(
            """{"onion":"","port":3478,"username":"1:a","credential":"c","realm":"aegis","ttl":3600,
               "stun":"relay.example.com:3478",
               "uris":["stun:relay.example.com:3478","turn:relay.example.com:3478?transport=udp"," ",7]}""",
        )!!
        assertFalse(turn.hasOnion)
        assertEquals(
            listOf("stun:relay.example.com:3478", "turn:relay.example.com:3478?transport=udp"),
            turn.uris,
        )
    }

    @Test
    fun turnWithoutCredentialsOrWayToConnectIsNull() {
        assertNull(RelayClient.parseTurn("""{"onion":"turn.onion","port":3478,"username":"","credential":"c"}"""))
        assertNull(RelayClient.parseTurn("""{"onion":"","port":0,"username":"u","credential":"c","uris":[]}"""))
        assertNull(RelayClient.parseTurn("not json"))
    }

    @Test
    fun aliasStatusesAreDistinguished() {
        fun put(status: Int, body: String = "{}") =
            RelayClient(FakeTransport { _, _ -> RelayHttpResponse(status, body.toByteArray()) }).putAlias("i", "c", "s", "")
        assertEquals(AliasPutResult.Ok, put(200))
        assertEquals(AliasPutResult.Taken, put(409))
        assertEquals(AliasPutResult.PowRequired, put(429))
        assertEquals(AliasPutResult.Unavailable, put(404))
        assertEquals(AliasPutResult.Rejected("подпись"), put(400, """{"error":"подпись"}"""))
    }

    @Test
    fun healthDistinguishesRelayFromOtherServices() {
        assertEquals(RelayHealth.Ok, RelayClient(FakeTransport { _, _ -> ok("""{"service":"aegis-relay"}""") }).health())
        assertEquals(RelayHealth.NotARelay, RelayClient(FakeTransport { _, _ -> ok("<html>") }).health())
        assertEquals(
            RelayHealth.HttpError(404),
            RelayClient(FakeTransport { _, _ -> RelayHttpResponse(404, ByteArray(0)) }).health(),
        )
    }

    @Test
    fun transportKindIsVisibleToCallers() {
        assertEquals(RelayTransportKind.TOR, RelayClient(FakeTransport(RelayTransportKind.TOR) { _, _ -> ok("{}") }).transportKind)
    }

    @Test
    fun configAndParamsParse() {
        val client = RelayClient(
            FakeTransport { _, path ->
                when (path) {
                    "/v1/config" -> ok("""{"doc":"ZG9j","sig":"c2ln"}""")
                    "/v1/params" -> ok("""{"directory":true,"alias":false,"powBits":20,"powEpochSecs":600}""")
                    else -> RelayHttpResponse(404, ByteArray(0))
                }
            },
        )
        assertEquals(SignedConfig("ZG9j", "c2ln"), client.getConfig())
        val params = client.getParams()!!
        assertTrue(params.directoryEnabled)
        assertEquals(20, params.powBits)
    }
}
