package io.github.pdrakeweb.drivesync.crypto

import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.Paths
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.core.SyncSetup
import io.github.pdrakeweb.drivesync.core.SyncState
import io.github.pdrakeweb.drivesync.core.TableSpec
import io.github.pdrakeweb.drivesync.core.Value
import io.github.pdrakeweb.drivesync.core.WireCodec
import io.github.pdrakeweb.drivesync.core.WrappedKey
import io.github.pdrakeweb.drivesync.testing.InMemoryBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.SimulationOptions
import io.github.pdrakeweb.drivesync.testing.Simulator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val FAST = Argon2Cost(memoryKib = 256, iterations = 1, parallelism = 1)
private const val MARKER = "PLAINTEXT-MARKER"

class PassphraseKeyManagerTest {
    private fun manager() = PassphraseKeyManager(InMemoryKeyStorage(), FAST)
    private fun ready() = manager().also { runBlocking { it.create("correct horse".toCharArray()) } }
    private fun errorOf(block: suspend () -> Unit): SyncError =
        assertThrows(SyncException::class.java) { runBlocking { block() } }.error

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    @Test
    fun `objects round-trip and never contain their plaintext`() = runBlocking {
        val keys = ready()
        val plain = "some $MARKER inside a longer payload $MARKER".encodeToByteArray()
        val one = keys.encrypt("batches/a/1.bin", plain)
        val two = keys.encrypt("batches/a/1.bin", plain)
        assertArrayEquals(plain, keys.decrypt("batches/a/1.bin", one))
        assertFalse(one.contentEquals(two), "a fresh nonce every time")
        assertFalse(contains(one, MARKER.encodeToByteArray()))
        assertArrayEquals(ByteArray(0), keys.decrypt("p", keys.encrypt("p", ByteArray(0))))
    }

    @Test
    fun `a tampered, truncated or misplaced object is rejected`() = runBlocking {
        val keys = ready()
        val path = "batches/a/000000000001-0123456789abcdef.bin"
        val sealed = keys.encrypt(path, "payload".encodeToByteArray())
        for (i in sealed.indices) {
            val flipped = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertEquals(SyncError.Corrupt(path), errorOf { keys.decrypt(path, flipped) }, "byte $i")
        }
        assertEquals(SyncError.Corrupt(path), errorOf { keys.decrypt(path, sealed.copyOf(sealed.size - 1)) })
        assertEquals(SyncError.Corrupt(path), errorOf { keys.decrypt(path, ByteArray(0)) })
        val elsewhere = "batches/b/000000000001-0123456789abcdef.bin"
        assertEquals(SyncError.Corrupt(elsewhere), errorOf { keys.decrypt(elsewhere, sealed) })
        assertEquals(SyncError.Corrupt(path), errorOf { ready().decrypt(path, sealed) }, "another key")
    }

    @Test
    fun `fingerprints are stable for one key and useless without it`() = runBlocking {
        val keys = ready()
        val a = keys.fingerprint("content".encodeToByteArray())
        assertEquals(a, keys.fingerprint("content".encodeToByteArray()))
        assertTrue(Regex("[0-9a-f]{16}").matches(a))
        assertNotEquals(a, keys.fingerprint("content!".encodeToByteArray()))
        assertNotEquals(a, ready().fingerprint("content".encodeToByteArray()), "same content, different key")
    }

    @Test
    fun `the passphrase opens the key on another device, a wrong one leaves it with no key`() = runBlocking {
        val first = manager()
        val wrapped = first.create("correct horse".toCharArray())
        val sealed = first.encrypt("p", "hello".encodeToByteArray())
        assertEquals("argon2id", wrapped.kdfAlgorithm)

        val second = manager()
        assertEquals(KeyState.NeedsPassphrase, second.state())
        assertEquals(SyncError.KeyMissing, errorOf { second.encrypt("p", ByteArray(1)) })
        second.unlock("correct horse".toCharArray(), wrapped)
        assertEquals(KeyState.Ready, second.state())
        assertArrayEquals("hello".encodeToByteArray(), second.decrypt("p", sealed))
        assertEquals(first.fingerprint(ByteArray(3)), second.fingerprint(ByteArray(3)))

        assertEquals(SyncError.KeyMissing, errorOf { second.unlock("wrong horse".toCharArray(), wrapped) })
        assertEquals(KeyState.NeedsPassphrase, second.state(), "a failed unlock leaves no key behind")
    }

