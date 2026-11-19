// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrubberTest {

    private fun scrub(text: String) = LogScrubber.scrub(text)

    private fun assertMasked(input: String, secret: String, token: String) {
        val out = scrub(input)
        assertFalse(out.contains(secret), "'$secret' must not survive in: $out")
        assertTrue(out.contains(token), "expected $token in: $out")
    }

    private fun assertUntouched(input: String) {
        assertEquals(input, scrub(input))
    }

    // ── IPv4 ─────────────────────────────────────────────────────────────────

    @Test
    fun `masks an IPv4 address`() {
        assertEquals("connect to <ip> failed", scrub("connect to 192.168.1.10 failed"))
    }

    @Test
    fun `masks an IPv4 address with its port`() {
        assertEquals("peer <ip> refused", scrub("peer 10.0.0.1:47731 refused"))
    }

    @Test
    fun `masks an IPv4 address at the end of a sentence`() {
        assertEquals("reached <ip>.", scrub("reached 172.16.0.254."))
    }

    @Test
    fun `masks an IPv4 address after an equal sign or a parenthesis`() {
        assertMasked("ip=10.1.2.3", "10.1.2.3", "<ip>")
        assertMasked("peer(10.1.2.3)", "10.1.2.3", "<ip>")
    }

    @Test
    fun `keeps versions and decimal numbers`() {
        assertUntouched("NextSH 1.0.0 on Android 14, gradle 8.11.1, ratio 3.14")
        assertUntouched("v0.21.0 build 999.1.1.1 is not an address")
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────────

    @Test
    fun `masks full and compressed IPv6 addresses`() {
        assertEquals("a <ip> b", scrub("a 2001:0db8:85a3:0000:0000:8a2e:0370:7334 b"))
        assertEquals("a <ip> b", scrub("a 2001:db8::1 b"))
        assertEquals("a <ip> b", scrub("a ::1 b"))
        assertEquals("a <ip> b", scrub("a fe80::1%wlan0 b"))
    }

    @Test
    fun `masks an IPv4 mapped IPv6 address`() {
        assertEquals("from <ip>", scrub("from ::ffff:192.168.1.1"))
    }

    @Test
    fun `masks a bracketed IPv6 address with its port`() {
        assertEquals("dial <ip> now", scrub("dial [2001:db8::42]:2222 now"))
    }

    @Test
    fun `keeps clock times and Kotlin method references`() {
        assertUntouched("started at 10:20:30, done at 10:20:31.250")
        assertUntouched("calling Logger::d and String::class")
        assertUntouched("timestamp 2026-10-12T10:30:00Z")
    }

    // ── Hosts ────────────────────────────────────────────────────────────────

    @Test
    fun `masks a dotted host with a port`() {
        assertEquals("open <host> ok", scrub("open srv01.corp:2222 ok"))
        assertEquals("open <host> ok", scrub("open localhost:47731 ok"))
    }

    @Test
    fun `masks a dotted host with a common or LAN suffix`() {
        assertEquals("resolve <host> failed", scrub("resolve nas.local failed"))
        assertEquals("resolve <host>.", scrub("resolve git.example.com."))
        assertMasked("box raspberrypi.lan", "raspberrypi", "<host>")
        assertMasked("site techtical.fr", "techtical", "<host>")
    }

    @Test
    fun `masks user at host`() {
        assertEquals("login <host> denied", scrub("login root@10.0.0.5 denied"))
        assertEquals("login <host> denied", scrub("login deploy@build-box denied"))
        assertEquals("login <host> denied", scrub("login git@git.example.com:22 denied"))
        assertEquals("mail <host>", scrub("mail jean.dupont@example.org"))
    }

    @Test
    fun `keeps SSH algorithm names and object hash codes`() {
        assertUntouched(
            "kex curve25519-sha256@libssh.org, cipher chacha20-poly1305@openssh.com, " +
                "key sk-ssh-ed25519@openssh.com, cert ssh-ed25519-cert-v01@openssh.com",
        )
        assertUntouched("holder java.lang.Object@6d06d69c")
    }

    @Test
    fun `keeps qualified class names and stack frames`() {
        assertUntouched("java.net.SocketTimeoutException: kotlinx.coroutines.JobCancellationException")
        assertUntouched("at fr.techtical.nextsh.shared.core.sync.JvmCredentialSyncRepository.apply(JvmCredentialSyncRepository.kt:279)")
        assertUntouched("at java.base/java.lang.Thread.run(Thread.java:840)")
        assertUntouched("at kotlinx.coroutines.internal.DispatchedContinuation.resumeWith(DispatchedContinuation.kt:46)")
        assertUntouched("at fr.techtical.nextsh.Foo\$bar\$1.invokeSuspend(Foo.kt:12)")
        assertUntouched("dispatcher Dispatchers.IO")
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    @Test
    fun `masks POSIX absolute and home paths`() {
        assertEquals("read <path> failed", scrub("read /home/thibaud/.ssh/id_ed25519 failed"))
        assertEquals("read <path>", scrub("read ~/.ssh/config"))
        assertEquals("(<path>)", scrub("(/data/user/0/fr.techtical.nextsh/files/vault.bin)"))
        assertEquals("file=<path>", scrub("file=/tmp/x.json"))
    }

    @Test
    fun `masks Windows drive paths including spaces in folder names`() {
        assertEquals("open <path> failed", scrub("open C:\\Users\\Jean Dupont\\AppData\\Roaming\\NextSH\\vault.json failed"))
        assertEquals("open <path>", scrub("open C:/Users/thibaud/keys"))
        assertMasked("jdk C:\\Program Files\\Android\\jbr", "Program", "<path>")
    }

    @Test
    fun `masks UNC paths`() {
        assertEquals("backup <path> done", scrub("backup \\\\nas\\share\\keys\\id_rsa done"))
    }

    @Test
    fun `an address or host after a slash loses its port`() {
        for (input in listOf("Connection to /192.168.1.10:2222 failed", "Connection to /srv.example.com:2222 failed")) {
            val out = scrub(input)
            for (leak in listOf("2222", "192.168", "srv")) assertFalse(out.contains(leak), out)
            assertTrue(out.startsWith("Connection to <") && out.endsWith("> failed"), out)
        }
        assertEquals("route <path> ok", scrub("route /var/10.0.0.1/x ok"))
    }

    @Test
    fun `masks Windows long and device paths`() {
        assertEquals("open <path> failed", scrub("open \\\\?\\C:\\Users\\Jean Dupont\\AppData\\x.json failed"))
        assertEquals("open <path>", scrub("open \\\\?\\UNC\\nas\\share\\keys"))
        assertEquals("pipe <path>", scrub("pipe \\\\.\\pipe\\nextsh"))
    }

    @Test
    fun `a last path segment with spaces is masked when it ends with an extension`() {
        assertEquals("open <path> failed", scrub("open C:\\Users\\me\\My Keys.txt failed"))
        assertEquals("read <path> now", scrub("read /home/u/id ed25519.pub now"))
        assertEquals("copy <path> failed after 3.5", scrub("copy /tmp/x failed after 3.5"))
    }

    @Test
    fun `masks non ASCII hosts and emails as a whole`() {
        assertEquals("server <host> unreachable", scrub("server münchen.de unreachable"))
        assertEquals("mail <host>", scrub("mail müller@host.de"))
        assertEquals("open <host> ok", scrub("open münchen.example.com:22 ok"))
        assertEquals("login <host>", scrub("login élodie@nas.local"))
    }

    @Test
    fun `an object hash code is kept only after a class name`() {
        assertEquals("<host> <host> <host>", scrub("ops@bed me@cafe git@dead"))
        assertUntouched("holder java.lang.Object@6d06d69c, Object@1a2b, Foo\$Bar@1a2b3c")
    }

    @Test
    fun `keeps slashes that are not paths`() {
        assertUntouched("transports HID:0, PC/SC:0, TCP/IP and/or 3/5 done")
        assertUntouched("a / b")
    }

    // ── URIs ─────────────────────────────────────────────────────────────────

    @Test
    fun `masks content file and network URIs`() {
        assertEquals(
            "picked <uri>",
            scrub("picked content://com.android.externalstorage.documents/document/primary%3ADownload%2Fhosts.json"),
        )
        assertEquals("picked <uri>", scrub("picked file:///sdcard/Download/backup.nextsh"))
        assertEquals("picked <uri>", scrub("picked file:/C:/Users/x/backup.nextsh"))
        assertEquals("get <uri> (timeout)", scrub("get https://192.168.1.2:47731/sync?token=abc (timeout)"))
        assertEquals("open <uri>", scrub("open sftp://root@srv:22/home/root"))
    }

    // ── Fingerprints ─────────────────────────────────────────────────────────

    @Test
    fun `masks SHA256 and MD5 SSH fingerprints`() {
        assertEquals("host key <fp> accepted", scrub("host key SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8 accepted"))
        assertEquals("legacy <fp>", scrub("legacy MD5:16:27:ac:a5:76:28:2d:36:63:1b:56:4d:eb:df:a6:48"))
        assertEquals("legacy <fp>", scrub("legacy 16:27:ac:a5:76:28:2d:36:63:1b:56:4d:eb:df:a6:48"))
    }

    @Test
    fun `masks a MAC address`() {
        assertEquals("nic <fp>", scrub("nic 00:1A:2b:3C:4d:5E"))
    }

    // ── PEM ──────────────────────────────────────────────────────────────────

    @Test
    fun `masks a PEM block`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals("key <pem> imported", scrub("key $pem imported"))
    }

    @Test
    fun `masks an unterminated PEM block to the end`() {
        assertEquals("cut <pem>", scrub("cut -----BEGIN RSA PRIVATE KEY-----\nMIIEow"))
    }

    // ── Ids, hex, base64 ─────────────────────────────────────────────────────

    @Test
    fun `masks a UUID`() {
        assertEquals("host <id> saved", scrub("host 550e8400-e29b-41d4-a716-446655440000 saved"))
        assertEquals("pwd:<id>", scrub("pwd:550E8400-E29B-41D4-A716-446655440000"))
    }

    @Test
    fun `masks long hex runs`() {
        assertEquals("hash <hex>", scrub("hash e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
        assertEquals("nonce <hex>", scrub("nonce 0x0123456789abcdef01"))
        assertEquals("key_<hex>", scrub("key_0123456789abcdef0123"))
    }

    @Test
    fun `keeps short hex codes and long decimal numbers`() {
        assertUntouched("makeCredential: HRESULT error 0x80090027 (NotSupportedError): key_ type rejected")
        assertUntouched("APDU SW 6A80, hash 6d06d69c, id deadbeef")
        assertUntouched("elapsed 1234567890123456789 ns, 1720000000000 ms, 42 entries, 4096 bytes")
    }

    @Test
    fun `masks long base64 runs`() {
        assertEquals(
            "pub ssh-ed25519 <b64>",
            scrub("pub ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"),
        )
        assertEquals("secret <b64>", scrub("secret q83vEjRWeJAbzd7v8KqZ3x9mN2LpQ4rT6sU8wY0zA1c="))
        assertEquals("tag <b64>", scrub("tag 3q2+7w8KqZ3x9mN2LpQ4rQ=="))
    }

    @Test
    fun `keeps long class names and lowercase algorithm names`() {
        assertUntouched("Ed25519PublicKeyParameters and JvmCredentialSyncRepository and SocketTimeoutException")
        assertUntouched("sk-ecdsa-sha2-nistp256-cert-v01 and hmac-sha2-256-etm and KEY_PREFIX_PRIVATE_KEY_PASSPHRASE")
    }

    // ── Assignments ──────────────────────────────────────────────────────────

    @Test
    fun `masks secret assignments`() {
        assertEquals("user password=<secret> rejected", scrub("user password=hunter2 rejected"))
        assertEquals("sharedSecret=<secret>, pin=<secret>", scrub("sharedSecret=AbC, pin=1234"))
        assertEquals("passphrase=\"<secret>\" ok", scrub("passphrase=\"two words\" ok"))
        assertEquals("{\"token\":\"<secret>\"}", scrub("{\"token\":\"eyJhbGciOi\"}"))
    }

    @Test
    fun `masks id assignments but keeps LogId values`() {
        assertEquals("syncId=<id>, skipped", scrub("syncId=pwd:host-42, skipped"))
        assertEquals("device_id=<id>", scrub("device_id=peer-B"))
        val logId = LogId.of("host-42")
        assertUntouched("id=$logId skipped")
        assertUntouched("skip (type=HOST_PASSWORD, id=$logId) now")
        assertUntouched("id=- skipped")
    }

    @Test
    fun `an id or personal value runs to the next delimiter, spaces included`() {
        assertEquals("syncId=<id>", scrub("syncId=pwd:host 42 skipped"))
        assertEquals(
            "Host(label=<value>, hostname=<value>, sshPort=<value>)",
            scrub("Host(label=Prod DB 1, hostname=srv01, sshPort=2222)"),
        )
        assertEquals("username=<value>", scrub("username=Jean Dupont"))
        assertEquals("user=<value> count=3 elapsedMs=12", scrub("user=Jean Dupont count=3 elapsedMs=12"))
        assertEquals("label=<value>\\nnext line", scrub("label=Prod DB\nnext line"))
    }

    // ── Value boundaries ─────────────────────────────────────────────────────

    @Test
    fun `a trailing errorClass idiom stays readable after a personal or id value`() {
        assertEquals("label=<value> (TransportException<-IOException)", scrub("label=Prod DB (TransportException<-IOException)"))
        assertEquals("user=<value> (IllegalStateException)", scrub("user=Jean Dupont (IllegalStateException)"))
        assertEquals("syncId=<id> (SyncCodecException<-...)", scrub("syncId=pwd:host 42 (SyncCodecException<-...)"))
    }

    @Test
    fun `parentheses inside a value do not end it`() {
        assertEquals("label=<value>, port=<value>", scrub("label=Prod (EU) DB, port=22"))
        assertEquals("user=<value>", scrub("user=John (Admin) Smith failed"))
        assertEquals("label=<value>", scrub("label=Prod (EU)"))
        assertEquals("Host(label=<value>, user=<value>)", scrub("Host(label=Prod (EU), user=root)"))
    }

    @Test
    fun `LogId values are kept bare or quoted`() {
        val host = LogId.of("3f2c9a52-6d0f-4c58-a0a0-1d2f3e4f5a6b")
        val device = LogId.of("device-uuid-1")
        assertUntouched("id=\"$host\" skipped")
        assertUntouched("{\"hostId\":\"$host\",\"n\":2}")
        assertUntouched("hostId='$host' type=ED25519")
        assertUntouched("SSH connected (password) hostId=$host")
        assertUntouched("Key generated: id=$host type=ED25519")
        assertUntouched("runSync: device=$device pushed=3 pulled=2 conflicts=0")
        assertUntouched("Credential decrypt failed (type=HOST_PASSWORD, id=$host), skipping (SyncCodecException<-AEADBadTagException)")
    }

    @Test
    fun `a second pass changes nothing, ids and escaped newlines included`() {
        val host = LogId.of("host-42")
        val inputs = listOf(
            "label=Prod DB\nuser=root",
            "syncId=pwd:host 42\nnext line id=$host",
            "user=John (Admin) Smith (IOException) count=3",
            "label=<value> next",
            "id=$host label=Web, hostname=srv01\tport=22",
        )
        for (input in inputs) {
            val once = scrub(input)
            assertEquals(once, scrub(once), "second pass must not change: $once")
            assertEquals(once, LogScrubber.clean(once), "clean after scrub must not change: $once")
        }
    }

    @Test
    fun `blanks between the separator and a secret are skipped`() {
        val inputs = listOf(
            "password=    hunter2",
            "\"password\":     \"hunter2\"",
            "password=\n\n\n\nhunter2",
            "password=\t\t\thunter2 ok",
            "token =        hunter2",
        )
        for (input in inputs) {
            assertTrue(LogScrubber.containsSecret(input), "not detected: $input")
            assertFalse(scrub(input).contains("hunter2"), scrub(input))
        }
    }

    // ── Nested and embedded assignments ──────────────────────────────────────

    private val embeddedSecrets = listOf(
        """{"auth":{"password":"hunter2"}}""",
        """{"cred":{"passphrase":"x"},"x":1}""",
        """{"data":[{"password":"hunter2"}]}""",
        "params=foo=bar&password=hunter2",
        "config=password=hunter2",
        """note="user said password=hunter2"""",
        "a= password=hunter2",
    )

    @Test
    fun `a secret nested in another value or object drops the line`() {
        for (input in embeddedSecrets) {
            assertTrue(LogScrubber.containsSecret(input), "not detected: $input")
            assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean(input), input)
        }
    }

    @Test
    fun `a secret nested in another value or object is masked by scrub`() {
        for (input in embeddedSecrets) {
            val out = scrub(input)
            assertFalse(out.contains("hunter2"), out)
            assertTrue(out.contains(LogScrubber.SECRET), out)
        }
        assertEquals("""{"auth":{"password":"<secret>"}}""", scrub("""{"auth":{"password":"hunter2"}}"""))
        assertEquals("""{"cred":{"passphrase":"<secret>"},"x":1}""", scrub("""{"cred":{"passphrase":"x"},"x":1}"""))
        assertEquals("params=foo=bar&password=<secret>", scrub("params=foo=bar&password=hunter2"))
        assertEquals("""note="user said password=<secret>"""", scrub("""note="user said password=hunter2""""))
        // Under a personal key such as msg the whole quoted text goes, the line is still dropped.
        assertEquals("""msg="<value>"""", scrub("""msg="user said password=hunter2""""))
        assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean("""msg="user said password=hunter2""""))
    }

    @Test
    fun `a cookie with an embedded pair drops the line`() {
        assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean("cookie=SESSIONID=abc123"))
        assertEquals("cookie=<secret>", scrub("cookie=SESSIONID=abc123"))
    }

    @Test
    fun `more secret key spellings are recognised`() {
        val inputs = listOf(
            "pass=hunter2", "userPass=hunter2", "secretKey=hunter2", "secret_key=hunter2", "awsSecretKey=hunter2",
            "sessionKey=hunter2", "session_key=hunter2", "authorization=hunter2", "x-auth-token=hunter2",
            "--password=hunter2", "private-key=hunter2", "{\"api-key\":\"hunter2\"}",
        )
        for (input in inputs) {
            assertTrue(LogScrubber.containsSecret(input), "not detected: $input")
            assertFalse(scrub(input).contains("hunter2"), scrub(input))
        }
    }

    @Test
    fun `key material and code names are secret keys`() {
        val keys = listOf(
            "masterKey", "access_key", "encryptionKey", "signingKey", "hmacKey", "sharedKey", "privKey",
            "vaultKey", "unlockKey", "recoveryCode", "pass_phrase", "pass-phrase", "dek", "wrappedKek",
            "wifiPsk", "pw", "pswd", "jwt", "sshpass", "AWS_SECRET_ACCESS_KEY",
        )
        for (key in keys) {
            val input = "$key=hunter2"
            assertTrue(LogScrubber.containsSecret(input), "not detected: $input")
            assertEquals("$key=<secret>", scrub(input))
        }
        assertUntouched("publicKey=ssh-ed25519 keks=3 rows=2 monkey=1")
    }

    @Test
    fun `X-API-Key and X-Auth-Token headers are masked like Authorization`() {
        assertEquals("X-API-Key: <secret>", scrub("X-API-Key: abc def"))
        assertEquals("sent X-Auth-Token:<secret>", scrub("sent X-Auth-Token:abc def"))
        assertTrue(LogScrubber.containsSecret("x-api-key: abc"))
    }

    @Test
    fun `the bare colon form drops the line for a secret name only`() {
        for (input in listOf("password: hunter2", "token: hunter2", "{password: hunter2}", "db_password: hunter2", "PIN: 1234")) {
            assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean(input), input)
            assertFalse(scrub(input).contains("hunter2"), scrub(input))
        }
        val neutral = listOf(
            "makeCredential: HRESULT error 0x80090027 (NotSupportedError): key_ type rejected",
            "storePassword: failed after 3 attempts",
            "retry pass: 2",
            "label: Prod DB",
            "token: null",
            "pwd:550e8400",
            "hasPassword: false",
        )
        for (input in neutral) {
            assertFalse(LogScrubber.containsSecret(input), "wrongly dropped: $input")
        }
    }

    @Test
    fun `escaped and dotted JSON keys are recognised`() {
        val escaped = """body="{\"password\":\"hunter2\",\"n\":1}""""
        assertTrue(LogScrubber.containsSecret(escaped))
        assertFalse(scrub(escaped).contains("hunter2"), scrub(escaped))
        assertTrue(scrub(escaped).contains("""\"password\":\"<secret>\""""), scrub(escaped))
        val dotted = """{"spring.datasource.password":"hunter2","spring.datasource.username":"app"}"""
        assertTrue(LogScrubber.containsSecret(dotted))
        assertEquals(
            """{"spring.datasource.password":"<secret>","spring.datasource.username":"<value>"}""",
            scrub(dotted),
        )
    }

    @Test
    fun `a key glued to a literal escape sequence is still a key`() {
        assertEquals("step 1\\nlabel=<value>", scrub("step 1\\nlabel=Prod DB"))
        assertEquals("\\tuser=<value>", scrub("\\tuser=root"))
        assertTrue(LogScrubber.containsSecret("line\\rpassword=hunter2"))
        assertTrue(LogScrubber.containsSecret("dir\\password=hunter2"))
    }

    @Test
    fun `HTTP auth headers are masked to the end of the line`() {
        assertEquals("Authorization: <secret>", scrub("Authorization: Bearer eyJhbGciOi.payload"))
        assertEquals("sent Cookie: <secret>\\nnext", scrub("sent Cookie: SID=abc; theme=dark\nnext"))
        assertEquals("authorization=<secret>", scrub("authorization=Basic dXNlcjpwYXNz"))
        assertTrue(LogScrubber.containsSecret("Proxy-Authorization: Basic dXNlcjpwYXNz"))
        assertTrue(LogScrubber.containsSecret("Set-Cookie: SID=abc"))
    }

    @Test
    fun `words that only end like a secret key stay neutral`() {
        assertUntouched("bypass=on compass=north spin=3 passes=2 mapping=x")
        assertFalse(LogScrubber.containsSecret("bypass=on compass=north spin=3 passes=2"))
    }

    @Test
    fun `flags and counts under a secret key keep the line`() {
        val flags = "credentials=3 passwords=12 tokens=0 secrets=4 needsPin=1 hasPassword=0 pin=true token=null"
        assertFalse(LogScrubber.containsSecret(flags))
        assertEquals(flags, LogScrubber.clean(flags))
        assertTrue(LogScrubber.containsSecret("pin=1234"))
        assertTrue(LogScrubber.containsSecret("tokens=abc"))
        assertTrue(LogScrubber.containsSecret("password=2"))
    }

    @Test
    fun `free text and device keys are personal, reason stays neutral`() {
        assertEquals("message=<value> (TransportException<-IOException)", scrub("message=Connect to srv01 failed (TransportException<-IOException)"))
        assertEquals("Error(msg=<value>, cause=<value>)", scrub("Error(msg=cannot open /x, cause=srv01 refused)"))
        assertEquals("errorMessage=<value>", scrub("errorMessage=host not found"))
        assertEquals("terminal=<value> reader=<value>", scrub("terminal=Thibaud phone reader=Yubico YubiKey OTP+FIDO+CCID 0"))
        assertUntouched("reason=TIMEOUT state=CLOSED")
    }

    @Test
    fun `name keys are personal unless they name a code or protocol element`() {
        assertEquals("tunnelName=<value>, keyName=<value>", scrub("tunnelName=Prod tunnel, keyName=work laptop"))
        assertEquals("name=<value>", scrub("name=Jean"))
        assertEquals("hostName=<value>", scrub("hostName=srv01"))
        assertUntouched("className=Foo methodName=bar transportName=HID algorithmName=ssh-ed25519 threadName=main")
    }

    @Test
    fun `scrub alone leaves no tail where clean would drop the line`() {
        val cases = mapOf(
            "password=ab,cd" to "password=<secret>",
            "secret={'a':'}','b':'leak'}" to "secret=<secret>",
            "password=Authorization: Bearer leak" to "password=<secret>",
            "password=ab\"cd leak\"" to "password=<secret>",
            "password=\"ab\"leak tail" to "password=<secret> tail",
            "Host(password=x, user=root)" to "Host(password=<secret>, user=<value>)",
            "{\"password\":leak,\"n\":1}" to "{\"password\":<secret>,\"n\":1}",
            "msg=\"secret={\"a\":\"leak\"} tail" to "msg=<value> tail",
            "msg=\"password=\n\nleak tail" to "msg=<value> tail",
        )
        for ((input, expected) in cases) {
            assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean(input), input)
            assertEquals(expected, scrub(input), input)
        }
    }

    @Test
    fun `a secret key with a structured value is masked as a whole`() {
        assertEquals("""{"password":<secret>,"n":2}""", scrub("""{"password":{"v":"hunter2","w":[1,2]},"n":2}"""))
        assertEquals("password=<secret>", scrub("password=[\"a\", \"b\""))
    }

    @Test
    fun `masks personal assignments`() {
        assertEquals("Host(label=<value>, hostname=<value>, sshPort=<value>, username=<value>)",
            scrub("Host(label=web, hostname=srv01, sshPort=2222, username=root)"))
        assertEquals("deviceName=<value>", scrub("deviceName=Thibaud-PC"))
    }

    @Test
    fun `keeps neutral assignments and boolean flags`() {
        assertUntouched("imported=3 skipped=1 transport=HID import=2 elapsedMs=12 code=401 valid=yes")
        assertUntouched("hasPassword=true token=null ghost=1 android=14")
    }

    // ── containsSecret / clean ───────────────────────────────────────────────

    @Test
    fun `containsSecret detects PEM and secret assignments only`() {
        assertTrue(LogScrubber.containsSecret("x -----BEGIN PRIVATE KEY----- y"))
        assertTrue(LogScrubber.containsSecret("password=hunter2"))
        assertTrue(LogScrubber.containsSecret("{\"passphrase\": \"x\"}"))
        assertTrue(LogScrubber.containsSecret("token = abc"))
        assertFalse(LogScrubber.containsSecret("passphrase mismatch on import"))
        assertFalse(LogScrubber.containsSecret("fido2 assertion failed, token missing"))
        assertFalse(LogScrubber.containsSecret("hasPassword=true"))
        assertFalse(LogScrubber.containsSecret("connect 10.0.0.1"))
    }

    @Test
    fun `clean drops a secret line and scrubs the others`() {
        assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean("auth password=hunter2"))
        assertEquals("connect <ip>", LogScrubber.clean("connect 10.0.0.1"))
    }

    // ── Control characters ───────────────────────────────────────────────────

    @Test
    fun `line breaks cannot forge a second log line`() {
        val out = scrub("import failed\nE/Vault: forged line\r\nW/x: y")
        assertEquals("import failed\\nE/Vault: forged line\\r\\nW/x: y", out)
        assertFalse(out.contains('\n') || out.contains('\r'))
        assertEquals("a\\nb", LogScrubber.clean("a\nb"))
    }

    @Test
    fun `terminal escapes and other controls are neutralised`() {
        val out = scrub("\u001B[31mred\u001B[0m tab\there nul\u0000 del\u007F c1\u0085 ls\u2028 rlo\u202Eevil")
        assertEquals("\\u001B[31mred\\u001B[0m tab here nul\\u0000 del\\u007F c1\\u0085 ls\\u2028 rlo\\u202Eevil", out)
        assertTrue(out.none { it.code < 0x20 || it.code in 0x7F..0x9F })
    }

    @Test
    fun `invisible format and tag characters are neutralised`() {
        val invisible = "a\u061Cb\u200Bc\u200Dd\u2060e\u2064f\uFEFFg"
        assertEquals("a\\u061Cb\\u200Bc\\u200Dd\\u2060e\\u2064f\\uFEFFg", scrub(invisible))
        // U+E0041 TAG LATIN CAPITAL LETTER A, a surrogate pair.
        val tagged = "ok" + String(Character.toChars(0xE0041)) + "end"
        assertEquals("ok\\uDB40\\uDC41end", scrub(tagged))
        // An emoji outside the tag block is printable and kept.
        val emoji = "done " + String(Character.toChars(0x1F600))
        assertUntouched(emoji)
    }

    @Test
    fun `printable non ASCII text is kept`() {
        assertUntouched("échec de l'import, réessai dans 3 s")
    }

    // ── Known limits ─────────────────────────────────────────────────────────
    //
    // Shapes the scrubber does not recognise on purpose or cannot tell apart. They pin the
    // current behaviour: call sites must not log such values in the first place.

    @Test
    fun `known limit, values without a recognisable shape survive`() {
        // A single label host, a relative path and a bare username have no shape.
        assertUntouched("connect myserver failed, key keys/id_rsa, as root")
        // The bare colon form only counts for a secret name: personal values stay.
        assertUntouched("label: Prod DB")
        // A camelCase name before a colon reads like a function prefix, not a secret key.
        assertFalse(LogScrubber.containsSecret("dbPassword: hunter2"))
        // Without a blank after the colon the form is not recognised, which keeps `pwd:host-42` ids readable.
        assertFalse(LogScrubber.containsSecret("password:hunter2"))
    }

    @Test
    fun `known limit, a literal backslash t before a key is read as an escaped tab`() {
        // `\token=x` is read as an escaped tab followed by `oken=x`.
        assertFalse(LogScrubber.containsSecret("\\token=hunter2"))
        assertTrue(LogScrubber.containsSecret("\\password=hunter2"))
    }

    @Test
    fun `known limit, a last path segment with spaces and no extension keeps its tail`() {
        assertEquals("open <path> notes", scrub("open C:\\x\\My notes"))
    }

    // ── Global properties ────────────────────────────────────────────────────

    @Test
    fun `scrub is idempotent`() {
        val inputs = listOf(
            "connect root@10.0.0.5:22 via /home/u/.ssh/id_rsa SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8",
            "password=hunter2 syncId=pwd:x label=web [2001:db8::1]:22 C:\\Users\\a b\\c",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 550e8400-e29b-41d4-a716-446655440000",
        )
        for (input in inputs) {
            val once = scrub(input)
            assertEquals(once, scrub(once), "second pass must not change: $once")
        }
    }

    @Test
    fun `keeps typical diagnostic lines verbatim`() {
        assertUntouched("detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC:0)")
        assertUntouched("Import complete: imported=3 skipped=1")
        assertUntouched("Sync OK in 125 ms, 12 entries, 3 conflicts (TransportException<-SocketTimeoutException)")
        assertUntouched("FIDO2 signature counter is 0: authenticator may not support counters or is newly reset")
        assertUntouched("LAN sync server listening on port 47731 (TLS, HMAC-SHA256, AES-256-GCM)")
        assertUntouched("")
    }

    @Test
    fun `pathological inputs are scrubbed in linear time`() {
        val inputs = listOf(
            "/" + "a ".repeat(8_000),
            "C:\\" + "a ".repeat(8_000),
            "\\\\" + "a\\".repeat(8_000),
            "a.".repeat(8_000) + ":1",
            "1:".repeat(8_000),
            "ab:".repeat(5_000),
            "-----BEGIN X----- ".repeat(800),
            "x=".repeat(8_000),
            "a@".repeat(8_000),
            "user=a ".repeat(3_000),
            "label=" + "a ".repeat(8_000),
            "password=" + "{".repeat(8_000),
            "\"k\":".repeat(4_000),
            "{\"password\":\"" + "x".repeat(8_000),
        )
        for (input in inputs) {
            val start = System.nanoTime()
            scrub(input)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsedMs < 5_000, "scrub took $elapsedMs ms on input starting with ${input.take(8)}")
        }
    }

    @Test
    fun `bounds very long input and drops the partial last word`() {
        val long = "word ".repeat(LogScrubber.MAX_INPUT_CHARS / 5 + 10) + "10.0.0.1"
        val out = scrub(long)
        assertTrue(out.endsWith(" [truncated]"))
        assertTrue(out.length <= LogScrubber.MAX_INPUT_CHARS + " [truncated]".length)
        assertFalse(out.contains("10.0.0"))
    }
}
