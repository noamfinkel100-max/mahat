package org.mahat.handlers;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * File encryption in one sentence: derive an AES key from the user's code, then encrypt the
 * file with AES-GCM.
 *
 * <p>The saved bytes are {@code header | salt | nonce | ciphertext | tag}. The header marks
 * the format, the salt makes the derived key different for each file, the nonce makes each
 * GCM encryption unique, and the tag detects a wrong code or changed data. Salt and nonce
 * are public; neither one is a secret.
 */
public final class Encryptor {
    private static final byte[] FILE_HEADER = "CYPNOD01".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int KEY_DERIVATION_ITERATIONS = 210_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    public static final long ENCRYPTION_OVERHEAD =
            FILE_HEADER.length + SALT_LENGTH + NONCE_LENGTH + TAG_LENGTH_BITS / 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // Keep the streaming provider local instead of changing the JVM-wide provider order.
    private static final Provider STREAMING_PROVIDER = new BouncyCastleProvider();

    private Encryptor() {}

    /** Encrypts a file without loading the whole file into memory or closing either stream. */
    public static void encryptTo(InputStream original, OutputStream output, String code)
            throws IOException, GeneralSecurityException {
        byte[] salt = randomBytes(SALT_LENGTH);
        byte[] nonce = randomBytes(NONCE_LENGTH);
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, code, salt, nonce);

        output.write(FILE_HEADER); // Identify the file format for future readers.
        output.write(salt);
        output.write(nonce);
        copyThroughCipher(original, output, cipher, false);
    }

    /**
     * Decrypts into a private output. The caller must not expose that output unless this
     * method returns: AES-GCM verifies the tag only after reading the complete file.
     */
    public static void decryptTo(InputStream encrypted, OutputStream privateOutput, String code)
            throws IOException, GeneralSecurityException {
        if (!Arrays.equals(FILE_HEADER, encrypted.readNBytes(FILE_HEADER.length))) {
            throw new UnsupportedEncryptedFileException();
        }
        byte[] salt = encrypted.readNBytes(SALT_LENGTH);
        byte[] nonce = encrypted.readNBytes(NONCE_LENGTH);
        if (salt.length != SALT_LENGTH || nonce.length != NONCE_LENGTH) {
            throw new UnsupportedEncryptedFileException();
        }

        copyThroughCipher(
                encrypted,
                privateOutput,
                createCipher(Cipher.DECRYPT_MODE, code, salt, nonce),
                true);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void copyThroughCipher(
            InputStream input, OutputStream output, Cipher cipher, boolean decrypt)
            throws IOException, GeneralSecurityException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long bytesRead = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                continue;
            }
            bytesRead += count;
            byte[] transformed = cipher.update(buffer, 0, count);
            if (transformed != null) {
                output.write(transformed);
            }
        }
        if (decrypt && bytesRead < TAG_LENGTH_BITS / 8) {
            throw new UnsupportedEncryptedFileException();
        }

        // GCM checks the authentication tag here; decryption fails if the code or data is wrong.
        output.write(cipher.doFinal());
    }

    private static Cipher createCipher(int mode, String code, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A non-empty encryption code is required.");
        }
        PBEKeySpec keySpec =
                new PBEKeySpec(
                        code.toCharArray(), salt, KEY_DERIVATION_ITERATIONS, KEY_LENGTH_BITS);
        byte[] keyBytes = null;
        try {
            SecretKey derivedKey =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec);
            keyBytes = derivedKey.getEncoded();
            Cipher cipher =
                    Cipher.getInstance("AES/GCM/NoPadding", STREAMING_PROVIDER);
            cipher.init(
                    mode,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher;
        } finally {
            keySpec.clearPassword();
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    public static class UnsupportedEncryptedFileException extends GeneralSecurityException {
        public UnsupportedEncryptedFileException() {
            super(
                    "This file uses an unsupported or incomplete encryption format; "
                            + "the original data has been preserved.");
        }
    }
}