    @Test
    fun `the key survives a restart through key storage, and can be forgotten`() = runBlocking {
        val storage = InMemoryKeyStorage()
        val before = PassphraseKeyManager(storage, FAST)
        before.create("pw".toCharArray())
        val sealed = before.encrypt("p", "x".encodeToByteArray())
        val after = PassphraseKeyManager(storage, FAST)
        assertEquals(KeyState.Ready, after.state())
        assertArrayEquals("x".encodeToByteArray(), after.decrypt("p", sealed))
        after.forget()
        assertEquals(KeyState.NeedsPassphrase, PassphraseKeyManager(storage, FAST).state())
    }

    @Test
    fun `a tampered meta file cannot demand absurd work or pass as valid`() = runBlocking {
        val wrapped = manager().create("pw".toCharArray())
        fun with(params: Map<String, String>, salt: ByteArray = wrapped.salt, alg: String = "argon2id") =
            WrappedKey(alg, params, salt, wrapped.wrappedKey)
        val huge = wrapped.kdfParams + ("memoryKib" to Int.MAX_VALUE.toString())
        assertEquals(SyncError.Corrupt(Paths.META), errorOf { manager().unlock("pw".toCharArray(), with(huge)) })
        assertEquals(SyncError.Corrupt(Paths.META), errorOf { manager().unlock("pw".toCharArray(), with(emptyMap())) })
        assertEquals(SyncError.Corrupt(Paths.META), errorOf { manager().unlock("pw".toCharArray(), with(wrapped.kdfParams, ByteArray(0))) })
        assertTrue(errorOf { manager().unlock("pw".toCharArray(), with(wrapped.kdfParams, alg = "md5")) } is SyncError.UnsupportedFormat)
        val changed = wrapped.kdfParams + ("iterations" to "2")
        assertEquals(SyncError.KeyMissing, errorOf { manager().unlock("pw".toCharArray(), with(changed)) })
    }

    @Test
    fun `a join code carries the key to another device and rejects damage`() = runBlocking {
        val first = ready()
        val sealed = first.encrypt("p", "hello".encodeToByteArray())
        val code = first.exportJoinCode()
        assertTrue(code.startsWith("DS1."))

        val second = manager()
        assertFalse(second.importJoinCode("DS1.not-a-code"))
        assertFalse(second.importJoinCode(code.dropLast(2) + "AA"))
        assertFalse(second.importJoinCode(code.removePrefix("DS1.")))
        assertEquals(KeyState.NeedsPassphrase, second.state())
        assertTrue(second.importJoinCode("  $code\n"))
        assertArrayEquals("hello".encodeToByteArray(), second.decrypt("p", sealed))
        assertEquals(SyncError.KeyMissing, assertThrows(SyncException::class.java) { manager().exportJoinCode() }.error)
    }
}

/** Invariant C-2 across the whole stack, with the real key manager. */
class EncryptedSyncTest {
    private val registry = MergeRegistry(1, listOf(TableSpec("secret_table_name", MergeClass.LWW_FIELDS)))
    private val cloud = InMemoryBlobStore()
    private var now = 1_800_000_000_000L

    private inner class Device(name: String) {
        val id = ReplicaId(name)
        val keys = PassphraseKeyManager(InMemoryKeyStorage(), FAST)
        val store = InMemoryLocalStore(id, registry) { now }
        val setup = SyncSetup(cloud, keys) { now }
        val engine = SyncEngine(id, store, cloud, keys, registry, SyncConfig("$MARKER label of $name", "$MARKER-platform")) { now }
        fun write(row: String, text: String) = store.write("secret_table_name", row, mapOf("secret_column" to Value.Text(text)))
        suspend fun sync(): SyncState { now += 1_000; return engine.sync() }
    }

    private fun contains(haystack: ByteArray, needle: String): Boolean {
        val n = needle.encodeToByteArray()
        return (0..haystack.size - n.size).any { i -> n.indices.all { haystack[i + it] == n[it] } }
    }

