package org.mahat.handlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class EncryptorTest {
    private static final String CODE = "correct-code 🔒";
    private static final byte[] CONTENT = "private file contents\0 שלום".getBytes(StandardCharsets.UTF_8);

    @TempDir Path temporary;

    @Test
    void decryptsWithTheCorrectCode() throws Exception {
        try (InputStream encrypted = Encryptor.encrypt(new ByteArrayInputStream(CONTENT), CODE);
             InputStream decrypted = Encryptor.decrypt(encrypted, CODE)) {
            assertArrayEquals(CONTENT, decrypted.readAllBytes());
        }
    }

    @Test
    void rejectsAnIncorrectCodeWithoutReturningPlaintext() throws Exception {
        byte[] encrypted = encrypt(CONTENT);
        assertThrows(AEADBadTagException.class,
                () -> Encryptor.decrypt(new ByteArrayInputStream(encrypted), "incorrect-code"));
    }

    @Test
    void rejectsModifiedCiphertextAndAuthenticationTag() throws Exception {
        byte[] encrypted = encrypt(CONTENT);
        encrypted[encrypted.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class,
                () -> Encryptor.decrypt(new ByteArrayInputStream(encrypted), CODE));

        encrypted[encrypted.length - 1] ^= 1;
        encrypted[36] ^= 1;
        assertThrows(AEADBadTagException.class,
                () -> Encryptor.decryptTo(new ByteArrayInputStream(encrypted), OutputStream.nullOutputStream(), CODE));
    }

    @Test
    void rejectsOldFormatsAndTruncatedHeadersWithoutFallback() throws Exception {
        byte[] valid = encrypt(CONTENT);
        for (byte[] unsupported : new byte[][]{new byte[32], "unencrypted plaintext".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(valid, 7), Arrays.copyOf(valid, 25), Arrays.copyOf(valid, 40)}) {
            assertThrows(Encryptor.UnsupportedEncryptedFileException.class,
                    () -> Encryptor.decrypt(new ByteArrayInputStream(unsupported), CODE));
        }
    }

    @Test
    void supportsEmptyFilesAndReportsExactEncryptionOverhead() throws Exception {
        for (byte[] original : new byte[][]{new byte[0], CONTENT, new byte[1_000]}) {
            byte[] encrypted = encrypt(original);
            assertEquals(original.length + Encryptor.ENCRYPTION_OVERHEAD, encrypted.length);
            try (InputStream decrypted = Encryptor.decrypt(new ByteArrayInputStream(encrypted), CODE)) {
                assertArrayEquals(original, decrypted.readAllBytes());
            }
        }
    }

    @Test
    void independentlyRandomizesSaltAndNonce() throws Exception {
        byte[] first = encrypt(CONTENT);
        byte[] second = encrypt(CONTENT);
        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 8, 24), Arrays.copyOfRange(second, 8, 24)));
        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 24, 36), Arrays.copyOfRange(second, 24, 36)));
    }

    @Test
    void readsExistingSunJceFilesWithoutMigration() throws Exception {
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        Arrays.fill(salt, (byte) 0x31);
        Arrays.fill(nonce, (byte) 0x72);
        Cipher previousImplementation = sunJceCipher(Cipher.ENCRYPT_MODE, salt, nonce);
        ByteArrayOutputStream previousFile = new ByteArrayOutputStream();
        previousFile.write("CYPNOD01".getBytes(StandardCharsets.US_ASCII));
        previousFile.write(salt);
        previousFile.write(nonce);
        previousFile.write(previousImplementation.doFinal(CONTENT));

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        Encryptor.decryptTo(new ByteArrayInputStream(previousFile.toByteArray()), decrypted, CODE);
        assertArrayEquals(CONTENT, decrypted.toByteArray());
    }

    @Test
    void newFilesRemainReadableByOriginalSunJceImplementation() throws Exception {
        byte[] encrypted = encrypt(CONTENT);
        Cipher previousImplementation = sunJceCipher(Cipher.DECRYPT_MODE,
                Arrays.copyOfRange(encrypted, 8, 24), Arrays.copyOfRange(encrypted, 24, 36));
        assertArrayEquals(CONTENT, previousImplementation.doFinal(Arrays.copyOfRange(encrypted, 36, encrypted.length)));
    }

    @Test
    void streamsLargeFileWithoutWholeInputReadsOrProviderBuffering() throws Exception {
        long size = 4L * 1024 * 1024 + 7;
        Path encrypted = temporary.resolve("large.enc");
        try (InputStream original = repeatedBytes(size); OutputStream target = Files.newOutputStream(encrypted)) {
            Encryptor.encryptTo(original, target, CODE);
        }
        long encryptedSize = Files.size(encrypted);
        assertEquals(size + Encryptor.ENCRYPTION_OVERHEAD, encryptedSize);

        try (TrackingInput encryptedInput = new TrackingInput(Files.newInputStream(encrypted))) {
            OutputStream plaintext = new OutputStream() {
                long written;
                @Override public void write(int value) { fail("Expected bounded chunk writes."); }
                @Override public void write(byte[] data, int offset, int length) {
                    if (length == 0) return;
                    if (written == 0) {
                        assertTrue(encryptedInput.consumed < encryptedSize,
                                "The crypto provider must emit bounded chunks before reading the complete file.");
                    }
                    assertTrue(length <= 64 * 1024 + 16, "Unbounded plaintext allocation.");
                    for (int index = offset; index < offset + length; index++) {
                        if (data[index] != 0x5a) fail("Decrypted content differs from the generated source.");
                    }
                    written += length;
                }
                @Override public void close() { assertEquals(size, written); }
            };
            try (plaintext) {
                Encryptor.decryptTo(encryptedInput, plaintext, CODE);
            }
        }
    }

    @Test
    void propagatesIoFailuresAndRejectsMissingCodes() {
        InputStream failed = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("simulated read failure"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("simulated read failure");
            }
        };
        assertThrows(IOException.class, () -> Encryptor.encryptTo(failed, OutputStream.nullOutputStream(), CODE));
        for (String invalid : new String[]{null, "", "   "}) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            assertThrows(IllegalArgumentException.class,
                    () -> Encryptor.encryptTo(new ByteArrayInputStream(CONTENT), target, invalid));
            assertEquals(0, target.size());
        }
    }

    private static byte[] encrypt(byte[] content) throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        Encryptor.encryptTo(new ByteArrayInputStream(content), target, CODE);
        return target.toByteArray();
    }

    private static Cipher sunJceCipher(int mode, byte[] salt, byte[] nonce) throws Exception {
        PBEKeySpec specification = new PBEKeySpec(CODE.toCharArray(), salt, 210_000, 256);
        byte[] key;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } finally {
            specification.clearPassword();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        Arrays.fill(key, (byte) 0);
        return cipher;
    }

    private static InputStream repeatedBytes(long length) {
        return new InputStream() {
            long remaining = length;
            @Override public int read() {
                if (remaining == 0) return -1;
                remaining--;
                return 0x5a;
            }
            @Override public int read(byte[] target, int offset, int requested) {
                assertTrue(requested <= 64 * 1024, "Do not read entire files into memory.");
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, requested);
                Arrays.fill(target, offset, offset + count, (byte) 0x5a);
                remaining -= count;
                return count;
            }
            @Override public byte[] readAllBytes() { throw new AssertionError("Whole-file reads are prohibited."); }
        };
    }

    private static class TrackingInput extends FilterInputStream {
        long consumed;
        TrackingInput(InputStream source) { super(source); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            assertTrue(length <= 64 * 1024);
            int count = super.read(bytes, offset, Math.min(length, 8 * 1024));
            if (count > 0) consumed += count;
            return count;
        }
        @Override public byte[] readAllBytes() { throw new AssertionError("Whole-file reads are prohibited."); }
    }
}
