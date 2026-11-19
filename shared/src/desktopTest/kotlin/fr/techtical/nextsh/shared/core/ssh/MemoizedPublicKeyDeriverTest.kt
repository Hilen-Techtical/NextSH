// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoizedPublicKeyDeriverTest {

    private var calls = 0
    private val counting = PublicKeyDeriver { pem, passphrase ->
        calls++
        SshPublicKeyDeriver.derive(pem, passphrase)
    }

    @Test
    fun `the same private key under two ids is derived once`() {
        val memo = counting.memoizedWithoutPassphrase()
        val pem = TestKeyFiles.openssh(TestKeyFiles.ed25519())

        val first = memo.derive(pem, null) // id one
        val second = memo.derive(pem, null) // id two, same key text
        assertIs<SshPublicKeyDerivation.Derived>(first)
        assertSame(first, second)
        assertEquals(1, calls)

        // An empty passphrase is no passphrase: same entry.
        assertSame(first, memo.derive(pem, CharArray(0)))
        assertEquals(1, calls)

        // Another key is derived on its own.
        memo.derive(TestKeyFiles.openssh(TestKeyFiles.ed25519()), null)
        assertEquals(2, calls)
    }

    @Test
    fun `a failure is remembered too, so a repeated forged key costs one derivation`() {
        val memo = counting.memoizedWithoutPassphrase()
        val pem = TestKeyFiles.openssh(TestKeyFiles.ed25519(), passphrase = "secret")
        repeat(5) { assertEquals(SshPublicKeyDerivation.NeedsPassphrase, memo.derive(pem, null)) }
        assertEquals(1, calls)
    }

    @Test
    fun `a call with a passphrase is never cached`() {
        val memo = counting.memoizedWithoutPassphrase()
        val pem = TestKeyFiles.openssh(TestKeyFiles.ed25519(), passphrase = "secret")
        assertIs<SshPublicKeyDerivation.Derived>(memo.derive(pem, "secret".toCharArray()))
        assertIs<SshPublicKeyDerivation.Derived>(memo.derive(pem, "secret".toCharArray()))
        assertEquals(2, calls)
        // And it does not fill the entry of the key read without passphrase.
        assertEquals(SshPublicKeyDerivation.NeedsPassphrase, memo.derive(pem, null))
        assertEquals(3, calls)
    }

    @Test
    fun `two memos do not share their entries`() {
        val pem = TestKeyFiles.openssh(TestKeyFiles.ed25519())
        counting.memoizedWithoutPassphrase().derive(pem, null)
        counting.memoizedWithoutPassphrase().derive(pem, null)
        assertEquals(2, calls)
    }

    @Test
    fun `past the restore budget a large RSA key is not proven and nothing is signed`() {
        // The size limit is lowered for the test: a 2048 bit key counts as a large one (4096 in production).
        val budget = ProofBudget(largeRsaBits = 1024)
        val memo = SshPublicKeyDeriver.memoizedWithoutPassphrase(budget)
        val rsa = TestKeyFiles.rsa(2048)
        val fields = TestKeyFiles.opensshPrivateFields(rsa)
        val envelope = TestKeyFiles.blobOf(rsa.public)
        // Seventeen texts of the same key, only the check integers change: the cache alone would not help.
        val pems = (1..ProofBudget.MAX_LARGE_RSA_PROOFS + 1).map {
            TestKeyFiles.openssh(fields, envelope, checkInts = it.toLong() to it.toLong())
        }
        val results = pems.map { memo.derive(it, null) }
        results.take(ProofBudget.MAX_LARGE_RSA_PROOFS).forEach { assertIs<SshPublicKeyDerivation.Derived>(it) }
        assertEquals(SshPublicKeyDerivation.Unsupported, results.last())
        assertEquals(ProofBudget.MAX_LARGE_RSA_PROOFS, budget.signatures) // none for the seventeenth

        // Smaller keys are never counted, and a passphrase does not bypass the budget.
        assertIs<SshPublicKeyDerivation.Derived>(memo.derive(TestKeyFiles.openssh(TestKeyFiles.ed25519()), null))
        assertEquals(ProofBudget.MAX_LARGE_RSA_PROOFS + 1, budget.signatures)
        val encrypted = TestKeyFiles.openssh(rsa, passphrase = "secret")
        assertEquals(SshPublicKeyDerivation.Unsupported, memo.derive(encrypted, "secret".toCharArray()))

        // The bare deriver (key import, copy) has no budget.
        assertIs<SshPublicKeyDerivation.Derived>(SshPublicKeyDeriver.derive(pems.last(), null))
    }

    @Test
    fun `the default budget allows sixteen RSA proofs over 4096 bits`() {
        assertEquals(16, ProofBudget.MAX_LARGE_RSA_PROOFS)
        assertEquals(4096, ProofBudget.LARGE_RSA_BITS)
        val budget = ProofBudget()
        assertTrue((1..1000).all { budget.allowRsaProof(4096) }) // never counted
        assertTrue((1..16).all { budget.allowRsaProof(4097) })
        assertFalse(budget.allowRsaProof(16_384))
        assertTrue(budget.allowRsaProof(2048))
    }

    @Test
    fun `the text form prints nothing of a key`() {
        val memo = counting.memoizedWithoutPassphrase()
        val pem = TestKeyFiles.openssh(TestKeyFiles.ed25519())
        memo.derive(pem, null)
        assertEquals("MemoizedPublicKeyDeriver", memo.toString())
        assertFalse(memo.toString().contains("OPENSSH"))
    }
}
