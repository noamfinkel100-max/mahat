package org.mahat.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Salted PBKDF2 account passwords; never used for file encryption codes. */
public final class PasswordHasher {
    // A valid precomputed hash keeps nonexistent-user verification on the same KDF path.
    public static final String DUMMY_HASH = "pbkdf2-sha256$600000$znQLSrvFlKkIcl3iNwRsOw$svjUMkb6883q6sbdNLzhqkYDvpWtdcYBmwsApbm6aes";
    private static final String FORMAT = "pbkdf2-sha256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("A password is required.");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        try {
            Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
            return FORMAT + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /** Malformed and unsupported hashes fail closed; comparisons are constant time. */
    public static boolean verify(String password, String encoded) {
        if (password == null || password.isEmpty() || encoded == null || encoded.length() > 256) return false;
        byte[] expected = null;
        byte[] actual = null;
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !FORMAT.equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            // Bound attacker-controlled work when reading a damaged/unsupported DB value.
            if (iterations < 100_000 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) return false;
            actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException malformedHash) {
            return false;
        } finally {
            if (expected != null) Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        PBEKeySpec specification = new PBEKeySpec(characters, salt, iterations, HASH_BYTES * 8);
        Arrays.fill(characters, '\0');
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException unavailableAlgorithm) {
            throw new IllegalStateException("The required password hashing algorithm is unavailable.", unavailableAlgorithm);
        } finally {
            specification.clearPassword();
        }
    }
}
