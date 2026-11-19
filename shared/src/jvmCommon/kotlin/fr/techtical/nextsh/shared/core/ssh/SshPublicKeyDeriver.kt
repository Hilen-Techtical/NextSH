// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.backup.SshPublicKeyLine
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.nist.NISTNamedCurves
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PBEParameter
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCS12PBEParams
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey as Asn1RsaPrivateKey
import org.bouncycastle.asn1.pkcs.RSAPublicKey as Asn1RsaPublicKey
import org.bouncycastle.asn1.sec.ECPrivateKey as Asn1EcPrivateKey
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.StringReader
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlin.math.abs

/**
 * The public key of an SSH private key, computed from the private key and PROVEN by a signature, for both
 * platforms: the backup restore, "Copy the public key" ([SshPublicKeyExportRules]) and the key import screen.
 *
 * Formats read: OpenSSH v1 (`BEGIN OPENSSH PRIVATE KEY`: Ed25519, RSA, ECDSA P-256/384/521, clear or encrypted
 * with bcrypt and a cipher SSHJ supports), PKCS#8 (`BEGIN PRIVATE KEY`, `BEGIN ENCRYPTED PRIVATE KEY` with
 * PBES2 and PBKDF2), PKCS#1 RSA and SEC1 EC (`BEGIN RSA PRIVATE KEY`, `BEGIN EC PRIVATE KEY`, clear or with the
 * OpenSSL `Proc-Type: 4,ENCRYPTED` header). An encrypted key with another scheme, PRF, cipher or key
 * derivation function is [SshPublicKeyDerivation.UnsupportedEncryption]; anything else is
 * [SshPublicKeyDerivation.Unsupported] (PuTTY, DSA, Ed25519 in PKCS#8, FIDO2 `sk-*` key files).
 *
 * How:
 * 1. size first: over 64 KiB of UTF-8 ([BackupLimits.MAX_SECRET_VALUE_BYTES]) gives `TooLarge`;
 * 2. the key is parsed with every length bounded before allocation (no `OutOfMemoryError` from a forged
 *    length; one is still caught, with `StackOverflowError`, and gives `Unreadable`);
 * 3. the cost of opening an encrypted key is checked before anything runs AND before any list of accepted
 *    algorithms (bcrypt rounds over [MAX_BCRYPT_ROUNDS], PBKDF2, PBES1 or PKCS#12 iterations over
 *    [MAX_PBKDF2_ITERATIONS]), so an unknown algorithm never hides a cost another reader would pay; RSA over
 *    [MAX_RSA_BITS] is not read, unbalanced RSA primes are refused;
 * 4. the public key is computed from the private key only: Ed25519 `A` from the seed, RSA `(n, e)`, ECDSA
 *    `Q = d G`; the public copies a key file carries are never trusted (SSHJ 0.38 takes the OpenSSH envelope
 *    as is). An OpenSSH envelope that differs gives [SshPublicKeyDerivation.Inconsistent], and so does the
 *    public key a PEM key pair embeds (SEC1 EC), which SSHJ presents at connection. RSA parts are checked for
 *    consistency (`p q = n` and the CRT values) before any signature;
 * 5. PROOF: 32 random bytes are signed with the private key and verified with the public key read back from
 *    the BYTES of the derived blob by a strict reader (never with the objects of the derivation), so a wrong
 *    encoding fails too. Only a verified key is returned.
 *
 * Cost: one signature per key (an RSA 16 384 key takes about a second on a phone), plus at most one key
 * derivation function when a passphrase is given. Run it off the main thread. At restore, run it without
 * passphrase, only where [fr.techtical.nextsh.shared.core.backup.BackupKeyRules.shouldDerive] says so, and
 * through [memoizedWithoutPassphrase]: a key repeated under many ids is derived once, and the proofs of large
 * RSA keys are bounded per restore ([ProofBudget]). This object itself has no budget (key import, copy).
 *
 * Thread safe. Never logs, never throws, never puts content of the key in a result or an exception. Wipes
 * the bytes it decodes or decrypts once done (a `String` or a `BigInteger` cannot be wiped).
 */
object SshPublicKeyDeriver : PublicKeyDeriver {

