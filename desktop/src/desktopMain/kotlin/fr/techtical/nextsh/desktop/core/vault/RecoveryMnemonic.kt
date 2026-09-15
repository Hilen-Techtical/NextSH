// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP39 (English) recovery mnemonic.
 *
 * 128 bits of entropy → SHA-256 checksum (first 4 bits appended) → 132 bits,
 * split into 12 groups of 11 bits, each indexing the official 2048-word
 * English wordlist. The 12 words are shown once to the user as the vault
 * recovery phrase and used (normalized) as a PBKDF2 password to wrap the DEK.
 *
 * No third-party dependency: the bit-packing is implemented here over the
 * already-committed `bip39-english.txt` classpath resource.
 */
object RecoveryMnemonic {

    private const val WORDLIST_RESOURCE = "bip39-english.txt"
    private const val WORDLIST_SIZE = 2048
    private const val ENTROPY_BITS = 128
    private const val ENTROPY_BYTES = ENTROPY_BITS / 8 // 16
    private const val CHECKSUM_BITS = ENTROPY_BITS / 32 // 4
    private const val BITS_PER_WORD = 11
    private const val WORD_COUNT = (ENTROPY_BITS + CHECKSUM_BITS) / BITS_PER_WORD // 12

    /** Lowercase English wordlist, loaded once from the classpath. Index = word value. */
    private val wordList: List<String> by lazy { loadWordList() }

    /** word → index lookup for O(1) validation. */
    private val wordIndex: Map<String, Int> by lazy {
        wordList.withIndex().associate { (i, w) -> w to i }
    }

    private fun loadWordList(): List<String> {
        // Same package as the resource → a relative name resolves correctly.
        val stream = RecoveryMnemonic::class.java.getResourceAsStream(WORDLIST_RESOURCE)
            ?: error("BIP39 wordlist resource '$WORDLIST_RESOURCE' not found on classpath")
        val words = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        require(words.size == WORDLIST_SIZE) {
            "BIP39 wordlist must contain exactly $WORDLIST_SIZE words, found ${words.size}"
        }
        return words
    }

    /** Generates a fresh 12-word BIP39 mnemonic from 128 bits of secure entropy. */
    fun generate(): List<String> {
        val entropy = ByteArray(ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        return try {
            entropyToWords(entropy)
        } finally {
            // The mnemonic carries the entropy from here on; wipe the raw copy.
            java.util.Arrays.fill(entropy, 0)
        }
    }

    /**
     * Validates a 12-word mnemonic: correct length, every word in the list,
     * and the embedded checksum matches the entropy. Word order matters.
     */
    fun isValid(words: List<String>): Boolean {
        if (words.size != WORD_COUNT) return false
        val normalized = words.map { it.trim().lowercase() }
        val indices = normalized.map { wordIndex[it] ?: return false }

        // Re-assemble the 132-bit payload.
        val bits = BooleanArray(WORD_COUNT * BITS_PER_WORD)
        var pos = 0
        for (idx in indices) {
            for (bit in (BITS_PER_WORD - 1) downTo 0) {
                bits[pos++] = (idx ushr bit) and 1 == 1
            }
        }

        // Split into entropy (first 128 bits) + checksum (last 4 bits).
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BITS) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        val expected = checksumBits(entropy)
        return try {
            (0 until CHECKSUM_BITS).all { bits[ENTROPY_BITS + it] == expected[it] }
        } finally {
            java.util.Arrays.fill(entropy, 0)
        }
    }

    /**
     * Normalizes the mnemonic into the PBKDF2 password form: each word lowercased
     * and trimmed, joined by single spaces. Callers MUST wipe the returned
     * CharArray after derivation.
     */
    fun toSeedChars(words: List<String>): CharArray {
        val joined = words.joinToString(" ") { it.trim().lowercase() }
        return joined.toCharArray()
    }

    private fun entropyToWords(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "entropy must be $ENTROPY_BYTES bytes" }
        val checksum = checksumBits(entropy)

        // 128 entropy bits + 4 checksum bits = 132 bits.
        val bits = BooleanArray(ENTROPY_BITS + CHECKSUM_BITS)
        for (i in 0 until ENTROPY_BITS) {
            bits[i] = (entropy[i / 8].toInt() ushr (7 - (i % 8))) and 1 == 1
        }
        for (i in 0 until CHECKSUM_BITS) {
            bits[ENTROPY_BITS + i] = checksum[i]
        }

        val words = ArrayList<String>(WORD_COUNT)
        for (w in 0 until WORD_COUNT) {
            var index = 0
            for (b in 0 until BITS_PER_WORD) {
                index = (index shl 1) or if (bits[w * BITS_PER_WORD + b]) 1 else 0
            }
            words.add(wordList[index])
        }
        return words
    }

    /** First [CHECKSUM_BITS] bits of SHA-256(entropy). */
    private fun checksumBits(entropy: ByteArray): BooleanArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val out = BooleanArray(CHECKSUM_BITS)
        for (i in 0 until CHECKSUM_BITS) {
            out[i] = (hash[i / 8].toInt() ushr (7 - (i % 8))) and 1 == 1
        }
        return out
    }
}
