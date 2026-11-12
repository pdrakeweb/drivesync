package io.github.pdrakeweb.drivesync.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.pdrakeweb.drivesync.crypto.KeyStorage
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the sync data key on the device, encrypted by a key that lives in the Android Keystore
 * (the system's hardware-backed key vault: the wrapping key cannot be read out, even by this app).
 * The passphrase is therefore entered once per device.
 *
 * The encrypted key is stored in the no-backup directory, so Android's automatic backup never
 * copies it; a Keystore key cannot be restored on another device anyway.
 */
public class KeystoreKeyStorage(context: Context, private val name: String = "drivesync") : KeyStorage {
    private val file = File(context.applicationContext.noBackupFilesDir, "$name.key")
    private val alias = "$name-data-key-wrapper"

    override fun load(): ByteArray? {
        if (!file.exists()) return null
        val stored = file.readBytes()
        if (stored.size <= IV_BYTES) return null
        return try {
            val key = keystore().getKey(alias, null) as? SecretKey ?: return null
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
                doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            // The Keystore key is gone or unusable (for example after a device restore). The app
            // sees "no key" and asks for the passphrase again; nothing is lost.
            null
        }
    }

    override fun store(key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.iv + cipher.doFinal(key)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not store the sync key" }
        }
    }

    override fun clear() {
        file.delete()
        keystore().run { if (containsAlias(alias)) deleteEntry(alias) }
    }

    private fun keystore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun wrappingKey(): SecretKey {
        (keystore().getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Draws a join code (from `PassphraseKeyManager.exportJoinCode()`) as a QR code, so a second
 * device can join by scanning instead of typing the passphrase. Scanning is left to the app, which
 * passes the scanned text to `importJoinCode`.
 *
 * A join code is the key itself: show it only on request, and never log or store the image.
 */
public object JoinQr {
    public fun matrix(joinCode: String): BitMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        return QRCodeWriter().encode(joinCode, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** A square bitmap about [sizePx] wide, black on white. */
    public fun bitmap(joinCode: String, sizePx: Int): Bitmap {
        val matrix = matrix(joinCode)
        val scale = (sizePx / matrix.width).coerceAtLeast(1)
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { i ->
            if (matrix[(i % size) / scale, (i / size) / scale]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
