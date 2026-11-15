package com.jbsoft.jobq.data.cv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for files at rest. */
interface CvCipher {
	fun encrypt(plain: ByteArray): ByteArray

	/** @throws GeneralSecurityException if the data was not produced by [encrypt] with the same key. */
	fun decrypt(encrypted: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that is generated in, and never leaves, the Android Keystore.
 * Output is the 12-byte IV followed by the ciphertext and tag.
 *
 * The key is not tied to the lock screen, so the CV stays readable for background work;
 * it is lost on uninstall or a factory reset, and does not move to another phone.
 */
class KeystoreCipher(private val keyAlias: String) : CvCipher {

	override fun encrypt(plain: ByteArray): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key())
		return cipher.iv + cipher.doFinal(plain)
	}

	override fun decrypt(encrypted: ByteArray): ByteArray {
		if (encrypted.size <= IV_BYTES) throw GeneralSecurityException("Truncated data")
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, encrypted, 0, IV_BYTES))
		return cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
	}

	@Synchronized
	private fun key(): SecretKey {
		val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
			init(
				KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setKeySize(256)
					.build()
			)
			generateKey()
		}
	}

	private companion object {
		const val KEYSTORE = "AndroidKeyStore"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_BYTES = 12
		const val TAG_BITS = 128
	}
}
