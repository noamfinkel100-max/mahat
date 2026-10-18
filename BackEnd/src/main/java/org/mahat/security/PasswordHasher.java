package org.mahat.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Passwords are not encrypted. PBKDF2 turns each password and random salt into a slow,
 * one-way value that can be checked later without storing the password itself.
 */
public final class PasswordHasher {
    // Use the same expensive check for unknown account names to reduce account-name timing leaks.
    public static final String DUMMY_HASH =
            "pbkdf2-sha256$600000$znQLSrvFlKkIcl3iNwRsOw$svjUMkb6883q6sbdNLzhqkYDvpWtdcYBmwsApbm6aes";

    private static final String ALGORITHM = "pbkdf2-sha256";
    private static final int ITERATIONS = 600_000;
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    /**
     * Saves four readable fields: algorithm, work factor, random salt, derived hash.
     * Base64 only makes the salt and hash printable; it does not encrypt them.
     */
    public static String hash(String password) {
        requirePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] result = derive(password, salt, ITERATIONS);
        try {
            Base64.Encoder base64 = Base64.getEncoder().withoutPadding();
            return ALGORITHM
                    + "$"
                    + ITERATIONS
                    + "$"
                    + base64.encodeToString(salt)
                    + "$"
                    + base64.encodeToString(result);
        } finally {
            Arrays.fill(result, (byte) 0);
        }
    }

    /** Returns false for malformed or unsupported stored values. */
    public static boolean verify(String password, String encoded) {
        if (password == null || password.isEmpty() || encoded == null || encoded.length() > 256) {
            return false;
        }

        byte[] expected = null;
        byte[] actual = null;
        try {
            String[] fields = encoded.split("\\$", -1);
            if (fields.length != 4 || !ALGORITHM.equals(fields[0])) {
                return false;
            }

            int iterations = Integer.parseInt(fields[1]);
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                return false;
            }

            byte[] salt = Base64.getDecoder().decode(fields[2]);
            expected = Base64.getDecoder().decode(fields[3]);
            if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) {
                return false;
            }

            actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException malformedValue) {
            return false;
        } finally {
            clear(expected);
            clear(actual);
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("A password is required.");
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec specification =
                new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException unavailableAlgorithm) {
            throw new IllegalStateException(
                    "The required password hashing algorithm is unavailable.",
                    unavailableAlgorithm);
        } finally {
            specification.clearPassword();
        }
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