    /**
     * Maximum bcrypt rounds of an encrypted OpenSSH key (ssh-keygen uses 16 by default, `-a` sets more;
     * guides usually advise 64 to 128). Decision of Thibaud: SSHJ opens the key again, with the passphrase
     * kept in the vault, at every connection. Measured with ssh-keygen (C): reading a key written with
     * `-a 1000` takes 5.5 s, with `-a 100` 0.6 s, so about 1.4 s at this bound, more in Java and on a
     * phone. A key over it is refused at import: `ssh-keygen -p -a 100 -f <key>` encrypts it again.
     */
    const val MAX_BCRYPT_ROUNDS: Int = 256

    /**
     * Maximum iterations of an encrypted PKCS#8 key (OpenSSL writes 2 048 by default): PBKDF2, and the PBES1
     * and PKCS#12 schemes (refused anyway, but over this count they are [SshPublicKeyDerivation.TooCostly]).
     */
    const val MAX_PBKDF2_ITERATIONS: Int = 1_000_000

    /** Largest RSA modulus read, in bits (the OpenSSH ceiling). */
    const val MAX_RSA_BITS: Int = 16_384

    /** Largest RSA public exponent read, in bits. */
    private const val MAX_RSA_EXPONENT_BITS = 64

    /** Whitespace tolerated around a key before its UTF-8 size is checked. */
    private const val TRIM_MARGIN_CHARS = 4 * 1024

    private const val CHALLENGE_BYTES = 32
    private const val MAX_NAME_BYTES = 64
    private const val UNCOMPRESSED_POINT: Byte = 0x04
    private const val BEGIN = "-----BEGIN "
    private const val END = "-----END "

    private val random: SecureRandom by lazy { SecureRandom() }

    // A provider instance (never registered): decryption does not depend on the provider order of the app.
    private val bouncyCastle: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    override fun derive(privateKeyPem: String, passphrase: CharArray?): SshPublicKeyDerivation =
        derive(privateKeyPem, passphrase, budget = null)

    /** [derive] with the proofs bounded by [budget] (one per restore, see [memoizedWithoutPassphrase]); null: no bound. */
    internal fun derive(privateKeyPem: String, passphrase: CharArray?, budget: ProofBudget?): SshPublicKeyDerivation = try {
        deriveChecked(privateKeyPem, passphrase, budget)
    } catch (e: Exception) {
        SshPublicKeyDerivation.Unreadable
    } catch (e: OutOfMemoryError) {
        SshPublicKeyDerivation.Unreadable
    } catch (e: StackOverflowError) {
        // A deeply nested ASN.1 structure.
        SshPublicKeyDerivation.Unreadable
    }

    private fun deriveChecked(text: String, passphrase: CharArray?, budget: ProofBudget?): SshPublicKeyDerivation {
        if (text.length > BackupLimits.MAX_SECRET_VALUE_BYTES + TRIM_MARGIN_CHARS) return SshPublicKeyDerivation.TooLarge
        val pem = text.trim()
        if (pem.encodeToByteArray().size > BackupLimits.MAX_SECRET_VALUE_BYTES) return SshPublicKeyDerivation.TooLarge
        val lines = pem.lines().map { it.trim() }
        val begin = lines.indexOfFirst { it.startsWith(BEGIN) }
        if (begin < 0) {
            return if (pem.startsWith("PuTTY-User-Key-File-")) SshPublicKeyDerivation.Unsupported else SshPublicKeyDerivation.Unreadable
        }
        return when (lines[begin].removePrefix(BEGIN).substringBefore("-----")) {
            "OPENSSH PRIVATE KEY" -> deriveOpenSsh(lines, begin, passphrase, budget)
            "PRIVATE KEY", "ENCRYPTED PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY" -> derivePem(pem, passphrase, budget)
            else -> SshPublicKeyDerivation.Unsupported
        }
    }

    // ---- OpenSSH v1 ------------------------------------------------------------------------

