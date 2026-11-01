package com.aegis.messenger.multidevice

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.crypto.CryptoModule
import com.aegis.messenger.messaging.SelfSyncManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка forward secrecy self-sync (follow-up №2.3, `docs/multidevice.md`
 * §6.2): `msg`/`revoke` между МОИМИ устройствами идут ДВОЙНЫМ конвейером — слой A
 * (каскад на детерминированном `selfSyncSecret`) → слой B (per-device Signal-
 * сессия между моими устройствами, Double Ratchet, MD-1). До №2.3 всё
 * запечатывалось ТОЛЬКО каскадом на долгоживущем `selfSyncSecret`: его
 * компрометация раскрывала всю self-sync-историю (нет forward secrecy).
 *
 * Здесь доказываем ключевые свойства:
 *  1) конверт `msg`/`revoke` НЕЛЬЗЯ вскрыть одним лишь `selfSyncSecret` — поверх
 *     каскада стоит слой B (Signal), значит компрометации секрета аккаунта
 *     недостаточно для чтения истории;
 *  2) конверт несёт `sigType` (тип Signal-конверта) — маркер слоя B;
 *  3) ratchet продвигается: два конверта одинакового контента дают РАЗНЫЕ
 *     шифртексты (PreKey → Whisper) — forward secrecy;
 *  4) сквозной round-trip B→A на приёмнике восстанавливает исходный контент.
 *
 * Крипта настоящая (Argon2id, каскад слоя A, libsignal per-device X3DH/ratchet);
 * два устройства-аккаунта поднимаются в одном процессе ([MdTestDevice]).
 */
@RunWith(AndroidJUnit4::class)
class SelfSyncForwardSecrecyTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()
    private val contactId = "aegis:bob"

    @Before
    fun ready() {
        assertTrue("Нативное ядро должно быть доступно", CryptoModule.isNativeReady)
    }

    @After
    fun tearDown() {
        devices.forEach { it.cleanup() }
        devices.clear()
    }

    @Test
    fun selfSyncMsg_wrappedInSignal_notCascadeOnly_andRatchetAdvances() = runBlocking {
        val primary = MdTestDevice(ctx, "fs-primary").also { devices += it }
        val tablet = MdTestDevice(ctx, "fs-tablet").also { devices += it }

        val mnemonic = SelfSyncTestSupport.primary(primary)
        SelfSyncTestSupport.linked(tablet, mnemonic, deviceId = 2, label = "Tablet")
        val accountId = SelfSyncTestSupport.accountId(primary)
        val secret = SelfSyncTestSupport.selfSyncSecret(mnemonic)
        val tabletBundle = SelfSyncTestSupport.bundleOf(tablet)

        fun msgWire(msgId: String) = SelfSyncTestSupport.buildWire(
            sender = primary, accountId = accountId, senderDevice = 1,
            targetDeviceId = 2, targetBundle = tabletBundle, secret = secret,
            kind = SelfSyncManager.KIND_MSG,
            contentJson = SelfSyncTestSupport.msgContent(contactId, msgId, "secret body"),
        )

        // --- Первый конверт: PreKey (устанавливает сессию) ---
        val wire1 = msgWire("m-1")
        val obj1 = JSONObject(wire1)
        val sigType1 = obj1.getString("sigType")
        val ct1 = Base64.decode(obj1.getString("ciphertextB64"), Base64.NO_WRAP)

        assertEquals("первый конверт слоя B — PreKey", "PreKey", sigType1)

        // (1) Один лишь selfSyncSecret НЕ вскрывает конверт: поверх каскада — слой B.
        var cascadeOnlyFailed = false
        try {
            CryptoModule.createCascadeCipher(secret).decrypt(ct1)
        } catch (_: Exception) {
            cascadeOnlyFailed = true
        }
        assertTrue(
            "конверт msg не должен вскрываться одним каскадом на selfSyncSecret (слой B поверх, №2.3)",
            cascadeOnlyFailed,
        )

        // --- Второй конверт того же контента: ДРУГОЙ шифртекст (ratchet продвинулся) ---
        // Тип остаётся PreKey, пока отправитель не получит ответную реплику (X3DH/
        // PQXDH): сессия «полуустановлена». Но цепочка ключей уже ратчетится —
        // одинаковый plaintext даёт РАЗНЫЙ шифртекст, что и есть forward secrecy.
        val wire2 = msgWire("m-1")
        val obj2 = JSONObject(wire2)
        assertNotEquals(
            "ratchet должен продвигаться: одинаковый контент → разные шифртексты (forward secrecy)",
            obj1.getString("ciphertextB64"), obj2.getString("ciphertextB64"),
        )

        // (4) Сквозной round-trip B→A на приёмнике: контент восстановлен, дедуп по msgId.
        assertTrue(tablet.selfSyncManager.onWire(wire1))
        assertTrue(tablet.selfSyncManager.onWire(wire2))
        val msgs = tablet.database.messageStore.messagesFor(contactId)
        assertEquals("дедуп по msgId (обе копии — один msgId)", 1, msgs.size)
        assertEquals("secret body", msgs[0].body)

        secret.fill(0)
    }

    @Test
    fun malformedLayerB_isRejected_noStorage() = runBlocking {
        val tablet = MdTestDevice(ctx, "fs-bad").also { devices += it }
        val mnemonic = SelfSyncTestSupport.primary(tablet, login = "user")
        SelfSyncTestSupport.linked(tablet, mnemonic, deviceId = 2, label = "Tablet")
        val secret = SelfSyncTestSupport.selfSyncSecret(mnemonic)

        // Конверт msg с каскадом-БЕЗ-Signal (как в старом формате до №2.3): sigType
        // указан, но тело не является валидным Signal-конвертом → слой B не снимется.
        val cascadeOnly = Base64.encodeToString(
            CryptoModule.createCascadeCipher(secret).encrypt(
                SelfSyncTestSupport.msgContent(contactId, "m-x", "downgrade").toByteArray(Charsets.UTF_8),
            ),
            Base64.NO_WRAP,
        )
        val downgrade =
            """{"channel":"self-sync","kind":"msg","senderDevice":1,"ciphertextB64":"$cascadeOnly","sigType":"Whisper"}"""
        assertFalse(
            "конверт без валидного слоя B должен отвергаться (нет downgrade к каскаду-only)",
            tablet.selfSyncManager.onWire(downgrade),
        )
        assertEquals(
            "ничего не должно сохраниться при отклонённом конверте", 0,
            tablet.database.messageStore.messagesFor(contactId).size,
        )

        // И конверт msg вовсе без sigType (пустой слой B) — тоже отвергается.
        val noSig =
            """{"channel":"self-sync","kind":"msg","senderDevice":1,"ciphertextB64":"$cascadeOnly"}"""
        assertFalse("msg без sigType (нет слоя B) должен отвергаться", tablet.selfSyncManager.onWire(noSig))

        secret.fill(0)
    }
}