    @Test
    fun `a device holding the key sets a new passphrase, and only the new one opens storage`() = runBlocking {
        val phone = Device("phone"); val tablet = Device("tablet")
        phone.setup.create("forgotten one".toCharArray())
        tablet.setup.join("forgotten one".toCharArray())
        phone.write("r1", "before the change"); phone.sync()
        val metaBefore = cloud.raw(Paths.META)!!

        phone.setup.changePassphrase("the new passphrase".toCharArray())

        // Devices that already hold the key carry on without being asked for anything.
        tablet.write("r2", "after the change")
        for (d in listOf(tablet, phone, tablet)) assertTrue(d.sync() is SyncState.Idle)
        assertEquals(phone.store.mergedState(), tablet.store.mergedState())

        val laptop = Device("laptop")
        assertEquals(SyncSetup.Result.WrongPassphrase, laptop.setup.join("forgotten one".toCharArray()))
        assertEquals(SyncSetup.Result.Ready, laptop.setup.join("the new passphrase".toCharArray()))
        assertTrue(laptop.sync() is SyncState.Idle)
        assertEquals(phone.store.mergedState(), laptop.store.mergedState())

        // C-2: meta.json still holds only a wrapped key and parameters, under a fresh salt.
        val before = WireCodec.decodeMeta(metaBefore); val after = WireCodec.decodeMeta(cloud.raw(Paths.META)!!)
        assertEquals(before.created, after.created)
        assertTrue(before.kdf.salt != after.kdf.salt && before.wrappedKey != after.wrappedKey)
        for (word in listOf("the new passphrase", "forgotten one")) assertFalse(contains(cloud.raw(Paths.META)!!, word))
    }

    @Test
    fun `a device without the key cannot set a passphrase, and storage is untouched`() = runBlocking {
        val phone = Device("phone"); val stranger = Device("stranger")
        phone.setup.create("pass phrase".toCharArray())
        val metaBefore = cloud.raw(Paths.META)!!.toList()
        val thrown = assertThrows(SyncException::class.java) { runBlocking { stranger.setup.changePassphrase("mine now".toCharArray()) } }
        assertEquals(SyncError.KeyMissing, thrown.error)
        assertEquals(metaBefore, cloud.raw(Paths.META)!!.toList())
        assertEquals(SyncSetup.Result.Ready, Device("tablet").setup.join("pass phrase".toCharArray()))
    }

    @Test
    fun `if storage lost meta json, a device holding the key can put it back`() = runBlocking {
        val phone = Device("phone")
        phone.setup.create("pass phrase".toCharArray())
        phone.write("r1", "kept"); phone.sync()
        cloud.delete(Paths.META)
        phone.setup.changePassphrase("another one".toCharArray())
        val tablet = Device("tablet")
        assertEquals(SyncSetup.Result.Ready, tablet.setup.join("another one".toCharArray()))
        assertTrue(tablet.sync() is SyncState.Idle)
        assertEquals(phone.store.mergedState(), tablet.store.mergedState())
    }

    @Test
    fun `a join code from a different sync set is recognised before it does any harm`() = runBlocking {
        val phone = Device("phone"); val tablet = Device("tablet")
        phone.setup.create("pass phrase".toCharArray())
        assertTrue(phone.setup.keyOpensStorage(), "nothing stored yet: nothing to contradict the key")
        phone.write("r1", "note"); phone.sync()
        assertTrue(phone.setup.keyOpensStorage())

        val elsewhere = PassphraseKeyManager(InMemoryKeyStorage(), FAST).also { it.create("other".toCharArray()) }
        assertTrue(tablet.keys.importJoinCode(elsewhere.exportJoinCode()), "well formed, so it is accepted as a code")
        assertFalse(tablet.setup.keyOpensStorage(), "but it does not open what is stored here")

        assertTrue(tablet.keys.importJoinCode(phone.keys.exportJoinCode()))
        assertTrue(tablet.setup.keyOpensStorage())
        assertTrue(tablet.sync() is SyncState.Idle)
        assertEquals(phone.store.mergedState(), tablet.store.mergedState())
    }

