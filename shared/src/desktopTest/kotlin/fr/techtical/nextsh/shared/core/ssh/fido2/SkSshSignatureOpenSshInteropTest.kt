// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import net.schmizz.sshj.common.Buffer
import org.junit.Assume
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Test d'interoperabilite : des signatures SSHSIG sk-* produites avec l'encodeur de PRODUCTION
 * ([SkSshSignature.encodeSignatureBlob], [SkSshPublicKey.toBlob]) a partir d'un authenticator
 * logiciel doivent etre acceptees par `ssh-keygen -Y check-novalidate` d'OpenSSH.
 *
 * C'est le meme verifieur de signature sk-* que celui d'un serveur sshd : si ssh-keygen repond
 * `Good "file" signature`, le blob (layout PROTOCOL.u2f, ECDSA en mpint r || mpint s) est correct.
 *
 * Le test est ignore (JUnit Assume) si aucun ssh-keygen compatible n'est trouve sur la machine
 * (PATH, Git for Windows, OpenSSH de Windows), sauf quand la variable d'environnement CI vaut
 * "true" : il echoue alors, pour que ce garde-fou ne disparaisse pas silencieusement de la CI.
 * Quand plusieurs binaires sont trouves, chacun est exerce.
 */
class SkSshSignatureOpenSshInteropTest {

    private val workDir: File = Files.createTempDirectory("nextsh-sk-interop").toFile()

    @AfterTest
    fun cleanup() {
        workDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- ssh-keygen discovery

    private fun candidateBinaries(): List<File> {
        val names = listOf("ssh-keygen.exe", "ssh-keygen")
        val fromPath = (System.getenv("PATH") ?: "")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.map { File(dir, it) } }
        val fixed = buildList {
            add(File("C:\\Program Files\\Git\\usr\\bin\\ssh-keygen.exe"))
            System.getenv("SystemRoot")?.let { add(File(it, "System32\\OpenSSH\\ssh-keygen.exe")) }
        }
        return (fromPath + fixed)
            .filter { it.isFile && it.canExecute() }
            .distinctBy { it.canonicalPath.lowercase() }
    }

    /** Resultat d'un appel ; `timedOut` distingue un blocage d'un vrai refus (code de sortie non nul). */
    private class Run(val exitCode: Int, val output: String, val timedOut: Boolean = false)

