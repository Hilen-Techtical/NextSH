// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import fr.techtical.nextsh.shared.util.Logger
import net.schmizz.sshj.common.Buffer
import java.io.Closeable

private const val TAG = "SkSshSignature"

/** Taille d'une signature Ed25519 brute (R || S), telle que renvoyee par les authenticators. */
private const val ED25519_SIGNATURE_SIZE = 64

/** Taille maximale d'un scalaire ECDSA P-256 (r ou s), hors octet de remplissage 0x00 de l'mpint. */
private const val P256_SCALAR_MAX_SIZE = 32

/**
 * Encapsule une signature SSH FIDO2 (sk-*) obtenue d'un authenticator.
 *
 * [rawSignature] reste dans la forme brute que livre l'authenticator :
 *   - sk-ecdsa-sha2-nistp256 : signature ECDSA encodee en DER (SEQUENCE { INTEGER r, INTEGER s })
 *   - sk-ssh-ed25519 : 64 octets (R || S)
 *
 * Le format fil du champ signature envoye a OpenSSH est decrit une seule fois, dans
 * [encodeSignatureBlob].
 *
 * Securite : implemente [Closeable] pour wiper la signature brute.
 */
class SkSshSignature private constructor(
    val keyType: SkKeyType,
    val rawSignature: ByteArray,
    val flags: Byte,
    val counter: UInt,
) : Closeable {

    /**
     * Encode la signature complete au format attendu par OpenSSH (PROTOCOL.u2f), qui est le
     * contenu du champ "signature" d'une USERAUTH_REQUEST publickey (et d'une signature SSHSIG) :
     *   string key_type    (par ex. "sk-ecdsa-sha2-nistp256@openssh.com")
     *   string signature   (ECDSA : mpint r || mpint s, RFC 5656 ; Ed25519 : 64 octets)
     *   byte   flags       (bit 0 = user present, bit 2 = user verified)
     *   uint32 counter     (compteur de signatures de l'authenticator)
     *
     * L'appelant ecrit ce blob tel quel comme UNE seule string SSH. Surtout pas via
     * `Buffer.putSignature`, qui ajouterait un second niveau de prefixe de longueur : OpenSSH
     * repond alors "invalid format".
     *
     * La conversion ECDSA DER vers `mpint r || mpint s` (RFC 5656 section 3.1.2) est faite ici.
     * Le tableau retourne est une copie : l'appelant est responsable de le wiper apres usage.
     *
     * @throws IllegalArgumentException si [rawSignature] n'est pas une signature valide pour
     *         [keyType] (DER ECDSA malforme, ou longueur Ed25519 differente de 64 octets).
     */
    fun encodeSignatureBlob(): ByteArray {
        val signatureField: ByteArray = when (keyType) {
            SkKeyType.SK_ECDSA_256 -> ecdsaDerToSshMpints(rawSignature)
            SkKeyType.SK_ED25519 -> {
                require(rawSignature.size == ED25519_SIGNATURE_SIZE) {
                    "Ed25519 signature must be exactly $ED25519_SIGNATURE_SIZE bytes, got ${rawSignature.size}"
                }
                rawSignature
            }
        }
        try {
            val buf = Buffer.PlainBuffer()
            buf.putString(keyType.sshName)
            buf.putString(signatureField)
            buf.putByte(flags)
            buf.putUInt32(counter.toLong())
            return buf.compactData
        } finally {
            // Pour Ed25519 signatureField est rawSignature elle-meme : c'est a close() de la wiper.
            if (signatureField !== rawSignature) signatureField.fill(0)
        }
    }

    /**
     * Ancien encodage "inner" : `string raw_signature || byte flags || uint32 counter`.
     *
     * ATTENTION : ce n'est PAS le format fil d'OpenSSH. La signature ECDSA y reste en DER et le
     * key_type est ajoute par l'appelant, ce qui produit un double niveau de prefixe que OpenSSH
     * rejette. Il n'est conserve que pour l'ancien chemin SSHJ `SkFido2Signature` (Desktop), qui
     * n'est pas utilise pour l'authentification. Tout nouveau code doit utiliser
     * [encodeSignatureBlob].
     *
     * Format :
     *   string raw_signature
     *   byte   flags
     *   uint32 counter
     */
    @Deprecated("Not the OpenSSH wire format, use encodeSignatureBlob()")
    fun encodeInner(): ByteArray {
        val buf = Buffer.PlainBuffer()
        buf.putString(rawSignature)
        buf.putByte(flags)
        buf.putUInt32(counter.toLong())
        return buf.compactData
    }

    override fun close() {
        rawSignature.fill(0)
    }

    companion object {
        /** Bit 0 : l'utilisateur etait physiquement present */
        const val FLAG_USER_PRESENT: Byte = 0x01
        /** Bit 2 : l'utilisateur a ete verifie (biometrie/PIN) */
        const val FLAG_USER_VERIFIED: Byte = 0x04

        /**
         * Cree une signature FIDO2 validee.
         *
         * La forme de [rawSignature] n'est verifiee qu'a l'encodage ([encodeSignatureBlob]).
         *
         * @param keyType Type de cle sk-*
         * @param rawSignature Signature brute de l'authenticator (DER ECDSA ou Ed25519 64 bytes)
         * @param flags Byte de flags FIDO2 (user_present doit etre set)
         * @param counter Compteur de signatures de l'authenticator
         * @throws IllegalArgumentException si le flag user_present n'est pas set
         */
        fun create(
            keyType: SkKeyType,
            rawSignature: ByteArray,
            flags: Byte,
            counter: UInt,
        ): SkSshSignature {
            require(flags.toInt() and FLAG_USER_PRESENT.toInt() != 0) {
                "FIDO2 signature must have user_present flag set"
            }
            if (counter == 0u) {
                Logger.w(TAG, "FIDO2 signature counter is 0: authenticator may not support counters or is newly reset")
            }
            return SkSshSignature(keyType, rawSignature, flags, counter)
        }
    }
}

