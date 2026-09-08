package com.example.georescux.domain.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
/**
 * Signing boundary for the trust model: outgoing locally-produced events
 * are signed, incoming authority/peer events are verified against the same
 * abstraction. Implementations must never leak the private key.
 *
 * [KeystoreEventSigner] is the production implementation (Android
 * Keystore, EC P-256, SHA256withECDSA). It is Android-bound and therefore
 * NOT covered by JVM tests — JVM tests use their own JDK EC key pairs
 * against the same interface (see EventAuthenticatorTest).
 */
interface EventSigner {
    fun sign(payload: ByteArray): ByteArray
    fun verify(payload: ByteArray, signature: ByteArray): Boolean
}

/**
 * Android Keystore-backed signer/verifier. The key is generated inside the
 * hardware-backed keystore (non-exportable); a lost keystore only means
 * new events carry a new origin key — verification of incoming events uses
 * the peer/authority key material shipped with them.
 */
class KeystoreEventSigner(private val alias: String = DEFAULT_ALIAS) : EventSigner {

    private fun privateKey(): PrivateKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it.privateKey }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        return generator.generateKeyPair().private
    }

    override fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey())
            update(payload)
        }.sign()

    override fun verify(payload: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            // Verification uses the certificate's public key from the keystore entry.
            initVerify(keyStoreCertificate())
            update(payload)
        }.verify(signature)
    } catch (e: Exception) {
        false // tampered/foreign/malformed signatures must fail closed, never throw
    }

    private fun keyStoreCertificate(): java.security.cert.Certificate {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getCertificate(alias)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val DEFAULT_ALIAS = "georescux-event-signing"
    }
}
