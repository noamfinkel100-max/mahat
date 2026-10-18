package org.mahat;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import javax.net.ssl.SSLException;

import static org.junit.jupiter.api.Assertions.*;

class StartupFailureTest {
    private static final String SECRET = "private-password-never-log";

    @Test void reportsTheFailingServiceWithoutLeakingItsExceptionMessage() {
        var storage = StartupFailure.at(StartupFailure.Stage.STORAGE, new ConnectException(SECRET));
        assertEquals(StartupFailure.Stage.STORAGE, storage.stage());
        assertTrue(storage.getMessage().contains("MinIO/Silo"));
        assertTrue(storage.getMessage().contains("S3_ENDPOINT"));
        assertFalse(storage.getMessage().contains(SECRET));
        var database = StartupFailure.at(StartupFailure.Stage.DATABASE, new IOException(SECRET));
        assertTrue(database.getMessage().contains("MongoDB"));
        assertFalse(database.getMessage().contains(SECRET));
    }

    @Test void recognizesNestedTlsAndPortBindingFailures() {
        assertTrue(StartupFailure.at(StartupFailure.Stage.STORAGE,
                new RuntimeException(new SSLException(SECRET))).getMessage().contains("TLS verification"));
        var http = StartupFailure.http(new RuntimeException(new BindException(SECRET)), 12000);
        assertTrue(http.getMessage().contains("12000 is already in use"));
        assertFalse(http.getMessage().contains(SECRET));
    }

    @Test void identifiesFilesystemAndConfigurationFailures() {
        assertTrue(StartupFailure.at(StartupFailure.Stage.UPLOAD_DIRECTORY, new IOException(SECRET))
                .getMessage().contains("UPLOAD_DIR"));
        assertTrue(StartupFailure.at(StartupFailure.Stage.CONFIGURATION, new IllegalArgumentException(SECRET))
                .getMessage().contains("configuration"));
    }

    @Test void preservesAnAlreadyIdentifiedFailureAndRedactsUnknownErrors() {
        var original = StartupFailure.at(StartupFailure.Stage.STORAGE, new IOException(SECRET));
        assertSame(original, StartupFailure.at(StartupFailure.Stage.CONFIGURATION, original));
        assertFalse(StartupFailure.describe(new RuntimeException(SECRET)).contains(SECRET));
    }
}
