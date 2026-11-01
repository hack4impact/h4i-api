package org.hack4impact.portal.adapters.vaultwarden

import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up Bitwarden account, encrypted the way a Bitwarden client would, for the crypto and adapter tests. */
object TestAccount {
	const val PASSWORD = "correct horse battery staple"
	const val EMAIL = "Service-Admin@Example.org"
	const val ITERATIONS = 1000
	private val random = SecureRandom()
	val userKey = SymmetricKey(ByteArray(64).also(random::nextBytes))
	val orgKey = SymmetricKey(ByteArray(64).also(random::nextBytes))
	private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
	val protectedUserKey = VaultwardenCrypto.encrypt(userKey.bytes, VaultwardenCrypto.stretch(VaultwardenCrypto.masterKey(PASSWORD, EMAIL, ITERATIONS)))
	val protectedPrivateKey = VaultwardenCrypto.encrypt(keyPair.private.encoded, userKey)
	val protectedOrgKey = VaultwardenCrypto.rsaEncrypt(orgKey.bytes, keyPair.public)
}

class VaultwardenCryptoTests {
	@Test
	fun `the organization key unlocks from the master password, user key and private key`() {
		val key = VaultwardenCrypto.organizationKey(
			TestAccount.PASSWORD, "service-admin@example.org", TestAccount.ITERATIONS, // the email salt is lowercased
			TestAccount.protectedUserKey, TestAccount.protectedPrivateKey, TestAccount.protectedOrgKey,
		)
		assertContentEquals(TestAccount.orgKey.bytes, key.bytes)
	}

	@Test
	fun `a wrong password fails on the MAC instead of producing garbage`() {
		assertFailsWith<IllegalStateException> {
			VaultwardenCrypto.organizationKey("wrong", TestAccount.EMAIL, TestAccount.ITERATIONS, TestAccount.protectedUserKey, TestAccount.protectedPrivateKey, TestAccount.protectedOrgKey)
		}
	}

	@Test
	fun `names encrypt as type 2 strings with a fresh IV each time, and decrypt back`() {
		val a = VaultwardenCrypto.encryptString("umd-rise-dc", TestAccount.orgKey)
		val b = VaultwardenCrypto.encryptString("umd-rise-dc", TestAccount.orgKey)
		assertTrue(a.startsWith("2.") && a.split('|').size == 3)
		assertTrue(a != b)
		assertEquals("umd-rise-dc", VaultwardenCrypto.decryptString(a, TestAccount.orgKey))
	}

	@Test
	fun `the stretched key is HKDF-Expand of the master key with enc and mac`() {
		// HKDF-Expand with one block: HMAC-SHA256(prk, info || 0x01). Independent computation for a fixed key.
		val master = ByteArray(32) { it.toByte() }
		val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(master, "HmacSHA256")) }
		val expected = mac.doFinal("enc".toByteArray() + 1) + mac.doFinal("mac".toByteArray() + 1)
		assertContentEquals(expected, VaultwardenCrypto.stretch(master).bytes)
	}
}