    @Test
    fun `first device creates, second joins with the passphrase, and nothing readable is stored`() = runBlocking {
        val phone = Device("phone"); val tablet = Device("tablet")
        assertEquals(SyncSetup.Status.NotSetUp, phone.setup.status())
        assertEquals(SyncState.NeedsKey, phone.sync())
        assertEquals(SyncSetup.Result.NotSetUp, tablet.setup.join("pass phrase".toCharArray()))
        assertEquals(SyncSetup.Result.Ready, phone.setup.create("pass phrase".toCharArray()))

        assertEquals(SyncSetup.Status.NeedsPassphrase, tablet.setup.status())
        assertEquals(SyncSetup.Result.WrongPassphrase, tablet.setup.join("guess".toCharArray()))
        assertEquals(SyncState.NeedsKey, tablet.sync())
        assertEquals(SyncSetup.Result.Ready, tablet.setup.join("pass phrase".toCharArray()))
        assertEquals(SyncSetup.Status.Ready, tablet.setup.status())

        phone.write("row-$MARKER", "$MARKER written on the phone")
        tablet.write("r2", "$MARKER written on the tablet")
        for (d in listOf(phone, tablet, phone)) assertTrue(d.sync() is SyncState.Idle)
        assertEquals(phone.store.mergedState(), tablet.store.mergedState())
        assertEquals(2, tablet.store.rows("secret_table_name").size)

        // C-2: every object except meta.json is ciphertext; names carry ids, numbers and tags only.
        val readable = listOf(MARKER, "secret_table_name", "secret_column", "pass phrase", "label of")
        assertEquals(5, cloud.paths().size)
        for (path in cloud.paths()) {
            val bytes = cloud.raw(path)!!
            for (word in readable) assertFalse(contains(bytes, word), "$word found in $path")
            assertTrue(Regex("meta\\.json|replicas/(phone|tablet)\\.json|batches/(phone|tablet)/\\d{12}-[0-9a-f]{16}\\.bin").matches(path), path)
        }
        // meta.json holds only the wrapped key and derivation parameters.
        val meta = Json.parseToJsonElement(cloud.raw(Paths.META)!!.decodeToString()).jsonObject
        assertEquals(setOf("format", "created", "kdf", "wrappedKey"), meta.keys)
        assertEquals(setOf("alg", "params", "salt"), meta.getValue("kdf").jsonObject.keys)
    }

    @Test
    fun `a second device creating at the same time ends up on the first one's key`() = runBlocking {
        val a = Device("phone"); val b = Device("tablet")
        assertEquals(SyncSetup.Result.Ready, a.setup.create("pw".toCharArray()))
        assertEquals(SyncSetup.Result.Ready, b.setup.create("pw".toCharArray()))
        a.write("r", "x"); a.sync(); assertTrue(b.sync() is SyncState.Idle)
        assertEquals(a.store.mergedState(), b.store.mergedState())

        val c = Device("third")
        assertEquals(SyncSetup.Result.WrongPassphrase, c.setup.create("different".toCharArray()))
        assertEquals(KeyState.NeedsPassphrase, c.keys.state())
    }

    @Test
    fun `tampering with any stored object is detected`() = runBlocking {
        val a = Device("phone"); val b = Device("tablet")
        a.setup.create("pw".toCharArray()); b.setup.join("pw".toCharArray())
        a.write("r", "x"); a.sync()
        for (path in cloud.paths().filter { it != Paths.META }) {
            val good = cloud.raw(path)!!
            cloud.tamper(path, good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
            assertEquals(SyncState.Error(SyncError.Corrupt(path), null), b.sync(), path)
            cloud.tamper(path, good)
        }
        assertTrue(b.sync() is SyncState.Idle)
    }

    @Test
    fun `the simulator's invariants hold with real encryption`() {
        val shared = PassphraseKeyManager(InMemoryKeyStorage(), FAST).also { runBlocking { it.create("pw".toCharArray()) } }
        val code = shared.exportJoinCode()
        val options = SimulationOptions(keys = { PassphraseKeyManager(InMemoryKeyStorage(), FAST).also { it.importJoinCode(code) } })
        val failures = (0 until 300L).map { Simulator.run(it, options) }.filter { it.violations.isNotEmpty() }
        assertEquals(emptyList<String>(), failures.map { "seed ${it.seed}: ${it.violations.take(2)}" })
    }
}
