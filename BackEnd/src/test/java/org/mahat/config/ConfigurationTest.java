package org.mahat.config;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path directory;
    private static final Map<String, String> ACCOUNT = Map.of("S3_ACCESS_KEY", "test-user", "S3_SECRET_KEY", "test-password");

    @Test void loadsDotenvWhenRunFromRepositoryRootOrBackend() throws Exception {
        Files.writeString(directory.resolve(".env"), "S3_ACCESS_KEY=test-user\nS3_SECRET_KEY=test-password\nPORT=12345\n");
        Path backend = Files.createDirectory(directory.resolve("BackEnd"));
        for (Path cwd : new Path[]{directory, backend}) {
            JsonObject config = Configuration.load(cwd, Map.of(), "local", endpoint -> fail("Credentials are already configured"));
            assertEquals("test-password", config.getString("S3_SECRET_KEY"));
            assertEquals(12345, config.getInteger("PORT"));
        }
    }

    @Test void nearestFileWinsWithoutReadingAnotherDirectorysDotenv() throws Exception {
        Files.writeString(directory.resolve(".env"), "S3_ACCESS_KEY=parent\nS3_SECRET_KEY=parent-password\n");
        Path backend = Files.createDirectory(directory.resolve("BackEnd"));
        Files.writeString(backend.resolve(".env"), "S3_ACCESS_KEY=child\nS3_SECRET_KEY=child-password\n");
        assertEquals("child", Configuration.load(backend, Map.of(), "local", endpoint -> Map.of()).getString("S3_ACCESS_KEY"));
        Path unrelated = Files.createDirectory(directory.resolve("unrelated"));
        assertThrows(IllegalArgumentException.class, () -> Configuration.load(unrelated, Map.of(), "production", endpoint -> fail("No discovery in production")));
    }

    @Test void processEnvironmentOverridesJsonAndDotenv() throws Exception {
        Files.writeString(directory.resolve(".env"), "S3_ACCESS_KEY=dotenv\nS3_SECRET_KEY=dotenv-password\nPORT=10001\n");
        Files.writeString(directory.resolve("config.json"), new JsonObject().put("S3_ACCESS_KEY", "json")
                .put("S3_SECRET_KEY", "json-password").put("PORT", 10002).encode());
        JsonObject config = Configuration.load(directory, Map.of("CONFIG_PATH", "config.json", "S3_ACCESS_KEY", "process",
                "S3_SECRET_KEY", "process-password", "PORT", "10003"), "local", endpoint -> fail("No discovery needed"));
        assertEquals("process", config.getString("S3_ACCESS_KEY"));
        assertEquals("process-password", config.getString("S3_SECRET_KEY"));
        assertEquals(10003, config.getInteger("PORT"));
    }

    @Test void explicitlySelectedLegacyJsonOverridesImplicitDotenv() throws Exception {
        Files.writeString(directory.resolve(".env"), "S3_ACCESS_KEY=dotenv\nS3_SECRET_KEY=dotenv-password\n");
        Files.writeString(directory.resolve("config.json"), new JsonObject().put("S3_ACCESS_KEY", "json")
                .put("S3_SECRET_KEY", "json-password").put("EXPOSED_PORT", 10002).encode());
        JsonObject config = Configuration.load(directory, Map.of("CONFIG_PATH", "config.json"), "production", endpoint -> fail("No discovery in production"));
        assertEquals("json", config.getString("S3_ACCESS_KEY"));
        assertEquals(10002, config.getInteger("PORT"));
    }

    @Test void localLaunchUsesExistingDockerCredentialsWithoutWritingAFile() throws Exception {
        JsonObject config = Configuration.load(directory, Map.of("S3_ENDPOINT", "http://localhost:19000"), "local", endpoint -> {
            assertEquals("http://127.0.0.1:19000", endpoint); return ACCOUNT;
        });
        assertEquals("test-user", config.getString("S3_ACCESS_KEY"));
        assertEquals("test-password", config.getString("S3_SECRET_KEY"));
        assertFalse(config.getBoolean("SESSION_SECURE"));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void localProfilePrefersTheRunningDockerServiceOverStaleDotenvCredentials() throws Exception {
        Files.writeString(directory.resolve(".env"), "S3_ACCESS_KEY=stale-user\nS3_SECRET_KEY=stale-password\n");
        JsonObject config = Configuration.load(directory, Map.of(), "local", endpoint -> {
            assertEquals("http://127.0.0.1:9000", endpoint); return Map.of("S3_ACCESS_KEY", "minioadmin", "S3_SECRET_KEY", "minioadmin");
        });
        assertEquals("stale-user", config.getString("S3_ACCESS_KEY"));
        assertEquals("stale-password", config.getString("S3_SECRET_KEY"));
    }

    @Test void productionNeverInspectsDockerOrInventsCredentials() {
        AtomicBoolean called = new AtomicBoolean();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Configuration.load(directory,
                Map.of(), "production", endpoint -> { called.set(true); return ACCOUNT; }));
        assertFalse(called.get());
        assertTrue(error.getMessage().contains("S3_ACCESS_KEY"));
    }

    @Test void explicitProductionProfileDisablesMavenLocalDefault() {
        assertThrows(IllegalArgumentException.class, () -> Configuration.load(directory,
                Map.of("CYPHERNOD_PROFILE", "production"), "local", endpoint -> fail("Production override must disable Docker lookup")));
    }

    @Test void partialCredentialsCannotBeMixedWithAnotherAccount() {
        assertThrows(IllegalArgumentException.class, () -> Configuration.load(directory,
                Map.of("S3_ACCESS_KEY", "different-user"), "local", endpoint -> ACCOUNT));
    }

    @Test void matchingPartialCredentialsMayBeCompletedFromSameAccount() throws Exception {
        JsonObject config = Configuration.load(directory, Map.of("S3_ACCESS_KEY", "test-user"), "local", endpoint -> ACCOUNT);
        assertEquals("test-password", config.getString("S3_SECRET_KEY"));
    }

    @Test void localDockerDefaultsAndPlainLocalhostUseIpv4WithConfiguredPorts() throws Exception {
        JsonObject defaults = Configuration.load(directory, ACCOUNT, "local", endpoint -> fail("Already configured"));
        assertEquals("mongodb://127.0.0.1:27017", defaults.getString("MONGO_URL"));
        assertEquals("http://127.0.0.1:9000", defaults.getString("S3_ENDPOINT"));
        var custom = new java.util.HashMap<>(ACCOUNT);
        custom.put("MONGO_URL", "mongodb://localhost:28017");
        custom.put("S3_ENDPOINT", "http://localhost:19000");
        JsonObject configured = Configuration.load(directory, custom, "local", endpoint -> fail("Already configured"));
        assertEquals("mongodb://127.0.0.1:28017", configured.getString("MONGO_URL"));
        assertEquals("http://127.0.0.1:19000", configured.getString("S3_ENDPOINT"));
    }

    @Test void localAddressNormalizationPreservesEncodedAccountAndQueryValues() throws Exception {
        var custom = new java.util.HashMap<>(ACCOUNT);
        custom.put("MONGO_URL", "mongodb://test:p%40ss%2Fword@localhost:28017/db?authSource=admin&appName=localhost");
        JsonObject config = Configuration.load(directory, custom, "local", endpoint -> fail("Already configured"));
        assertEquals("mongodb://test:p%40ss%2Fword@127.0.0.1:28017/db?authSource=admin&appName=localhost", config.getString("MONGO_URL"));
    }

    @Test void tlsRemoteAndExplicitIpv6EndpointsAreNeverRewritten() throws Exception {
        for (String mongo : new String[]{"mongodb://localhost:27017/?tls=true", "mongodb://localhost:27017/?ssl=true",
                "mongodb://database.example.com:27017", "mongodb://[::1]:27017"}) {
            var custom = new java.util.HashMap<>(ACCOUNT);
            custom.put("MONGO_URL", mongo); custom.put("S3_ENDPOINT", "https://localhost:9000");
            JsonObject config = Configuration.load(directory, custom, "local", endpoint -> fail("Already configured"));
            assertEquals(mongo, config.getString("MONGO_URL"));
            assertEquals("https://localhost:9000", config.getString("S3_ENDPOINT"));
        }
    }

    @Test void quotesCommentsBomAndWindowsPathsPreserveLiteralCredentials() throws Exception {
        Path path = directory.resolve(".env");
        Files.writeString(path, "\uFEFF# comment\r\nexport S3_ACCESS_KEY = 'account name' # comment\r\n"
                + "S3_SECRET_KEY=\"dollar$literal#with=equals\"\nUPLOAD_DIR=C:\\Users\\demo\\uploads\nPORT=12000 # local port\n");
        Map<String, String> config = Configuration.readDotenv(path);
        assertEquals("account name", config.get("S3_ACCESS_KEY"));
        assertEquals("dollar$literal#with=equals", config.get("S3_SECRET_KEY"));
        assertEquals("C:\\Users\\demo\\uploads", config.get("UPLOAD_DIR"));
        assertEquals("12000", config.get("PORT"));
    }

    @Test void malformedDotenvAndJsonErrorsNeverIncludeSecretValues() throws Exception {
        Files.writeString(directory.resolve(".env"), "S3_SECRET_KEY=\"do-not-print-this-secret\n");
        var dotenv = assertThrows(IllegalArgumentException.class, () -> Configuration.load(directory, Map.of(), "local", endpoint -> Map.of()));
        assertFalse(dotenv.getMessage().contains("do-not-print"));
        Files.delete(directory.resolve(".env"));
        Files.writeString(directory.resolve("config.json"), "{ secret: do-not-print-this-secret");
        var json = assertThrows(IllegalArgumentException.class, () -> Configuration.load(directory, Map.of("CONFIG_PATH", "config.json"), "production", endpoint -> Map.of()));
        assertFalse(json.getMessage().contains("do-not-print"));
    }

    @Test void invalidPortsAreReportedWithoutEchoingTheirContents() {
        var environment = new java.util.HashMap<>(ACCOUNT); environment.put("PORT", "do-not-print-this-value");
        var error = assertThrows(IllegalArgumentException.class, () -> Configuration.load(directory, environment, "production", endpoint -> Map.of()));
        assertFalse(error.getMessage().contains("do-not-print"));
    }
}
