package org.hack4impact.portal.adapters.vaultwarden

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** A 64-byte symmetric key as Bitwarden uses it: 32 bytes for AES-256, 32 for HMAC-SHA256. */
class SymmetricKey(val bytes: ByteArray) {
	init { require(bytes.size == 64) { "a Bitwarden symmetric key is 64 bytes, not ${bytes.size}" } }
	val enc: ByteArray get() = bytes.copyOfRange(0, 32)
	val mac: ByteArray get() = bytes.copyOfRange(32, 64)
}

/**
 * The few pieces of Bitwarden's client-side encryption the portal needs to write to Vaultwarden (build plan
 * step 9, wiki decision 101), so it doesn't depend on the Bitwarden CLI:
 *
 *  - unlock the organization key as the service admin: master password → master key (PBKDF2-SHA256, salted with
 *    the email) → stretched with HKDF ("enc", "mac") → user key → RSA private key → organization key;
 *  - encrypt a collection name with the organization key (EncString type 2: AES-256-CBC + HMAC-SHA256);
 *  - encrypt the organization key for a member being confirmed (EncString type 4: RSA-OAEP with SHA-1).
 *
 * Only PBKDF2 accounts are supported (Bitwarden's default); Argon2 accounts get a clear error.
 */
object VaultwardenCrypto {
	private val random = SecureRandom()
	private val b64 = Base64.getDecoder()
	private val b64e = Base64.getEncoder()

	/** The master key, before stretching. */
	fun masterKey(password: String, email: String, iterations: Int): ByteArray {
		val spec = PBEKeySpec(password.toCharArray(), email.trim().lowercase().toByteArray(), iterations, 256)
		return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
	}

	/** The master key stretched into an encryption and a MAC key with HKDF-Expand (SHA-256). */
	fun stretch(masterKey: ByteArray) = SymmetricKey(hkdfExpand(masterKey, "enc") + hkdfExpand(masterKey, "mac"))

	/** Decrypts an EncString type 2 ("2.iv|data|mac"), checking its MAC first. */
	fun decrypt(encString: String, key: SymmetricKey): ByteArray {
		require(encString.startsWith("2.")) { "expected an AES-CBC-256 + HMAC EncString (type 2)" }
		val (iv, data, mac) = encString.removePrefix("2.").split('|').also { require(it.size == 3) { "malformed EncString" } }.map(b64::decode)
		check(MessageDigest.isEqual(hmac(key.mac, iv + data), mac)) { "wrong key or password: the MAC doesn't match" }
		return aes(Cipher.DECRYPT_MODE, key.enc, iv, data)
	}

	fun encrypt(plain: ByteArray, key: SymmetricKey): String {
		val iv = ByteArray(16).also(random::nextBytes)
		val data = aes(Cipher.ENCRYPT_MODE, key.enc, iv, plain)
		return "2.${b64e.encodeToString(iv)}|${b64e.encodeToString(data)}|${b64e.encodeToString(hmac(key.mac, iv + data))}"
	}

	fun encryptString(text: String, key: SymmetricKey) = encrypt(text.toByteArray(Charsets.UTF_8), key)

	fun decryptString(encString: String, key: SymmetricKey) = String(decrypt(encString, key), Charsets.UTF_8)

	/** Decrypts an EncString type 4 ("4.data", RSA-OAEP SHA-1), e.g. an organization key shared with this user. */
	fun rsaDecrypt(encString: String, privateKey: PrivateKey): ByteArray {
		require(encString.startsWith("4.")) { "expected an RSA-OAEP EncString (type 4)" }
		return oaep(Cipher.DECRYPT_MODE, privateKey).doFinal(b64.decode(encString.removePrefix("4.")))
	}

	fun rsaEncrypt(plain: ByteArray, publicKey: PublicKey): String =
		"4." + b64e.encodeToString(oaep(Cipher.ENCRYPT_MODE, publicKey).doFinal(plain))

	fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

	/** A member's public key as Vaultwarden returns it (base64 SubjectPublicKeyInfo). */
	fun publicKey(base64: String): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(b64.decode(base64)))

	/**
	 * The organization key, from the service admin's account: [protectedUserKey] and [protectedPrivateKey] from
	 * `/api/sync`'s profile, [protectedOrgKey] from its organization entry.
	 */
	fun organizationKey(password: String, email: String, iterations: Int, protectedUserKey: String, protectedPrivateKey: String, protectedOrgKey: String): SymmetricKey {
		val userKey = SymmetricKey(decrypt(protectedUserKey, stretch(masterKey(password, email, iterations))))
		val privateKey = privateKey(decrypt(protectedPrivateKey, userKey))
		return SymmetricKey(rsaDecrypt(protectedOrgKey, privateKey))
	}

	private fun hkdfExpand(prk: ByteArray, info: String): ByteArray = hmac(prk, info.toByteArray() + byteArrayOf(1)) // one block: 32 bytes

	private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
		Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

	private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
		Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)

	private fun oaep(mode: Int, key: java.security.Key): Cipher =
		Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
			init(mode, key, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
		}
}
