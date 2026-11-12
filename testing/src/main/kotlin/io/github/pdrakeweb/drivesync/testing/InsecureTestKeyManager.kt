package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.KeyManager
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.core.WrappedKey

/**
 * NOT ENCRYPTION. A reversible scramble for tests and the simulator, so they can run without the
 * crypto module while still catching objects that bypass the key manager, are tampered with, or
 * are read back from a different path. Real apps use the `:crypto` module.
 */
public class InsecureTestKeyManager(public var keyState: KeyState = KeyState.Ready) : KeyManager {
    override suspend fun state(): KeyState = keyState

    override suspend fun create(passphrase: CharArray): WrappedKey {
        keyState = KeyState.Ready
        return WrappedKey("test", emptyMap(), ByteArray(0), ByteArray(0))
    }

    override suspend fun unlock(passphrase: CharArray, wrapped: WrappedKey) {
        keyState = KeyState.Ready
    }

    override suspend fun encrypt(path: String, plain: ByteArray): ByteArray {
        requireReady()
        val out = ByteArray(HEADER + plain.size)
        MAGIC.copyInto(out)
        writeInt(out, MAGIC.size, path.hashCode())
        writeInt(out, MAGIC.size + 4, plain.contentHashCode())
        for (i in plain.indices) out[HEADER + i] = (plain[i].toInt() xor MASK).toByte()
        return out
    }

    override suspend fun decrypt(path: String, cipher: ByteArray): ByteArray {
        requireReady()
        if (cipher.size < HEADER || !cipher.copyOf(MAGIC.size).contentEquals(MAGIC) ||
            readInt(cipher, MAGIC.size) != path.hashCode()
        ) {
            throw SyncException(SyncError.Corrupt(path))
        }
        val plain = ByteArray(cipher.size - HEADER) { (cipher[HEADER + it].toInt() xor MASK).toByte() }
        if (readInt(cipher, MAGIC.size + 4) != plain.contentHashCode()) throw SyncException(SyncError.Corrupt(path))
        return plain
    }

    override suspend fun fingerprint(plain: ByteArray): String {
        requireReady()
        val a = plain.contentHashCode()
        val b = plain.reversedArray().contentHashCode() xor 0x5EED
        return "%08x%08x".format(a, b)
    }

    private fun requireReady() {
        if (keyState != KeyState.Ready) throw SyncException(SyncError.KeyMissing)
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        for (i in 0..3) target[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun readInt(source: ByteArray, at: Int): Int =
        (0..3).fold(0) { acc, i -> acc or ((source[at + i].toInt() and 0xFF) shl (8 * i)) }

    private companion object {
        val MAGIC = byteArrayOf(0x54, 0x45, 0x53, 0x54)
        const val HEADER = 12
        const val MASK = 0xA5
    }
}
