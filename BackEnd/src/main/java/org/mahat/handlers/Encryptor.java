package org.mahat.handlers;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public class Encryptor {
    private static final byte[] FILE_HEADER = "CYPNOD01".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int KEY_DERIVATION_ITERATIONS = 210_000;
    public static final long ENCRYPTION_OVERHEAD = FILE_HEADER.length + SALT_LENGTH + NONCE_LENGTH + TAG_LENGTH_BITS / 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static InputStream encrypt(InputStream original, String code) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(nonce);

        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, code, salt, nonce);
        byte[] encrypted = cipher.doFinal(original.readAllBytes());
        int headerLength = FILE_HEADER.length + SALT_LENGTH + NONCE_LENGTH;
        ByteBuffer output = ByteBuffer.allocate(Math.addExact(headerLength, encrypted.length));
        output.put(FILE_HEADER).put(salt).put(nonce).put(encrypted);
        return new ByteArrayInputStream(output.array());
    }

    public static InputStream decrypt(InputStream encrypted, String code) throws IOException, GeneralSecurityException {
        byte[] data = encrypted.readAllBytes();
        int headerLength = FILE_HEADER.length + SALT_LENGTH + NONCE_LENGTH;
        if (data.length < ENCRYPTION_OVERHEAD || !hasFileHeader(data)) {
            throw new UnsupportedEncryptedFileException();
        }

        ByteBuffer input = ByteBuffer.wrap(data);
        input.position(FILE_HEADER.length);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        input.get(salt).get(nonce);
        byte[] ciphertext = Arrays.copyOfRange(data, headerLength, data.length);
        Cipher cipher = createCipher(Cipher.DECRYPT_MODE, code, salt, nonce);
        return new ByteArrayInputStream(cipher.doFinal(ciphertext));
    }

    private static boolean hasFileHeader(byte[] data) {
        if (data.length < FILE_HEADER.length) {
            return false;
        }
        for (int i = 0; i < FILE_HEADER.length; i++) {
            if (data[i] != FILE_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private static Cipher createCipher(int mode, String code, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A non-empty encryption code is required.");
        }

        PBEKeySpec keySpec = new PBEKeySpec(code.toCharArray(), salt, KEY_DERIVATION_ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKey derivedKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec);
            SecretKeySpec aesKey = new SecretKeySpec(derivedKey.getEncoded(), "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher;
        } finally {
            keySpec.clearPassword();
        }
    }

    public static class UnsupportedEncryptedFileException extends GeneralSecurityException {
        public UnsupportedEncryptedFileException() {
            super("This file uses an older encryption format and must be uploaded again.");
        }
    }
}
