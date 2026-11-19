// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Sentinel exception signaling that the provided PEM is encrypted and a
 * passphrase is required. Callers can prompt the user then retry the import
 * with the passphrase parameter populated.
 */
class PassphraseRequiredException : Exception("SSH key requires a passphrase")

/**
 * The passphrase supplied for an encrypted key did not unlock it. Typed so that the screen picks
 * its own sentence (in the user's language): the message is a short technical text, never shown.
 * [cause] is the failure of the SSH library, kept for the class chain of the logs.
 */
class WrongKeyPassphraseException(cause: Throwable? = null) : Exception("Wrong key passphrase", cause)

/**
 * The text is not a private key NextSH can read (no PEM header, unknown format, damaged key).
 * Typed so that the screen picks its own sentence (in the user's language): the message is a short
 * technical text, never shown, and never built from what the SSH library says about the file.
 * [cause] is that failure, kept for the class chain of the logs.
 */
class UnreadableKeyException(cause: Throwable? = null) : Exception("Unreadable private key", cause)

/**
 * The key text is longer than [MAX_PRIVATE_KEY_BYTES]: it would not be restorable from a backup
 * either. Typed so that the screen picks its own sentence; the message is never shown.
 */
class KeyTooLargeException : Exception("Private key too large")

/**
 * Opening the key would cost too much (bcrypt rounds or PBKDF2 iterations over the bound of
 * `SshPublicKeyDeriver`): refused before any key derivation function runs. Typed so that the screen picks
 * its own sentence; the message is never shown.
 */
class KeyTooCostlyException : Exception("Private key too costly to open")

/**
 * An OpenSSH key file whose public key copy is not the public key of its private key: it could never sign in,
 * so it is not imported. Typed so that the screen picks its own sentence; the message is never shown.
 */
class InconsistentKeyException : Exception("Inconsistent private key file")

/**
 * An encrypted key whose encryption scheme is outside the lists of the shared deriver (PBES1, PKCS#12, an unknown
 * PRF or cipher...): never handed to the SSH library, which could run any number of iterations with the passphrase
 * typed. Typed so that the screen picks its own sentence; the message is never shown.
 */
class UnsupportedKeyEncryptionException : Exception("Unsupported key encryption")

/** Largest private key accepted at import, in UTF-8 bytes: the limit of one secret in a backup (64 KiB). */
const val MAX_PRIVATE_KEY_BYTES: Int = BackupLimits.MAX_SECRET_VALUE_BYTES

/** More than [MAX_PRIVATE_KEY_BYTES] of UTF-8. A character is at least one byte: length first. */
fun isPrivateKeyTooLarge(text: String): Boolean =
    text.length > MAX_PRIVATE_KEY_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_PRIVATE_KEY_BYTES

/** Bytes read from a key file: [MAX_PRIVATE_KEY_BYTES] plus a margin for the blanks around the key. */
const val MAX_PRIVATE_KEY_FILE_BYTES: Int = MAX_PRIVATE_KEY_BYTES + 4 * 1024

/**
 * UseCase : importer une clé SSH privée.
 *
 * Formats supportés (via SSHJ) :
 * - PEM PKCS#8 (.pem, .key) : -----BEGIN PRIVATE KEY-----
 * - PEM PKCS#1 RSA (.pem)   : -----BEGIN RSA PRIVATE KEY-----
 * - OpenSSH (.key, id_rsa, id_ed25519) : -----BEGIN OPENSSH PRIVATE KEY-----
 * - PEM EC (.pem)            : -----BEGIN EC PRIVATE KEY-----
 *
 * Étapes :
 * 1. Extrait la clé publique pour la metadata DB
 * 2. Stocke la clé privée chiffrée dans le vault
 * 3. Sauvegarde la metadata dans Room
 */
