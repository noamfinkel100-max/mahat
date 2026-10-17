package org.mahat.handlers;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptorTest {
    @Test
    void decryptsWithTheCorrectCode() throws Exception {
        byte[] original = "private file contents".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = Encryptor.encrypt(new ByteArrayInputStream(original), "correct-code").readAllBytes();
        byte[] decrypted = Encryptor.decrypt(new ByteArrayInputStream(encrypted), "correct-code").readAllBytes();

        assertArrayEquals(original, decrypted);
    }

    @Test
    void rejectsAnIncorrectCode() throws Exception {
        byte[] encrypted = Encryptor.encrypt(
                new ByteArrayInputStream("private file contents".getBytes(StandardCharsets.UTF_8)),
                "correct-code"
        ).readAllBytes();

        assertThrows(
                AEADBadTagException.class,
                () -> Encryptor.decrypt(new ByteArrayInputStream(encrypted), "incorrect-code")
        );
    }

    @Test
    void rejectsModifiedCiphertext() throws Exception {
        byte[] encrypted = Encryptor.encrypt(
                new ByteArrayInputStream("private file contents".getBytes(StandardCharsets.UTF_8)),
                "correct-code"
        ).readAllBytes();
        encrypted[encrypted.length - 1] ^= 1;

        assertThrows(
                AEADBadTagException.class,
                () -> Encryptor.decrypt(new ByteArrayInputStream(encrypted), "correct-code")
        );
    }

    @Test
    void rejectsFilesUsingThePreviousFormat() {
        byte[] previousFormatFile = new byte[32];

        assertThrows(
                Encryptor.UnsupportedEncryptedFileException.class,
                () -> Encryptor.decrypt(new ByteArrayInputStream(previousFormatFile), "any-code")
        );
    }
}
