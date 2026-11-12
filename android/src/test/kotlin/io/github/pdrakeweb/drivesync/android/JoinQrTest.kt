package io.github.pdrakeweb.drivesync.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.pdrakeweb.drivesync.crypto.Argon2Cost
import io.github.pdrakeweb.drivesync.crypto.InMemoryKeyStorage
import io.github.pdrakeweb.drivesync.crypto.PassphraseKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinQrTest {
    @Test
    fun `a join code survives being drawn as a QR code and read back`() = runBlocking {
        val cost = Argon2Cost(256, 1, 1)
        val first = PassphraseKeyManager(InMemoryKeyStorage(), cost).also { it.create("pw".toCharArray()) }
        val sealed = first.encrypt("p", "hello".toByteArray())
        val code = first.exportJoinCode()

        val matrix = JoinQr.matrix(code)
        val scale = 6
        val size = matrix.width * scale
        val source = object : LuminanceSource(size, size) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray =
                ByteArray(size) { x -> if (matrix[x / scale, y / scale]) 0 else -1 }
            override fun getMatrix(): ByteArray =
                ByteArray(size * size) { i -> if (matrix[(i % size) / scale, (i / size) / scale]) 0 else -1 }
        }
        val scanned = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        assertEquals(code, scanned)

        val second = PassphraseKeyManager(InMemoryKeyStorage(), cost)
        assertTrue(second.importJoinCode(scanned))
        assertArrayEquals("hello".toByteArray(), second.decrypt("p", sealed))
    }
}
