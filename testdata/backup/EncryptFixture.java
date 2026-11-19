import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Neutral .nextsh fixture encryptor (SunJCE), independent of the Android and Desktop code.
 * Usage: java EncryptFixture.java <in.json> <out.nextsh>
 * Frame: [SALT_LEN(4 BE)][SALT(32)][IV_LEN(1)][IV(12)][CIPHERTEXT||TAG(16)],
 * key = PBKDF2WithHmacSHA256(passphrase, salt, 210000, 256), AES/GCM/NoPadding.
 * The test passphrase is public and protects nothing: "Fixture-vault-" + U+00E9 + "-2026".
 */
public class EncryptFixture {
    static final String PASS = "Fixture-vault-" + (char) 0x00E9 + "-2026";

    public static void main(String[] args) throws Exception {
        byte[] plain = Files.readAllBytes(Path.of(args[0]));
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[32];
        rnd.nextBytes(salt);
        byte[] iv = new byte[12];
        rnd.nextBytes(iv);
        byte[] key = derive(salt);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(4).putInt(salt.length).array());
        out.write(salt);
        out.write(iv.length);
        out.write(iv);
        out.write(ct);
        byte[] file = out.toByteArray();
        Files.write(Path.of(args[1]), file);
        // Round trip check.
        byte[] back = decrypt(file);
        if (!Arrays.equals(back, plain)) throw new IllegalStateException("round trip mismatch");
        System.out.println("ok " + args[1] + " " + file.length + " bytes");
    }

    static byte[] derive(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(PASS.toCharArray(), salt, 210_000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    static byte[] decrypt(byte[] file) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(file);
        int saltLen = b.getInt();
        byte[] salt = new byte[saltLen];
        b.get(salt);
        int ivLen = b.get() & 0xFF;
        byte[] iv = new byte[ivLen];
        b.get(iv);
        byte[] ct = new byte[b.remaining()];
        b.get(ct);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(salt), "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }
}