/**
 * Convertit une signature ECDSA P-256 DER (SEQUENCE { INTEGER r, INTEGER s }, RFC 3279 /
 * CTAP2) vers `mpint r || mpint s` (RFC 5656 section 3.1.2, RFC 4251 section 5), sans les
 * prefixes de longueur externes.
 *
 * Le parseur est strict sur la structure : tag SEQUENCE puis deux INTEGER, longueurs definies et
 * minimales, aucun octet en trop ni en moins, entiers strictement positifs (le test de signe porte
 * sur le premier octet d'origine), non nuls, et chaque scalaire tient sur 256 bits. Toute entree
 * malformee est rejetee par une [IllegalArgumentException] au message explicite.
 *
 * Il est en revanche tolerant sur les octets 0x00 redondants en tete d'un INTEGER. Le DER
 * canonique n'en met pas (CTAP2 l'impose), mais d'autres decodeurs (OpenSSL) les acceptent, donc
 * un authenticator ou une couche plateforme peu rigoureux (par exemple Windows Hello) pourrait en
 * laisser sans que personne ne le remarque, et sshd verifie la signature de toute facon. Ils
 * sont retires, puis chaque scalaire est reencode en mpint minimal : complement a deux minimal,
 * un seul 0x00 en tete uniquement si le bit de poids fort du premier octet est a 1.
 * Par exemple `02 02 00 05` donne l'mpint `05`.
 *
 * @param der signature DER telle que livree par l'authenticator
 * @return `string r_mpint_content || string s_mpint_content`, soit `mpint r || mpint s`
 */
internal fun ecdsaDerToSshMpints(der: ByteArray): ByteArray {
    fun fail(why: String): Nothing =
        throw IllegalArgumentException("Malformed ECDSA DER signature: $why")

    var pos = 0

    fun readLength(): Int {
        if (pos >= der.size) fail("truncated length")
        val first = der[pos++].toInt() and 0xFF
        if (first < 0x80) return first
        val lengthOctets = first and 0x7F
        if (lengthOctets == 0) fail("indefinite length is not allowed in DER")
        if (lengthOctets > 2) fail("length field too large")
        if (pos + lengthOctets > der.size) fail("truncated length")
        var length = 0
        repeat(lengthOctets) { length = (length shl 8) or (der[pos++].toInt() and 0xFF) }
        // Forme longue minimale : une longueur < 0x80 doit etre en forme courte,
        // une longueur < 0x100 doit tenir sur un seul octet.
        if (length < 0x80 || (lengthOctets == 2 && length < 0x100)) fail("non-minimal length encoding")
        return length
    }

    // Lit un INTEGER strictement positif et retourne (offset, longueur) de sa magnitude dans [der],
    // octets 0x00 redondants en tete exclus.
    fun readScalar(name: String): Pair<Int, Int> {
        if (pos >= der.size) fail("missing INTEGER $name")
        if (der[pos++] != 0x02.toByte()) fail("expected INTEGER for $name")
        val length = readLength()
        if (length == 0) fail("empty INTEGER $name")
        if (length > der.size - pos) fail("INTEGER $name overruns the signature")
        val start = pos
        val end = start + length
        pos = end
        // Le signe se lit sur le premier octet d'origine, avant toute normalisation.
        if ((der[start].toInt() and 0x80) != 0) fail("INTEGER $name is negative")
        var magnitudeStart = start
        while (magnitudeStart < end && der[magnitudeStart] == 0.toByte()) magnitudeStart++
        if (magnitudeStart == end) fail("INTEGER $name is zero")
        val magnitudeSize = end - magnitudeStart
        if (magnitudeSize > P256_SCALAR_MAX_SIZE) fail("INTEGER $name exceeds 256 bits")
        return magnitudeStart to magnitudeSize
    }

    if (der.isEmpty() || der[pos++] != 0x30.toByte()) fail("expected SEQUENCE")
    val sequenceLength = readLength()
    if (sequenceLength != der.size - pos) fail("SEQUENCE length does not match the input size")
    val (rStart, rSize) = readScalar("r")
    val (sStart, sSize) = readScalar("s")
    if (pos != der.size) fail("unexpected data after s")

    // mpint minimal : un 0x00 en tete si, et seulement si, le bit de poids fort est a 1.
    val rPad = if ((der[rStart].toInt() and 0x80) != 0) 1 else 0
    val sPad = if ((der[sStart].toInt() and 0x80) != 0) 1 else 0
    val rLength = rPad + rSize
    val sLength = sPad + sSize
    val out = ByteArray(4 + rLength + 4 + sLength)
    fun putU32(offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }
    putU32(0, rLength)
    der.copyInto(out, destinationOffset = 4 + rPad, startIndex = rStart, endIndex = rStart + rSize)
    putU32(4 + rLength, sLength)
    der.copyInto(out, destinationOffset = 8 + rLength + sPad, startIndex = sStart, endIndex = sStart + sSize)
    return out
}
