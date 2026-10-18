package org.mahat;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies failures at the actual Server entry point with real MongoDB and S3 services. */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class StartupIntegrationTest {
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String database = "cyphernod_test_startup_" + suffix;
    private final String bucket = "cyphernod-startup-" + suffix;
    private final List<Server> servers = new ArrayList<>();
    @TempDir Path directory;

    private JsonObject config() {
        return new JsonObject().put("PORT", 0).put("MONGO_URL", env("TEST_MONGO_URL", "mongodb://127.0.0.1:27018"))
                .put("MONGO_DB_NAME", database).put("S3_ENDPOINT", env("TEST_S3_ENDPOINT", "http://127.0.0.1:19000"))
                .put("S3_ACCESS_KEY", env("TEST_S3_ACCESS_KEY", "cyphernod-test"))
                .put("S3_SECRET_KEY", env("TEST_S3_SECRET_KEY", "cyphernod-test-only-password"))
                .put("S3_BUCKET", bucket).put("UPLOAD_DIR", directory.resolve("uploads").toString());
    }

    private Server server() { Server server = new Server(); servers.add(server); return server; }

    @AfterEach void closeServers() throws Exception {
        for (Server server : servers) await(server.close());
        servers.clear();
    }

    @AfterAll void removeOwnedFixtures() throws Exception {
        JsonObject config = config();
        Vertx vertx = Vertx.vertx();
        MongoClient mongo = MongoClient.create(vertx, new JsonObject().put("connection_string", config.getString("MONGO_URL"))
                .put("db_name", database));
        try {
            await(mongo.runCommand("dropDatabase", new JsonObject().put("dropDatabase", 1)));
            MinioClient minio = MinioClient.builder().endpoint(config.getString("S3_ENDPOINT"))
                    .credentials(config.getString("S3_ACCESS_KEY"), config.getString("S3_SECRET_KEY")).build();
            if (minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                minio.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        } finally { mongo.close(); await(vertx.close()); }
    }

    @Test void reportsActualS3AuthenticationRejectionWithoutPrintingCredentials() throws Exception {
        String secret = "private-invalid-key-never-log";
        StartupFailure error = failed(server().start(config().put("S3_ACCESS_KEY", "unknown-account").put("S3_SECRET_KEY", secret)));
        assertEquals(StartupFailure.Stage.STORAGE, error.stage());
        assertTrue(error.getMessage().contains("MinIO/Silo rejected"));
        assertFalse(error.getMessage().contains(secret));
        assertFalse(error.getMessage().contains("unknown-account"));
    }

    @Test void reportsMongoConnectivitySeparatelyFromStorage() throws Exception {
        StartupFailure error = failed(server().start(config().put("MONGO_URL",
                "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=500&connectTimeoutMS=200")));
        assertEquals(StartupFailure.Stage.DATABASE, error.stage());
        assertTrue(error.getMessage().contains("MongoDB"));
    }

    @Test void identifiesAnOccupiedHttpPortAfterServicesInitialize() throws Exception {
        int port = await(server().start(config()));
        assertTrue(port > 0);
        StartupFailure error = failed(server().start(config().put("PORT", port)));
        assertEquals(StartupFailure.Stage.HTTP, error.stage());
        assertTrue(error.getMessage().contains(port + " is already in use"));
    }

    @Test void identifiesAnInvalidUploadDirectoryBeforeContactingStorage() throws Exception {
        Path file = Files.writeString(directory.resolve("not-a-directory"), "test fixture");
        StartupFailure error = failed(server().start(config().put("UPLOAD_DIR", file.toString())));
        assertEquals(StartupFailure.Stage.UPLOAD_DIRECTORY, error.stage());
        assertTrue(error.getMessage().contains("UPLOAD_DIR"));
    }

    private static StartupFailure failed(Future<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(future));
        return assertInstanceOf(StartupFailure.class, error.getCause());
    }
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
