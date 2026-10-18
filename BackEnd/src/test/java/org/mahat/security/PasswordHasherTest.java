package org.mahat.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test
    void verifiesPasswordWithoutKeepingPlaintext() {
        String password = "correct horse battery staple";
        String encoded = PasswordHasher.hash(password);

        assertTrue(PasswordHasher.verify(password, encoded));
        assertFalse(PasswordHasher.verify("incorrect horse battery staple", encoded));
        assertFalse(encoded.contains(password));
        assertTrue(encoded.startsWith("pbkdf2-sha256$600000$"));
    }

    @Test
    void independentlySaltsEveryPassword() {
        String first = PasswordHasher.hash("same account password");
        String second = PasswordHasher.hash("same account password");

        assertNotEquals(first, second);
        assertNotEquals(first.split("\\$")[2], second.split("\\$")[2]);
        assertTrue(PasswordHasher.verify("same account password", first));
        assertTrue(PasswordHasher.verify("same account password", second));
    }

    @Test
    void handlesUnicodeAndWhitespaceWithoutChangingPassword() {
        String password = "  סיסמה 🔒 é  ";
        String encoded = PasswordHasher.hash(password);

        assertTrue(PasswordHasher.verify(password, encoded));
        assertFalse(PasswordHasher.verify(password.trim(), encoded));
    }

    @Test
    void rejectsMalformedHashesAndUnboundedWorkFactors() {
        String hash = PasswordHasher.hash("test-password");
        for (String malformed : new String[]{"", "plaintext-password", "pbkdf2-sha256$NaN$a$b",
                hash.replace("$600000$", "$999999999$"), hash.replace("$600000$", "$1$"),
                hash.replace("pbkdf2-sha256", "unknown-kdf"), hash + "$extra", "!".repeat(300),
                "pbkdf2-sha256$600000$not base64!$not base64!"}) {
            assertFalse(PasswordHasher.verify("test-password", malformed), malformed);
        }
        assertFalse(PasswordHasher.verify("password", null));
        assertFalse(PasswordHasher.verify(null, hash));
        assertFalse(PasswordHasher.verify("", hash));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(""));
    }

    @Test
    void detectsChangedHashBytesAndSupportsDummyHash() {
        String encoded = PasswordHasher.hash("test-password");
        String[] parts = encoded.split("\\$");
        byte[] digest = Base64.getDecoder().decode(parts[3]);
        digest[0] ^= 1;
        parts[3] = Base64.getEncoder().withoutPadding().encodeToString(digest);

        assertFalse(PasswordHasher.verify("test-password", String.join("$", parts)));
        assertFalse(PasswordHasher.verify("any-real-password", PasswordHasher.DUMMY_HASH));
    }
}