    private fun deriveOpenSsh(lines: List<String>, begin: Int, passphrase: CharArray?, budget: ProofBudget?): SshPublicKeyDerivation {
        val end = (begin + 1 until lines.size).firstOrNull { lines[it].startsWith(END) }
            ?: return SshPublicKeyDerivation.Unreadable
        val base64 = buildString { for (i in begin + 1 until end) append(lines[i]) }
        val body = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return SshPublicKeyDerivation.Unreadable
        }
        try {
            return when (val read = OpenSshKeyV1Reader.read(body, passphrase)) {
                is OpenSshRead.Failed -> read.outcome
                is OpenSshRead.Read -> try {
                    when (val proven = prove(read.material, budget)) {
                        is SshPublicKeyDerivation.Derived ->
                            if (proven.blobBase64 == Base64.getEncoder().encodeToString(read.envelope)) proven
                            else SshPublicKeyDerivation.Inconsistent(proven)
                        else -> proven
                    }
                } finally {
                    read.material.wipe()
                }
            }
        } finally {
            body.fill(0) // the whole key file, private section included for a clear key
        }
    }

    // ---- PEM read by BouncyCastle (PKCS#8, PKCS#1, SEC1) ------------------------------------

    private fun derivePem(pem: String, passphrase: CharArray?, budget: ProofBudget?): SshPublicKeyDerivation {
        val parsed = PEMParser(StringReader(pem)).use { it.readObject() } ?: return SshPublicKeyDerivation.Unreadable
        var encrypted = false
        // The public key a `PEMKeyPair` carries (SEC1 EC keys may embed one): SSHJ presents it at connection.
        var embeddedPublic: SubjectPublicKeyInfo? = null
        val info: PrivateKeyInfo = when (parsed) {
            is PrivateKeyInfo -> parsed
            is PEMKeyPair -> {
                embeddedPublic = parsed.publicKeyInfo
                parsed.privateKeyInfo ?: return SshPublicKeyDerivation.Unreadable
            }
            is PKCS8EncryptedPrivateKeyInfo -> {
                pkcs8EncryptionProblem(parsed.encryptionAlgorithm)?.let { return it }
                if (passphrase == null || passphrase.isEmpty()) return SshPublicKeyDerivation.NeedsPassphrase
                encrypted = true
                try {
                    parsed.decryptPrivateKeyInfo(JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(bouncyCastle).build(passphrase))
                } catch (e: Exception) {
                    // Scheme, PRF and cost were checked above: what is left is the passphrase.
                    return SshPublicKeyDerivation.WrongPassphrase
                }
            }
            is PEMEncryptedKeyPair -> {
                // OpenSSL traditional encryption: one MD5 round, nothing to bound. An unknown cipher is
                // refused by its name before anything is decrypted, and never handed to another reader.
                if (!DEK_ALGORITHM.matches(parsed.dekAlgName.orEmpty())) return SshPublicKeyDerivation.UnsupportedEncryption
                if (passphrase == null || passphrase.isEmpty()) return SshPublicKeyDerivation.NeedsPassphrase
                encrypted = true
                val pair = try {
                    parsed.decryptKeyPair(JcePEMDecryptorProviderBuilder().setProvider(bouncyCastle).build(passphrase))
                } catch (e: Exception) {
                    return SshPublicKeyDerivation.WrongPassphrase
                }
                embeddedPublic = pair.publicKeyInfo
                pair.privateKeyInfo ?: return SshPublicKeyDerivation.WrongPassphrase
            }
            else -> return SshPublicKeyDerivation.Unsupported // a public key, a certificate, a DSA key...
        }
        val material = try {
            materialOf(info) ?: return SshPublicKeyDerivation.Unsupported
        } catch (e: Exception) {
            // Garbage after a decryption whose padding happened to be valid is a wrong passphrase.
            return if (encrypted) SshPublicKeyDerivation.WrongPassphrase else SshPublicKeyDerivation.Unreadable
        }
        try {
            val proven = prove(material, budget)
            val embedded = embeddedPublic
            if (proven is SshPublicKeyDerivation.Derived && embedded != null && !embeddedPublicMatches(embedded, material)) {
                return SshPublicKeyDerivation.Inconsistent(proven)
            }
            return proven
        } finally {
            material.wipe()
        }
    }

    /**
     * True when the public key [spki] that a PEM key pair carries is the public key of [material]. For a SEC1
     * EC key the point may be embedded (compressed or not): it is decoded on its curve and compared to `d G`.
     * A point that does not decode throws (the file is unreadable, SSHJ could not load it either).
     */
    private fun embeddedPublicMatches(spki: SubjectPublicKeyInfo, material: PrivateKeyMaterial): Boolean = when (material) {
        is PrivateKeyMaterial.Ecdsa -> {
            val x9 = NISTNamedCurves.getByName(material.curve.bcName)
            val embedded = x9.curve.decodePoint(spki.publicKeyData.octets).normalize()
            embedded == x9.g.multiply(material.d).normalize()
        }
        is PrivateKeyMaterial.Rsa -> {
            val k = Asn1RsaPublicKey.getInstance(spki.parsePublicKey())
            k.modulus == material.n && k.publicExponent == material.e
        }
        is PrivateKeyMaterial.Ed25519 -> true // never in a PEM key pair here
    }

    /**
     * Null when the encrypted PKCS#8 key can be opened here at a bounded cost, else why it is not opened.
     *
     * SECURITY: the cost comes BEFORE the lists. BouncyCastle (so SSHJ too) runs the iterations of PBKDF2, of the
     * PBES1 schemes and of the PKCS#12 schemes whatever their number, also with a PRF or a cipher this deriver
     * does not take. So the iteration count is bounded as soon as the parameters can be read
     * ([SshPublicKeyDerivation.TooCostly]), and only then an algorithm outside the lists gives
     * [SshPublicKeyDerivation.UnsupportedEncryption]: never handed to another reader.
     * PBES1 and PKCS#12 schemes are obsolete and never accepted; a non PBKDF2 function of PBES2 (scrypt) is not
     * run by the PKCS#8 reader of BouncyCastle either.
     */
    private fun pkcs8EncryptionProblem(algorithm: AlgorithmIdentifier): SshPublicKeyDerivation? {
        val oid = algorithm.algorithm
        return when {
            oid == PKCSObjectIdentifiers.id_PBES2 -> {
                val pbes2 = PBES2Parameters.getInstance(algorithm.parameters)
                val kdf = pbes2.keyDerivationFunc
                if (kdf.algorithm != PKCSObjectIdentifiers.id_PBKDF2) return SshPublicKeyDerivation.UnsupportedEncryption
                val params = PBKDF2Params.getInstance(kdf.parameters)
                iterationsProblem(params.iterationCount)
                    ?: if (pbes2.encryptionScheme.algorithm !in PBES2_SCHEMES || params.prf.algorithm !in PBKDF2_PRFS) {
                        SshPublicKeyDerivation.UnsupportedEncryption
                    } else {
                        null
                    }
            }
            oid in PBES1_SCHEMES ->
                iterationsProblem(PBEParameter.getInstance(algorithm.parameters).iterationCount)
                    ?: SshPublicKeyDerivation.UnsupportedEncryption
            oid.on(PKCSObjectIdentifiers.pkcs_12PbeIds) ->
                iterationsProblem(PKCS12PBEParams.getInstance(algorithm.parameters).iterations)
                    ?: SshPublicKeyDerivation.UnsupportedEncryption
            else -> SshPublicKeyDerivation.UnsupportedEncryption
        }
    }

    /** [SshPublicKeyDerivation.TooCostly] over [MAX_PBKDF2_ITERATIONS], [SshPublicKeyDerivation.Unreadable] under 1, else null. */
    private fun iterationsProblem(iterations: BigInteger): SshPublicKeyDerivation? = when {
        iterations.signum() <= 0 -> SshPublicKeyDerivation.Unreadable
        iterations > BigInteger.valueOf(MAX_PBKDF2_ITERATIONS.toLong()) -> SshPublicKeyDerivation.TooCostly
        else -> null
    }

    /** PBES1 schemes (PKCS#5 v1.5), whose iteration count BouncyCastle runs as it is. */
    private val PBES1_SCHEMES = setOf(
        PKCSObjectIdentifiers.pbeWithMD2AndDES_CBC,
        PKCSObjectIdentifiers.pbeWithMD2AndRC2_CBC,
        PKCSObjectIdentifiers.pbeWithMD5AndDES_CBC,
        PKCSObjectIdentifiers.pbeWithMD5AndRC2_CBC,
        PKCSObjectIdentifiers.pbeWithSHA1AndDES_CBC,
        PKCSObjectIdentifiers.pbeWithSHA1AndRC2_CBC,
    )

    /** PBES2 encryption schemes BouncyCastle opens: AES-CBC and 3DES-CBC. */
    private val PBES2_SCHEMES = setOf(
        NISTObjectIdentifiers.id_aes128_CBC,
        NISTObjectIdentifiers.id_aes192_CBC,
        NISTObjectIdentifiers.id_aes256_CBC,
        PKCSObjectIdentifiers.des_EDE3_CBC,
    )

    /** PBKDF2 pseudo random functions: HMAC with SHA-1 (the default when absent) and SHA-2. */
    private val PBKDF2_PRFS = setOf(
        PKCSObjectIdentifiers.id_hmacWithSHA1,
        PKCSObjectIdentifiers.id_hmacWithSHA224,
        PKCSObjectIdentifiers.id_hmacWithSHA256,
        PKCSObjectIdentifiers.id_hmacWithSHA384,
        PKCSObjectIdentifiers.id_hmacWithSHA512,
    )

    /** `DEK-Info` ciphers of the OpenSSL traditional format that BouncyCastle opens. */
    private val DEK_ALGORITHM =
        Regex("^(AES-(128|192|256)|DES|DES-EDE|DES-EDE3|BF|RC2|RC2-40|RC2-64)-(CBC|CFB|ECB|OFB)$|^DES-EDE3?$")

    /** The private key of [info], or null for an algorithm not read here. Throws when malformed. */
    private fun materialOf(info: PrivateKeyInfo): PrivateKeyMaterial? {
        val algorithm = info.privateKeyAlgorithm
        return when (algorithm.algorithm) {
            PKCSObjectIdentifiers.rsaEncryption -> {
                val k = Asn1RsaPrivateKey.getInstance(info.parsePrivateKey())
                PrivateKeyMaterial.Rsa(k.modulus, k.publicExponent, k.privateExponent, k.prime1, k.prime2, k.exponent1, k.exponent2, k.coefficient)
            }
            X9ObjectIdentifiers.id_ecPublicKey -> {
                // Named curves only. The optional public key of the structure is ignored: Q is recomputed.
                val oid = algorithm.parameters?.toASN1Primitive() as? ASN1ObjectIdentifier ?: return null
                val curve = EcCurve.byOid(oid.id) ?: return null
                PrivateKeyMaterial.Ecdsa(curve, Asn1EcPrivateKey.getInstance(info.parsePrivateKey()).key)
            }
            // Ed25519 (SSHJ 0.38 cannot read it in PKCS#8), DSA, RSA-PSS...
            else -> null
        }
    }

    // ---- derivation and proof --------------------------------------------------------------

    /**
     * The proven public key of [material], or why there is none (out of bounds, or the proof failed). With a
     * [budget], an RSA key over [ProofBudget.largeRsaBits] is proven only while the budget lasts, else
     * [SshPublicKeyDerivation.Unsupported] without any signature.
     */
    internal fun prove(material: PrivateKeyMaterial, budget: ProofBudget? = null): SshPublicKeyDerivation {
        val sshType: String
        val blob: ByteArray
        when (material) {
            is PrivateKeyMaterial.Ed25519 -> {
                if (material.seed.size != OpenSshKeyV1Reader.ED25519_KEY_BYTES) return SshPublicKeyDerivation.Unreadable
                sshType = OpenSshKeyV1Reader.ED25519
                val a = Ed25519PrivateKeyParameters(material.seed, 0).generatePublicKey().encoded
                blob = SshWireWriter().string(sshType).string(a).toByteArray()
            }
            is PrivateKeyMaterial.Rsa -> {
                if (material.n.bitLength() > MAX_RSA_BITS) return SshPublicKeyDerivation.Unsupported
                if (!material.isWellFormed()) return SshPublicKeyDerivation.Unreadable
                // Not proven, so a restore treats it like an encrypted key.
                if (budget != null && !budget.allowRsaProof(material.n.bitLength())) return SshPublicKeyDerivation.Unsupported
                sshType = OpenSshKeyV1Reader.RSA
                blob = SshWireWriter().string(sshType).mpint(material.e).mpint(material.n).toByteArray()
            }
            is PrivateKeyMaterial.Ecdsa -> {
                val x9 = NISTNamedCurves.getByName(material.curve.bcName)
                if (material.d.signum() <= 0 || material.d >= x9.n) return SshPublicKeyDerivation.Unreadable
                sshType = material.curve.sshType
                val q = x9.g.multiply(material.d).normalize().getEncoded(false)
                blob = SshWireWriter().string(sshType).string(material.curve.sshCurve).string(q).toByteArray()
            }
        }
        val challenge = ByteArray(CHALLENGE_BYTES).also { random.nextBytes(it) }
        budget?.countSignature()
        val signature = sign(material, challenge)
        if (!verify(material, blob, challenge, signature)) return SshPublicKeyDerivation.Unreadable
        val keyType = SshPublicKeyLine.keyTypeOf(sshType) ?: return SshPublicKeyDerivation.Unsupported
        return SshPublicKeyDerivation.Derived(keyType, sshType, Base64.getEncoder().encodeToString(blob))
    }

    private fun sign(material: PrivateKeyMaterial, challenge: ByteArray): ByteArray = when (material) {
        is PrivateKeyMaterial.Ed25519 -> Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(material.seed, 0))
            update(challenge, 0, challenge.size)
            generateSignature()
        }
        is PrivateKeyMaterial.Rsa -> Signature.getInstance(RSA_SIGNATURE).run {
            val spec = RSAPrivateCrtKeySpec(
                material.n, material.e, material.d, material.p, material.q, material.dP, material.dQ, material.qInv,
            )
            initSign(KeyFactory.getInstance("RSA").generatePrivate(spec))
            update(challenge)
            sign()
        }
        is PrivateKeyMaterial.Ecdsa -> Signature.getInstance(material.curve.signature).run {
            initSign(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(material.d, ecParameters(material.curve))))
            update(challenge)
            sign()
        }
    }

    /**
     * Verifies [signature] with the public key read back FROM THE BYTES of [blob] by a strict reader (never
     * with the objects the derivation computed): a wrong encoding of the blob (sign byte, point padding, curve
     * name) fails here. Standard JCA names only ("RSA", "EC"), so no provider registration is needed.
     */
    private fun verify(material: PrivateKeyMaterial, blob: ByteArray, challenge: ByteArray, signature: ByteArray): Boolean {
        val r = SshWireReader(blob)
        val type = r.text(MAX_NAME_BYTES)
        return when (material) {
            is PrivateKeyMaterial.Ed25519 -> {
                val a = r.string(OpenSshKeyV1Reader.ED25519_KEY_BYTES * 2)
                if (type != OpenSshKeyV1Reader.ED25519 || a.size != OpenSshKeyV1Reader.ED25519_KEY_BYTES || r.remaining() != 0) {
                    return false
                }
                Ed25519Signer().run {
                    init(false, Ed25519PublicKeyParameters(a, 0))
                    update(challenge, 0, challenge.size)
                    verifySignature(signature)
                }
            }
            is PrivateKeyMaterial.Rsa -> {
                val e = r.mpint(OpenSshKeyV1Reader.MAX_RSA_MPINT_BYTES)
                val n = r.mpint(OpenSshKeyV1Reader.MAX_RSA_MPINT_BYTES)
                if (type != OpenSshKeyV1Reader.RSA || r.remaining() != 0) return false
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                Signature.getInstance(RSA_SIGNATURE).run {
                    initVerify(publicKey)
                    update(challenge)
                    verify(signature)
                }
            }
            is PrivateKeyMaterial.Ecdsa -> {
                val curve = material.curve
                if (type != curve.sshType || r.text(MAX_NAME_BYTES) != curve.sshCurve) return false
                val point = r.string(curve.pointBytes)
                if (point.size != curve.pointBytes || point[0] != UNCOMPRESSED_POINT || r.remaining() != 0) return false
                val half = (point.size - 1) / 2
                val w = ECPoint(BigInteger(1, point.copyOfRange(1, 1 + half)), BigInteger(1, point.copyOfRange(1 + half, point.size)))
                val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(w, ecParameters(curve)))
                Signature.getInstance(curve.signature).run {
                    initVerify(publicKey)
                    update(challenge)
                    verify(signature)
                }
            }
        }
    }

    private fun ecParameters(curve: EcCurve): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(curve.jcaName))
            getParameterSpec(ECParameterSpec::class.java)
        }

    /**
     * Cheap checks run BEFORE any signature: sizes, primes of balanced sizes (at most [MAX_RSA_PRIME_GAP_BITS]
     * bits apart), `dP < p`, `dQ < q`, `qInv < p`, then `p q = n`. A forged key would otherwise cost seconds of
     * modular exponentiation: full size primes, or a tiny `q` (3) next to a `p` almost the size of `n`, which
     * passes `p q = n` and makes the exponentiation modulo `p` cost about four times a real key of that size.
     * No real tool (OpenSSH, OpenSSL, PuTTY) writes unbalanced primes.
     */
    internal fun PrivateKeyMaterial.Rsa.isWellFormed(): Boolean {
        val bits = n.bitLength()
        fun inRange(v: BigInteger) = v.signum() > 0 && v.bitLength() <= bits
        return bits > 0 && e.signum() > 0 && e.testBit(0) && e.bitLength() in 2..MAX_RSA_EXPONENT_BITS &&
            inRange(d) && inRange(p) && inRange(q) && inRange(dP) && inRange(dQ) && inRange(qInv) &&
            p.bitLength() >= 2 && q.bitLength() >= 2 &&
            abs(p.bitLength() - q.bitLength()) <= MAX_RSA_PRIME_GAP_BITS &&
            dP < p && dQ < q && qInv < p &&
            p.multiply(q) == n
    }

    /** Largest size difference between the two RSA primes, in bits. */
    private const val MAX_RSA_PRIME_GAP_BITS = 16

    private const val RSA_SIGNATURE = "SHA256withRSA"
}