    private fun run(exe: File, args: List<String>, stdin: File? = null): Run {
        val out = File(workDir, "out-${System.nanoTime()}.txt")
        val pb = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .redirectOutput(out)
        if (stdin != null) pb.redirectInput(stdin)
        val process = pb.start()
        if (stdin == null) process.outputStream.close()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Run(-1, "TIMEOUT", timedOut = true)
        }
        return Run(process.exitValue(), out.readText(Charsets.UTF_8).replace("\r", "").trim())
    }

    /** Binaires ssh-keygen utilisables : ceux dont l'aide mentionne `check-novalidate` (OpenSSH 8.1+). */
    private fun usableSshKeygens(): List<File> =
        candidateBinaries().filter { exe ->
            try {
                run(exe, listOf("-?")).output.contains("check-novalidate")
            } catch (_: Exception) {
                false
            }
        }

    private fun requireSshKeygens(): List<File> {
        val found = usableSshKeygens()
        if (found.isEmpty() && System.getenv("CI") == "true") {
            // Sur la CI on echoue plutot que d'ignorer : ce test est le garde-fou du format fil.
            fail("No ssh-keygen with 'check-novalidate' found on this CI runner: install OpenSSH 8.1+ so the interop guard runs")
        }
        Assume.assumeTrue("No ssh-keygen with 'check-novalidate' found, interop test skipped", found.isNotEmpty())
        println("ssh-keygen interop against: " + found.joinToString { it.path })
        return found
    }

    // ---------------------------------------------------------------- authenticateur logiciel

    /** Authenticator FIDO2 logiciel : signe `authData || clientDataHash` comme le ferait une cle materielle. */
    private class SoftAuthenticator(val keyType: SkKeyType) {
        private val application = "ssh:"
        private val keyPair = when (keyType) {
            SkKeyType.SK_ECDSA_256 ->
                KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            SkKeyType.SK_ED25519 -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        }

        val publicKey: SkSshPublicKey = SkSshPublicKey(keyType, rawPublicKey(), application)

        private fun rawPublicKey(): ByteArray = when (keyType) {
            SkKeyType.SK_ECDSA_256 -> {
                val w = (keyPair.public as ECPublicKey).w
                byteArrayOf(0x04) + fixed32(w.affineX) + fixed32(w.affineY)
            }
            // X.509 SubjectPublicKeyInfo d'une cle Ed25519 : 12 octets d'en-tete puis les 32 octets bruts.
            SkKeyType.SK_ED25519 -> keyPair.public.encoded.takeLast(32).toByteArray()
        }

        private fun fixed32(v: BigInteger): ByteArray {
            val b = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            return ByteArray(32 - b.size) + b
        }

        fun authData(flags: Byte, counter: UInt): ByteArray =
            sha256(application.toByteArray()) + byteArrayOf(flags) + u32(counter.toLong())

        /** Signature brute telle que livree par un authenticator : DER pour ECDSA, 64 octets pour Ed25519. */
        fun sign(data: ByteArray): ByteArray = when (keyType) {
            SkKeyType.SK_ECDSA_256 -> Signature.getInstance("SHA256withECDSA").apply {
                initSign(keyPair.private)
                update(data)
            }.sign()
            SkKeyType.SK_ED25519 -> Signature.getInstance("Ed25519").apply {
                initSign(keyPair.private)
                update(data)
            }.sign()
        }
    }

    // ---------------------------------------------------------------- SSHSIG (PROTOCOL.sshsig)

    companion object {
        private fun u32(v: Long) =
            byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

        private fun str(b: ByteArray) = u32(b.size.toLong()) + b
        private fun str(s: String) = str(s.toByteArray(Charsets.UTF_8))
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        private fun sha512(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b)

        private const val MIN_ECDSA_ITERATIONS = 6
        private const val MAX_ECDSA_ITERATIONS = 200
    }

    private val flags: Byte = SkSshSignature.FLAG_USER_PRESENT
    private val counter = 7u

    private fun signedData(namespace: String, message: ByteArray): ByteArray =
        "SSHSIG".toByteArray() + str(namespace) + str("") + str("sha512") + str(sha512(message))

    /** Enveloppe SSHSIG armoree autour d'un blob de signature sk-*. */
    private fun armor(publicKeyBlob: ByteArray, signatureBlob: ByteArray, namespace: String): String {
        val raw = "SSHSIG".toByteArray() + u32(1) + str(publicKeyBlob) + str(namespace) +
            str("") + str("sha512") + str(signatureBlob)
        val b64 = Base64.getEncoder().encodeToString(raw)
        return "-----BEGIN SSH SIGNATURE-----\n" + b64.chunked(70).joinToString("\n") +
            "\n-----END SSH SIGNATURE-----\n"
    }

    /** Signature produite par l'encodeur de PRODUCTION. */
    private fun productionSignatureBlob(auth: SoftAuthenticator, namespace: String, message: ByteArray): ByteArray {
        val clientDataHash = sha256(signedData(namespace, message))
        val raw = auth.sign(auth.authData(flags, counter) + clientDataHash)
        val signature = SkSshSignature.create(auth.keyType, raw, flags, counter)
        try {
            return signature.encodeSignatureBlob()
        } finally {
            signature.close()
        }
    }

    private fun writeSignature(name: String, auth: SoftAuthenticator, blob: ByteArray, namespace: String = "file"): File {
        val pubBlob = auth.publicKey.toBlob()
        return File(workDir, name).apply { writeText(armor(pubBlob, blob, namespace)) }
    }

    private fun check(exe: File, sigFile: File, messageFile: File, namespace: String = "file"): Run =
        run(exe, listOf("-Y", "check-novalidate", "-n", namespace, "-s", sigFile.absolutePath), stdin = messageFile)

    private fun messageFile(content: String, name: String = "message-${System.nanoTime()}.txt"): File =
        File(workDir, name).apply { writeBytes(content.toByteArray()) }

    private fun assertGood(label: String, r: Run) {
        assertFalse(r.timedOut, "$label: ssh-keygen timed out")
        assertEquals(0, r.exitCode, "$label: ssh-keygen refused the signature: ${r.output}")
        assertTrue(r.output.contains("Good \"file\" signature"), "$label: unexpected output: ${r.output}")
    }

    /** Un refus doit venir de la verification, pas du format : sinon le controle ne prouve rien. */
    private fun assertVerificationFailure(label: String, r: Run) {
        // Un timeout donne aussi un code de sortie non nul : il ne doit jamais passer pour un refus.
        assertFalse(r.timedOut, "$label: ssh-keygen timed out, that is not a refusal")
        assertNotEquals(0, r.exitCode, "$label: ssh-keygen must refuse this signature, output: ${r.output}")
        assertFalse(r.output.contains("invalid format"), "$label: refused for a format reason: ${r.output}")
    }

    /**
     * Indique si r et s du blob ECDSA portent un octet 0x00 de remplissage en tete (bit de poids
     * fort a 1). Un mpint positif minimal ne commence par 0x00 que dans ce cas.
     */
    private fun ecdsaPadding(blob: ByteArray): Pair<Boolean, Boolean> {
        val buf = Buffer.PlainBuffer(blob)
        buf.readString()
        val field = Buffer.PlainBuffer(buf.readStringAsBytes())
        val r = field.readStringAsBytes()
        val s = field.readStringAsBytes()
        return (r[0] == 0.toByte()) to (s[0] == 0.toByte())
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `ssh-keygen accepts sk-ecdsa signatures built by the production encoder`() {
        val exes = requireSshKeygens()
        val auth = SoftAuthenticator(SkKeyType.SK_ECDSA_256)
        // On continue tant que ssh-keygen n'a pas valide au moins un r et au moins un s portant le
        // 0x00 de remplissage (le cas qui casse un encodeur naif), dans la limite de 200 essais.
        var sawPaddedR = false
        var sawPaddedS = false
        var i = 0
        while ((i < MIN_ECDSA_ITERATIONS || !(sawPaddedR && sawPaddedS)) && i < MAX_ECDSA_ITERATIONS) {
            val message = "ecdsa message $i\n"
            val msgFile = messageFile(message)
            val blob = productionSignatureBlob(auth, "file", message.toByteArray())
            val sigFile = writeSignature("ecdsa-$i.sig", auth, blob)
            for (exe in exes) assertGood("sk-ecdsa #$i [${exe.path}]", check(exe, sigFile, msgFile))
            val (paddedR, paddedS) = ecdsaPadding(blob)
            sawPaddedR = sawPaddedR || paddedR
            sawPaddedS = sawPaddedS || paddedS
            i++
        }
        assertTrue(sawPaddedR, "no signature with a padded r was verified by ssh-keygen after $i signatures")
        assertTrue(sawPaddedS, "no signature with a padded s was verified by ssh-keygen after $i signatures")
    }

    @Test
    fun `ssh-keygen accepts sk-ed25519 signatures built by the production encoder`() {
        val exes = requireSshKeygens()
        val auth = SoftAuthenticator(SkKeyType.SK_ED25519)
        repeat(2) { i ->
            val message = "ed25519 message $i\n"
            val msgFile = messageFile(message)
            val blob = productionSignatureBlob(auth, "file", message.toByteArray())
            val sigFile = writeSignature("ed25519-$i.sig", auth, blob)
            for (exe in exes) assertGood("sk-ed25519 #$i [${exe.path}]", check(exe, sigFile, msgFile))
        }
    }

    @Test
    fun `negative control - a tampered message is refused for both key types`() {
        val exes = requireSshKeygens()
        for (type in SkKeyType.entries) {
            val auth = SoftAuthenticator(type)
            val message = "original message\n"
            val blob = productionSignatureBlob(auth, "file", message.toByteArray())
            val sigFile = writeSignature("tamper-${type.name}.sig", auth, blob)
            val goodFile = messageFile(message)
            val tamperedFile = messageFile("original message!\n")
            for (exe in exes) {
                assertGood("$type untampered [${exe.path}]", check(exe, sigFile, goodFile))
                assertVerificationFailure("$type tampered message [${exe.path}]", check(exe, sigFile, tamperedFile))
            }
        }
    }

    @Test
    fun `negative control - a wrong namespace or an altered counter is refused`() {
        val exes = requireSshKeygens()
        for (type in SkKeyType.entries) {
            val auth = SoftAuthenticator(type)
            val message = "namespace and counter\n"
            val msgFile = messageFile(message)
            val blob = productionSignatureBlob(auth, "file", message.toByteArray())

            val sigFile = writeSignature("ns-${type.name}.sig", auth, blob)
            // Le counter fait partie de authData signe : le modifier doit casser la verification.
            val altered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            val alteredFile = writeSignature("counter-${type.name}.sig", auth, altered)

            for (exe in exes) {
                assertGood("$type baseline [${exe.path}]", check(exe, sigFile, msgFile))
                assertVerificationFailure("$type wrong namespace [${exe.path}]", check(exe, sigFile, msgFile, "other"))
                assertVerificationFailure("$type altered counter [${exe.path}]", check(exe, alteredFile, msgFile))
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `negative control - the legacy double-prefixed layout is refused`() {
        val exes = requireSshKeygens()
        for (type in SkKeyType.entries) {
            val auth = SoftAuthenticator(type)
            val message = "legacy layout\n"
            val msgFile = messageFile(message)
            val clientDataHash = sha256(signedData("file", message.toByteArray()))
            val raw = auth.sign(auth.authData(flags, counter) + clientDataHash)
            val signature = SkSshSignature.create(type, raw, flags, counter)
            // Ce que SkAuthMethod envoyait avant la correction : putSignature(type, encodeInner()),
            // soit string(type) || string(string(raw) || flags || counter), DER ECDSA non converti.
            val legacy = Buffer.PlainBuffer().putSignature(type.sshName, signature.encodeInner()).compactData
            val legacyBlob = Buffer.PlainBuffer(legacy).readBytes()
            val sigFile = writeSignature("legacy-${type.name}.sig", auth, legacyBlob)
            for (exe in exes) {
                val r = check(exe, sigFile, msgFile)
                assertFalse(r.timedOut, "$type legacy layout [${exe.path}]: ssh-keygen timed out")
                assertNotEquals(0, r.exitCode, "$type legacy layout must be refused [${exe.path}]: ${r.output}")
                // Prouve le bug exact : le refus vient du format, pas d'une verification cryptographique.
                assertTrue(
                    r.output.contains("invalid format"),
                    "$type legacy layout [${exe.path}] must be refused as invalid format: ${r.output}",
                )
            }
        }
    }
}
