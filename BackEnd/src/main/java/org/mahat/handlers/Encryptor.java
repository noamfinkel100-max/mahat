package org.mahat.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class Encryptor {
    private static final Logger LOGGER = LogManager.getLogger(Encryptor.class);

    public static InputStream encrypt(InputStream original, String code) {
        try {
            SecretKeySpec secretKey = generateSecretKey(code);
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = original.read(buffer)) != -1) {
                    cipherOutputStream.write(buffer, 0, bytesRead);
                }
            }
            return new ByteArrayInputStream(outputStream.toByteArray());
        } catch (Exception e) {
            LOGGER.error("Failed to encrypt data! returning the original. The error: %s".formatted(e.getMessage()));
            return original;
        }
    }

    public static InputStream decrypt(InputStream encrypted, String code) {
        try {
            SecretKeySpec secretKey = generateSecretKey(code);
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = encrypted.read(buffer)) != -1) {
                    cipherOutputStream.write(buffer, 0, bytesRead);
                }
            }
            return new ByteArrayInputStream(outputStream.toByteArray());
        } catch (Exception e) {
            LOGGER.error("Failed to decrypt data! Returning original The error: %s".formatted(e.getMessage()));
            return encrypted;
        }
    }

    private static SecretKeySpec generateSecretKey(String code) throws Exception {
        byte[] key = code.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        key = sha.digest(key);
        key = Arrays.copyOf(key, 16);
        return new SecretKeySpec(key, "AES");
    }
}