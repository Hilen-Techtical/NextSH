// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.core.backup.BackupLimits
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The deriver of a backup restore. The platforms use it for a restore, never for the key import screen nor for
 * "Copy the public key" (they use [SshPublicKeyDeriver] itself, which has no budget).
 *
 * - It remembers what [this] gave for a private key read WITHOUT passphrase, keyed by the SHA-256 of its exact
 *   text: a file that carries the same private key under many ids pays for one derivation only.
 * - It bounds the expensive proofs of the restore: at most [ProofBudget.MAX_LARGE_RSA_PROOFS] RSA keys over
 *   [ProofBudget.LARGE_RSA_BITS] bits are signed. Past that, such a key gives [SshPublicKeyDerivation.Unsupported]
 *   without any signature (not proven: the restore treats it like an encrypted key). The cache alone would not
 *   do: a forged file changes a check integer or a blank and every copy is a new text. The budget applies when
 *   [this] is [SshPublicKeyDeriver]; another deriver is called as it is.
 * - Only results are kept, and they are public ([SshPublicKeyDerivation.Derived] holds a public key, the other
 *   outcomes hold nothing). Never the text of the key, never a passphrase.
 * - A call with a passphrase (not empty) is never cached (it still spends the budget).
 * - Create one per restore and drop it with the restore: neither the cache nor the budget is process wide. The
 *   cache stops growing past [BackupLimits.MAX_PRIVATE_KEYS] entries.
 *
 * Thread safe. Two threads asking for the same new key at once may both derive it.
 */
fun PublicKeyDeriver.memoizedWithoutPassphrase(): PublicKeyDeriver = MemoizedPublicKeyDeriver(this, ProofBudget())

/** [memoizedWithoutPassphrase] with a given [budget] (tests: a smaller size limit, a look at the signatures). */
internal fun PublicKeyDeriver.memoizedWithoutPassphrase(budget: ProofBudget): PublicKeyDeriver =
    MemoizedPublicKeyDeriver(this, budget)

/**
 * The expensive proofs allowed to one restore: [maxLargeRsaProofs] RSA proofs over [largeRsaBits] bits, smaller
 * keys are never counted. A real backup rarely holds more than a few RSA keys over 4 096 bits, and each costs
 * about a second on a phone (16 384 bits). Thread safe.
 */
internal class ProofBudget(
    private val maxLargeRsaProofs: Int = MAX_LARGE_RSA_PROOFS,
    val largeRsaBits: Int = LARGE_RSA_BITS,
) {
    private var largeRsaProofs = 0
    private var signatureCount = 0

    /** True when the proof of an RSA key of [bits] may run, and counts it when it is a large one. */
    @Synchronized
    fun allowRsaProof(bits: Int): Boolean {
        if (bits <= largeRsaBits) return true
        if (largeRsaProofs >= maxLargeRsaProofs) return false
        largeRsaProofs++
        return true
    }

    /** Called by the deriver right before each signature, whatever the key type. */
    @Synchronized
    fun countSignature() {
        signatureCount++
    }

    /** Signatures made under this budget: lets a test see that none was attempted. */
    val signatures: Int
        @Synchronized get() = signatureCount

    override fun toString(): String = "ProofBudget"

    companion object {
        /** RSA keys over [LARGE_RSA_BITS] proven per restore. */
        const val MAX_LARGE_RSA_PROOFS = 16

        /** Size over which an RSA proof is counted, in bits. */
        const val LARGE_RSA_BITS = 4_096
    }
}

private class MemoizedPublicKeyDeriver(
    private val delegate: PublicKeyDeriver,
    private val budget: ProofBudget,
) : PublicKeyDeriver {

    private val results = ConcurrentHashMap<String, SshPublicKeyDerivation>()

    override fun derive(privateKeyPem: String, passphrase: CharArray?): SshPublicKeyDerivation {
        if (passphrase != null && passphrase.isNotEmpty()) return delegateDerive(privateKeyPem, passphrase)
        // Too long to be read anyway: no hash of a huge text, the deriver refuses it on its length.
        if (privateKeyPem.length > MAX_TEXT_CHARS) return delegateDerive(privateKeyPem, null)
        val id = fingerprint(privateKeyPem)
        results[id]?.let { return it }
        val result = delegateDerive(privateKeyPem, null)
        if (results.size < BackupLimits.MAX_PRIVATE_KEYS) results.putIfAbsent(id, result)
        return result
    }

    private fun delegateDerive(privateKeyPem: String, passphrase: CharArray?): SshPublicKeyDerivation =
        if (delegate === SshPublicKeyDeriver) {
            SshPublicKeyDeriver.derive(privateKeyPem, passphrase, budget)
        } else {
            delegate.derive(privateKeyPem, passphrase)
        }

    /** SHA-256 of the UTF-8 text, the encoded copy wiped. */
    private fun fingerprint(text: String): String {
        val bytes = text.encodeToByteArray()
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    override fun toString(): String = "MemoizedPublicKeyDeriver"

    private companion object {
        /** Same bound as the deriver: 64 KiB plus whitespace. */
        const val MAX_TEXT_CHARS = BackupLimits.MAX_SECRET_VALUE_BYTES + 4 * 1024
    }
}