class ImportSshKeyUseCase internal constructor(
    private val keyManager: SshKeyManager,
    private val keyRepository: SshKeyRepository,
    private val vaultManager: VaultManager,
    private val dispatcher: CoroutineDispatcher,
    private val deriver: PublicKeyDeriver = SshPublicKeyDeriver,
) {
    @Inject
    constructor(
        keyManager: SshKeyManager,
        keyRepository: SshKeyRepository,
        vaultManager: VaultManager,
    ) : this(keyManager, keyRepository, vaultManager, Dispatchers.Default)

    suspend operator fun invoke(
        label: String,
        privateKeyPem: String,
        passphrase: CharArray? = null,
    ): Result<SshKey> {
        val trimmedKey = privateKeyPem.trim()
        if (isTooLarge(trimmedKey)) {
            passphrase?.fill('\u0000')
            return Result.failure(KeyTooLargeException())
        }
        if (!trimmedKey.contains("-----BEGIN ")) {
            passphrase?.fill('\u0000')
            return Result.failure(UnreadableKeyException())
        }

        // SSHJ's `PasswordUtils.createOneOff` wipes the char[] it's given after the
        // first `reqPassword` call: if we passed the caller's array straight to
        // SSHJ then tried to persist it in the vault, we'd persist a blanked array
        // and connects would fail with "passphrase incorrect". Snapshot up front,
        // use the snapshot for the vault, let SSHJ consume the original.
        val passphraseSnapshot = passphrase?.copyOf()

        // SSHJ reads a key lazily, at the first `private` / `public` access: loading the provider
        // proves nothing about the key. The read is forced here, off the main thread (a bcrypt
        // derivation or a large RSA key is slow), so that an encrypted key is detected now:
        // no passphrase supplied means the screen asks for one, a passphrase supplied that does
        // not unlock the key is a wrong passphrase, anything else is an unreadable file.
        // The failure is caught INSIDE the coroutine and returned as a value: an exception that
        // crosses the boundary of withContext can be copied by the stack trace recovery of the
        // coroutines, and the cause kept for the logs would no longer be the one of the library.
        val loaded: Result<String> = withContext(dispatcher) {
            try {
                // The shared deriver reads the key first: it bounds the cost of the key derivation function
                // (a forged file could announce 2^31 bcrypt rounds, even without a passphrase) and sees an
                // envelope that is not the public key of its private key. SSHJ comes after.
                val refusal = refusalOf(trimmedKey, passphrase)
                if (refusal != null) Result.failure(refusal) else Result.success(loadPublicKeyLine(trimmedKey, passphrase))
            } catch (e: Exception) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                // A forged key can announce gigabytes in its length fields: unreadable, not a crash.
                Result.failure(e)
            }
        }
        val publicKeyLine = loaded.getOrElse { return failLoad(it, passphrase, passphraseSnapshot) }

        val keyType = when {
            publicKeyLine.startsWith("ssh-ed25519") -> SshKeyType.ED25519
            publicKeyLine.startsWith("ssh-rsa") -> SshKeyType.RSA_4096
            publicKeyLine.startsWith("ecdsa-sha2-nistp256") -> SshKeyType.ECDSA_256
            publicKeyLine.startsWith("ecdsa-sha2-nistp384") -> SshKeyType.ECDSA_384
            publicKeyLine.startsWith("ecdsa-sha2-nistp521") -> SshKeyType.ECDSA_521
            else -> SshKeyType.RSA_4096
        }

        val id = java.util.UUID.randomUUID().toString()
        val sshKey = SshKey(
            id = id,
            label = label,
            keyType = keyType,
            publicKey = publicKeyLine,
            isBiometric = false,
        )

        return try {
            vaultManager.storePrivateKey(id, trimmedKey)
            // Persist the passphrase only if one was actually used for decrypt.
            // Use the snapshot taken before SSHJ got a chance to wipe the caller's
            // array (see note above).
            if (passphraseSnapshot != null && passphraseSnapshot.isNotEmpty()) {
                vaultManager.storeKeyPassphrase(id, passphraseSnapshot.copyOf())
            }
            keyRepository.save(sshKey)
            Timber.i("SSH key imported: id=${LogId.of(id)} ($keyType)")
            Result.success(sshKey)
        } catch (e: Exception) {
            Timber.e("Failed to persist SSH key id=${LogId.of(id)} (${errorClass(e)})")
            Result.failure(e)
        } finally {
            passphraseSnapshot?.fill('\u0000')
        }
    }

    private fun failLoad(e: Throwable, passphrase: CharArray?, snapshot: CharArray?): Result<SshKey> {
        // Class chain only: the message of the SSH library can quote the key.
        Timber.w("SSH key load failed (${errorClass(e)})")
        snapshot?.fill('\u0000')
        // Already typed by the shared deriver: kept as they are.
        if (e is PassphraseRequiredException || e is WrongKeyPassphraseException || e is KeyTooLargeException ||
            e is KeyTooCostlyException || e is InconsistentKeyException || e is UnsupportedKeyEncryptionException
        ) {
            return Result.failure(e)
        }
        return if (e !is OutOfMemoryError && isPassphraseError(e)) {
            if (passphrase == null) {
                Result.failure(PassphraseRequiredException())
            } else {
                Result.failure(WrongKeyPassphraseException(e))
            }
        } else {
            Result.failure(UnreadableKeyException(e))
        }
    }

    /**
     * The typed refusal for what the shared deriver says of [pem], or null when SSHJ may go on (a derived key,
     * or a format the deriver does not read: SSHJ then decides). The passphrase is read, never modified.
     */
    private fun refusalOf(pem: String, passphrase: CharArray?): Exception? = when (deriver.derive(pem, passphrase)) {
        SshPublicKeyDerivation.TooCostly -> KeyTooCostlyException()
        SshPublicKeyDerivation.TooLarge -> KeyTooLargeException()
        SshPublicKeyDerivation.NeedsPassphrase -> PassphraseRequiredException()
        SshPublicKeyDerivation.WrongPassphrase -> WrongKeyPassphraseException()
        is SshPublicKeyDerivation.Inconsistent -> InconsistentKeyException()
        SshPublicKeyDerivation.UnsupportedEncryption -> UnsupportedKeyEncryptionException()
        else -> null
    }

    /** Loads the key with SSHJ and forces the read of both halves; returns its public line. */
    private fun loadPublicKeyLine(pem: String, passphrase: CharArray?): String {
        val keyProvider = keyManager.loadKeyProviderFromString(pem, passphrase)
        keyProvider.private
        val pub = keyProvider.public
        val keyType = net.schmizz.sshj.common.KeyType.fromKey(pub)
        val encoded = java.util.Base64.getEncoder().encodeToString(
            net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(pub).compactData
        )
        return "$keyType $encoded nextsh-imported"
    }

    private fun isTooLarge(text: String): Boolean = isPrivateKeyTooLarge(text)

    /**
     * Heuristic matching [DesktopSshKeyLoader.isLikelyPassphraseError]: SSHJ
     * raises different exception classes depending on the key format, so we
     * walk the cause chain and look for passphrase-related markers.
     */
    private fun isPassphraseError(e: Throwable): Boolean {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return chain.any { t ->
            val msg = t.message?.lowercase() ?: ""
            t is com.hierynomus.sshj.common.KeyDecryptionFailedException ||
                t is javax.crypto.BadPaddingException ||
                t is java.security.InvalidKeyException ||
                "password" in msg || "passphrase" in msg ||
                "decrypt" in msg || "tag mismatch" in msg
        }
    }
}
