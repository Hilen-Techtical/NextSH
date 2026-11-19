// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.testutil.TestSshKeys
import fr.techtical.nextsh.ui.vault.keyImportFailureRes
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The shared deriver reads a key file BEFORE the SSH library at import: a forged file costs nothing (no
 * bcrypt round runs), and a file whose clear public key is not the one of its private key is refused.
 * The SSH library is a strict mock here: any call to it would end as an unreadable file, never as these reasons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportSshKeyUseCaseDeriverTest {

    private val keyManager = mockk<SshKeyManager>()
    private val keyRepository = mockk<SshKeyRepository>()
    private val vaultManager = mockk<VaultManager>()
    private lateinit var useCase: ImportSshKeyUseCase

    @BeforeEach
    fun setUp() {
        useCase = ImportSshKeyUseCase(keyManager, keyRepository, vaultManager, UnconfinedTestDispatcher())
    }

    private fun verifyUntouched() {
        verify(exactly = 0) { keyManager.loadKeyProviderFromString(any(), any()) }
        verify(exactly = 0) { vaultManager.storePrivateKey(any(), any()) }
        coVerify(exactly = 0) { keyRepository.save(any()) }
    }

    @Test
    fun `a forged file with 2 billion bcrypt rounds is refused at once, with or without a passphrase`() = runTest {
        val pem = TestSshKeys.forgedBcryptRounds(Int.MAX_VALUE.toLong())
        val start = System.nanoTime()

        val without = useCase("work", pem, null).exceptionOrNull()
        val with = useCase("work", pem, "any".toCharArray()).exceptionOrNull()

        assertTrue(without is KeyTooCostlyException, without.toString())
        assertTrue(with is KeyTooCostlyException, with.toString())
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "no key derivation function may run")
        verifyUntouched()
    }

    @Test
    fun `rounds over the bound by one are refused, rounds at the bound are not a cost refusal`() = runTest {
        val over = useCase("work", TestSshKeys.forgedBcryptRounds(SshPublicKeyDeriver.MAX_BCRYPT_ROUNDS + 1L), null).exceptionOrNull()

        assertTrue(over is KeyTooCostlyException, over.toString())
        // At the bound the file is encrypted: without a passphrase the screen asks for one.
        val atBound = useCase("work", TestSshKeys.forgedBcryptRounds(SshPublicKeyDeriver.MAX_BCRYPT_ROUNDS.toLong()), null).exceptionOrNull()
        assertTrue(atBound is PassphraseRequiredException, atBound.toString())
        verifyUntouched()
    }

    @Test
    fun `a key file whose public key is not the one of its private key is refused as inconsistent`() = runTest {
        val failure = useCase("work", TestSshKeys.inconsistentEd25519(), null).exceptionOrNull()

        assertTrue(failure is InconsistentKeyException, failure.toString())
        verifyUntouched()
    }

    @Test
    fun `a real encrypted key without passphrase asks for one and with a wrong one is a wrong passphrase`() = runTest {
        val key = TestSshKeys.encryptedEd25519("right one")

        val needs = useCase("work", key.pem, null).exceptionOrNull()
        val wrong = useCase("work", key.pem, "wrong one".toCharArray()).exceptionOrNull()

        assertTrue(needs is PassphraseRequiredException, needs.toString())
        assertTrue(wrong is WrongKeyPassphraseException, wrong.toString())
        verifyUntouched()
    }

    @Test
    fun `an encrypted key with a scheme outside the accepted lists is refused before the SSH library, whatever the passphrase`() = runTest {
        val pem = TestSshKeys.encryptedPkcs8Pkcs12Scheme()

        val without = useCase("work", pem, null).exceptionOrNull()
        val with = useCase("work", pem, "typed by the user".toCharArray()).exceptionOrNull()

        assertTrue(without is UnsupportedKeyEncryptionException, without.toString())
        assertTrue(with is UnsupportedKeyEncryptionException, with.toString())
        verifyUntouched()
    }

    @Test
    fun `the same scheme with a huge iteration count is a cost refusal first`() = runTest {
        val failure = useCase("work", TestSshKeys.encryptedPkcs8Pkcs12Scheme(Int.MAX_VALUE.toLong()), "any".toCharArray())
            .exceptionOrNull()

        assertTrue(failure is KeyTooCostlyException, failure.toString())
        verifyUntouched()
    }

    @Test
    fun `a deriver that says unsupported encryption never lets the SSH library see the key`() = runTest {
        val fake = ImportSshKeyUseCase(
            keyManager, keyRepository, vaultManager, UnconfinedTestDispatcher(),
            deriver = { _, _ -> fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation.UnsupportedEncryption },
        )

        val failure = fake("work", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----", "pp".toCharArray())
            .exceptionOrNull()

        assertTrue(failure is UnsupportedKeyEncryptionException, failure.toString())
        verifyUntouched()
    }

    @Test
    fun `the new refusals have their own sentences`() {
        assertEquals(R.string.vault_key_import_unsupported_encryption, keyImportFailureRes(UnsupportedKeyEncryptionException()))
        assertEquals(R.string.vault_key_import_too_costly, keyImportFailureRes(KeyTooCostlyException()))
        assertEquals(R.string.vault_key_import_inconsistent, keyImportFailureRes(InconsistentKeyException()))
    }
}
