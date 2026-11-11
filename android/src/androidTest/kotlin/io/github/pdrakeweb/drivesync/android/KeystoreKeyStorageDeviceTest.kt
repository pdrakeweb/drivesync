package io.github.pdrakeweb.drivesync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.crypto.Argon2Cost
import io.github.pdrakeweb.drivesync.crypto.PassphraseKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs on a device or emulator: the Android Keystore does not exist on the JVM. */
@RunWith(AndroidJUnit4::class)
class KeystoreKeyStorageDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "drivesync-test"

    @After
    fun cleanUp() = KeystoreKeyStorage(context, name).clear()

    @Test
    fun keyIsStoredEncryptedAndSurvivesANewInstance() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        KeystoreKeyStorage(context, name).store(key)

        assertArrayEquals(key, KeystoreKeyStorage(context, name).load())
        val onDisk = File(context.noBackupFilesDir, "$name.key").readBytes()
        assertTrue("stored in the no-backup directory", onDisk.isNotEmpty())
        assertFalse("the key itself is not on disk", onDisk.toList().windowed(8).contains(key.toList().take(8)))

        KeystoreKeyStorage(context, name).clear()
        assertNull(KeystoreKeyStorage(context, name).load())
    }

    @Test
    fun aDamagedFileReadsAsNoKeyRatherThanCrashing() {
        val storage = KeystoreKeyStorage(context, name)
        storage.store(ByteArray(32) { 9 })
        val file = File(context.noBackupFilesDir, "$name.key")
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it.last() + 1).toByte() })
        assertNull(storage.load())
    }

    @Test
    fun passphraseIsNeededOncePerDevice() = runBlocking {
        val cost = Argon2Cost(memoryKib = 1024, iterations = 1, parallelism = 1)
        val first = PassphraseKeyManager(KeystoreKeyStorage(context, name), cost)
        first.create("correct horse".toCharArray())
        val sealed = first.encrypt("p", "hello".toByteArray())

        // A new process: nothing in memory, only the Keystore-wrapped key on disk.
        val later = PassphraseKeyManager(KeystoreKeyStorage(context, name), cost)
        assertEquals(KeyState.Ready, later.state())
        assertArrayEquals("hello".toByteArray(), later.decrypt("p", sealed))
    }

    @Test
    fun defaultArgon2CostIsUsableOnThisDevice() = runBlocking {
        val manager = PassphraseKeyManager(KeystoreKeyStorage(context, name))
        val started = System.nanoTime()
        manager.create("a passphrase".toCharArray())
        val millis = (System.nanoTime() - started) / 1_000_000
        println("drivesync: Argon2id 64 MiB x3 took $millis ms on this device")
        assertTrue("took $millis ms", millis < 20_000)
    }
}