/** A private key read from a key file, the input of the proof. Never printed. */
internal sealed class PrivateKeyMaterial {
    /** Overwrites what can be overwritten (the Ed25519 seed; a `BigInteger` cannot be). Call once the proof is done. */
    open fun wipe() {}

    class Ed25519(val seed: ByteArray) : PrivateKeyMaterial() {
        override fun wipe() = seed.fill(0)
    }

    class Rsa(
        val n: BigInteger,
        val e: BigInteger,
        val d: BigInteger,
        val p: BigInteger,
        val q: BigInteger,
        val dP: BigInteger,
        val dQ: BigInteger,
        val qInv: BigInteger,
    ) : PrivateKeyMaterial()

    class Ecdsa(val curve: EcCurve, val d: BigInteger) : PrivateKeyMaterial()

    override fun toString(): String = "PrivateKeyMaterial"
}

/** The ECDSA curves of SSH (RFC 5656), with their names in each world. */
internal enum class EcCurve(
    val sshType: String,
    val sshCurve: String,
    val bcName: String,
    val jcaName: String,
    val signature: String,
    /** Uncompressed point: 0x04 then X and Y. */
    val pointBytes: Int,
    val oid: String,
) {
    P256("ecdsa-sha2-nistp256", "nistp256", "P-256", "secp256r1", "SHA256withECDSA", 65, "1.2.840.10045.3.1.7"),
    P384("ecdsa-sha2-nistp384", "nistp384", "P-384", "secp384r1", "SHA384withECDSA", 97, "1.3.132.0.34"),
    P521("ecdsa-sha2-nistp521", "nistp521", "P-521", "secp521r1", "SHA512withECDSA", 133, "1.3.132.0.35"),
    ;

    companion object {
        fun bySshType(sshType: String): EcCurve? = entries.firstOrNull { it.sshType == sshType }

        fun byOid(oid: String): EcCurve? = entries.firstOrNull { it.oid == oid }
    }
}
