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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;

public final class Encryptor {
    private static final byte[] FILE_HEADER = "CYPNOD01".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int KEY_DERIVATION_ITERATIONS = 210_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    public static final long ENCRYPTION_OVERHEAD = FILE_HEADER.length + SALT_LENGTH + NONCE_LENGTH + TAG_LENGTH_BITS / 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // SunJCE buffers complete GCM plaintext on decrypt; BC processes it incrementally.
    // Pass a local provider instead of changing the JVM-wide provider order.
    private static final Provider STREAMING_PROVIDER = new BouncyCastleProvider();

    private Encryptor() {}

    /** Streams the existing CYPNOD01 format without closing either stream. */
    public static void encryptTo(InputStream original, OutputStream output, String code)
            throws IOException, GeneralSecurityException {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(nonce);
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, code, salt, nonce);
        output.write(FILE_HEADER);
        output.write(salt);
        output.write(nonce);
        transform(original, output, cipher, false);
    }

    /**
     * Streams into a private temporary output. The caller MUST discard that output on
     * any failure and MUST NOT expose plaintext until this method returns successfully:
     * GCM authentication is checked by doFinal after the last ciphertext byte.
     * Neither stream is closed here. No legacy/plaintext fallback is attempted.
     */
    public static void decryptTo(InputStream encrypted, OutputStream privateOutput, String code)
            throws IOException, GeneralSecurityException {
        byte[] header = encrypted.readNBytes(FILE_HEADER.length);
        byte[] salt = encrypted.readNBytes(SALT_LENGTH);
        byte[] nonce = encrypted.readNBytes(NONCE_LENGTH);
        if (!Arrays.equals(FILE_HEADER, header) || salt.length != SALT_LENGTH || nonce.length != NONCE_LENGTH) {
            throw new UnsupportedEncryptedFileException();
        }
        transform(encrypted, privateOutput, createCipher(Cipher.DECRYPT_MODE, code, salt, nonce), true);
    }

    /** Compatibility helper; the caller must close the returned temporary-file stream. */
    public static InputStream encrypt(InputStream original, String code) throws IOException, GeneralSecurityException {
        return temporaryResult(original, code, true);
    }

    /** Returns plaintext only after complete authentication; close the returned stream. */
    public static InputStream decrypt(InputStream encrypted, String code) throws IOException, GeneralSecurityException {
        return temporaryResult(encrypted, code, false);
    }

    private static InputStream temporaryResult(InputStream input, String code, boolean encrypt)
            throws IOException, GeneralSecurityException {
        Path temporary = Files.createTempFile("cyphernod-crypto-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                if (encrypt) encryptTo(input, output, code);
                else decryptTo(input, output, code);
            }
            return Files.newInputStream(temporary, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void transform(InputStream input, OutputStream output, Cipher cipher, boolean decrypt)
            throws IOException, GeneralSecurityException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            total += count;
            byte[] transformed = cipher.update(buffer, 0, count);
            if (transformed != null) output.write(transformed);
        }
        if (decrypt && total < TAG_LENGTH_BITS / 8) throw new UnsupportedEncryptedFileException();
        // Do not use CipherInputStream: some implementations obscure authentication failures.
        output.write(cipher.doFinal());
    }

    private static Cipher createCipher(int mode, String code, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A non-empty encryption code is required.");
        }
        char[] password = code.toCharArray();
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, KEY_DERIVATION_ITERATIONS, KEY_LENGTH_BITS);
        Arrays.fill(password, '\0');
        byte[] keyBytes = null;
        try {
            SecretKey derivedKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec);
            keyBytes = derivedKey.getEncoded();
            SecretKeySpec aesKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", STREAMING_PROVIDER);
            cipher.init(mode, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher;
        } finally {
            keySpec.clearPassword();
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    public static class UnsupportedEncryptedFileException extends GeneralSecurityException {
        public UnsupportedEncryptedFileException() {
            super("This file uses an unsupported or incomplete encryption format; the original data has been preserved.");
        }
    }
}
