package com.aegis.messenger.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.data.storage.AndroidKeyStoreManager
import com.aegis.data.storage.LockedInboxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LockedInboxStoreTest {

    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(base.filesDir, "locked-inbox-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val ctx: Context = object : ContextWrapper(base.applicationContext) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }
    private val keyStore = AndroidKeyStoreManager(ctx)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun queueKeepsArrivalOrder_survivesReopen_andRemovesEntries() {
        val store = LockedInboxStore(ctx, keyStore)
        assertTrue(store.enqueue("inbox-a", "first", receivedAtMs = 1))
        assertTrue(store.enqueue("inbox-b", "second", receivedAtMs = 2))
        assertTrue(store.enqueue("inbox-a", "third", receivedAtMs = 3))

        // Новый экземпляр — как после перезапуска процесса: нумерация продолжается.
        val reopened = LockedInboxStore(ctx, keyStore)
        assertTrue(reopened.enqueue("inbox-c", "fourth", receivedAtMs = 4))
        val pending = reopened.pending()
        assertEquals(listOf("first", "second", "third", "fourth"), pending.map { it.payload })
        assertEquals(listOf("inbox-a", "inbox-b", "inbox-a", "inbox-c"), pending.map { it.inbox })
        assertEquals(listOf(1L, 2L, 3L, 4L), pending.map { it.receivedAtMs })

        reopened.remove(pending[1].id)
        assertEquals(listOf("first", "third", "fourth"), reopened.pending().map { it.payload })
    }

    @Test
    fun inboxesRoundTrip_andClearWipesEverything() {
        val store = LockedInboxStore(ctx, keyStore)
        store.saveInboxes(setOf("x", "y"))
        store.enqueue("x", "payload")
        assertEquals(setOf("x", "y"), LockedInboxStore(ctx, keyStore).inboxes())

        store.clear()
        assertTrue(store.inboxes().isEmpty())
        assertTrue(store.pending().isEmpty())
        assertFalse(File(dir, "locked_queue").exists())
    }

    @Test
    fun fullQueue_refusesNewEntries() {
        val store = LockedInboxStore(ctx, keyStore)
        var accepted = 0
        while (store.enqueue("inbox", "p$accepted")) accepted++
        assertEquals(1000, accepted)
        assertFalse(store.enqueue("inbox", "overflow"))
        store.remove(store.pending().first().id)
        assertTrue("место освободилось", store.enqueue("inbox", "after"))
    }
}
