package org.mahat.api;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + MongoDB + MinIO tests. Every run owns a fresh database and bucket. */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class SecurityIntegrationTest {
    private static final String PASSWORD = "test-only-account-password";
    private static final String CODE = "test-only-file-code";
    private static final byte[] CONTENT = "Encrypted integration fixture: שלום 🔒\n".getBytes(StandardCharsets.UTF_8);
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String database = "cyphernod_test_" + suffix;
    private final String bucket = "cyphernod-test-" + suffix;
    @TempDir static Path temporary;
    private Vertx vertx;
    private MongoClient mongo;
    private Api api;
    private HttpServer server;
    private ControlledStorage storage;
    private MinioClient minio;
    private JsonObject config;
    private String base;
    private Account owner, bob, charlie, outsider;

    @BeforeAll
    void startIsolatedApplication() throws Exception {
        config = new JsonObject().put("MONGO_URL", env("TEST_MONGO_URL", "mongodb://127.0.0.1:27018"))
                .put("MONGO_DB_NAME", database).put("S3_ENDPOINT", env("TEST_S3_ENDPOINT", "http://127.0.0.1:19000"))
                .put("S3_ACCESS_KEY", env("TEST_S3_ACCESS_KEY", "cyphernod-test"))
                .put("S3_SECRET_KEY", env("TEST_S3_SECRET_KEY", "cyphernod-test-only-password"))
                .put("S3_BUCKET", bucket).put("SESSION_SECURE", false).put("MAX_UPLOAD_BYTES", 1024 * 1024)
                .put("UPLOAD_DIR", temporary.resolve("uploads").toString());
        vertx = Vertx.vertx();
        mongo = MongoClient.create(vertx, new JsonObject().put("connection_string", config.getString("MONGO_URL")).put("db_name", database));
        minio = MinioClient.builder().endpoint(config.getString("S3_ENDPOINT"))
                .credentials(config.getString("S3_ACCESS_KEY"), config.getString("S3_SECRET_KEY")).build();
        storage = new ControlledStorage(config);
        api = new Api(vertx, config, storage);
        await(api.initialize());
        server = await(vertx.createHttpServer().requestHandler(api.router()).listen(0, "127.0.0.1"));
        base = "http://127.0.0.1:" + server.actualPort();
        owner = register("owner"); bob = register("bob"); charlie = register("charlie"); outsider = register("outsider");
    }

    @AfterAll
    void removeOnlyThisRunsData() throws Exception {
        if (storage != null) { storage.failDelete.set(false); storage.failDownload.set(false); storage.release(); }
        if (server != null) await(server.close());
        if (api != null) api.close();
        try {
            if (mongo != null) {
                assertTrue(database.startsWith("cyphernod_test_"));
                await(mongo.runCommand("dropDatabase", new JsonObject().put("dropDatabase", 1)));
                mongo.close();
            }
            if (minio != null) {
                for (io.minio.Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                    minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(result.get().objectName()).build());
                }
                minio.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            }
        } finally {
            if (vertx != null) await(vertx.close());
        }
    }

    @Test
    void unauthenticatedRequestsCannotSpoofAnIdentity() throws Exception {
        HttpClient anonymous = client();
        assertStatus(401, request(anonymous, "GET", "/api/files?userId=" + owner.id, null));
        assertStatus(401, request(anonymous, "GET", "/api/users", null));
        HttpRequest spoof = HttpRequest.newBuilder(URI.create(base + "/api/files"))
                .header("userId", owner.id).header("X-User-Id", owner.id).GET().build();
        assertEquals(401, anonymous.send(spoof, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void registrationStoresOnlyHashedPasswordsAndSecureCookieAttributes() throws Exception {
        Account account = register("password-audit");
        JsonObject stored = await(mongo.findOne(Api.USERS, new JsonObject().put("_id", account.id), null));
        assertTrue(stored.getString("passwordHash").startsWith("pbkdf2-sha256$600000$"));
        assertFalse(stored.containsKey("password"));
        Result login = request(client(), "POST", "/api/auth/login", new JsonObject().put("name", account.name).put("password", PASSWORD));
        assertStatus(200, login);
        assertFalse(login.json().containsKey("passwordHash"));
        String cookie = login.response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.toLowerCase().contains("httponly"));
        assertTrue(cookie.toLowerCase().contains("samesite=strict"));
    }

    @Test
    void loginRejectsWrongAndUnknownCredentialsAndSupportsCanonicalNames() throws Exception {
        Result wrong = request(client(), "POST", "/api/auth/login", new JsonObject().put("name", owner.name).put("password", "incorrect-password"));
        Result missing = request(client(), "POST", "/api/auth/login", new JsonObject().put("name", "missing-" + suffix).put("password", "incorrect-password"));
        assertError(401, "INVALID_CREDENTIALS", wrong);
        assertError(401, "INVALID_CREDENTIALS", missing);
        assertEquals(wrong.json(), missing.json());
        HttpClient signedIn = client();
        assertStatus(200, request(signedIn, "POST", "/api/auth/login", new JsonObject().put("name", owner.name.toUpperCase()).put("password", PASSWORD)));
        assertEquals(owner.id, request(signedIn, "GET", "/api/auth/me", null).json().getString("userId"));
    }

    @Test
    void logoutAndExpiredSessionsCannotBeReplayed() throws Exception {
        Account account = register("session-audit");
        String rawCookie = ((CookieManager) account.client.cookieHandler().orElseThrow()).getCookieStore().getCookies().get(0).toString();
        assertStatus(204, request(account.client, "POST", "/api/auth/logout", null));
        HttpRequest replay = HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).header("Cookie", rawCookie).GET().build();
        assertEquals(401, client().send(replay, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertStatus(200, request(account.client, "POST", "/api/auth/login", new JsonObject().put("name", account.name).put("password", PASSWORD)));
        await(mongo.updateCollection(Api.SESSIONS, new JsonObject().put("userId", account.id),
                new JsonObject().put("$set", new JsonObject().put("expiresAt", new JsonObject().put("$date", Instant.now().minusSeconds(3600).toString())))));
        assertError(401, "UNAUTHENTICATED", request(account.client, "GET", "/api/auth/me", null));
    }

    @Test
    void hashedSessionsPersistAcrossInstancesAndLogoutInvalidatesBoth() throws Exception {
        Account account = register("multi-instance");
        String token = ((CookieManager) account.client.cookieHandler().orElseThrow()).getCookieStore().getCookies().get(0).getValue();
        JsonObject session = await(mongo.findOne(Api.SESSIONS, new JsonObject().put("userId", account.id), null));
        assertNotEquals(token, session.getString("_id"));
        assertFalse(session.encode().contains(token));
        JsonObject indexes = await(mongo.runCommand("listIndexes", new JsonObject().put("listIndexes", Api.SESSIONS)));
        assertTrue(indexes.getJsonObject("cursor").getJsonArray("firstBatch").stream().map(item -> (JsonObject) item)
                .anyMatch(index -> index.getJsonObject("key").containsKey("expiresAt")
                        && ((Number) index.getValue("expireAfterSeconds", -1)).intValue() == 0));
        Api second = new Api(vertx, config, storage);
        HttpServer secondServer = null;
        try {
            await(second.initialize());
            secondServer = await(vertx.createHttpServer().requestHandler(second.router()).listen(0, "127.0.0.1"));
            HttpRequest me = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + secondServer.actualPort() + "/api/auth/me")).GET().build();
            Result before = new Result(account.client.send(me, HttpResponse.BodyHandlers.ofByteArray()));
            assertStatus(200, before); assertEquals(account.id, before.json().getString("userId"));
            assertStatus(204, request(account.client, "POST", "/api/auth/logout", null));
            HttpRequest replay = HttpRequest.newBuilder(me.uri()).header("Cookie", "cyphernod_session=" + token).GET().build();
            assertEquals(401, client().send(replay, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        } finally {
            if (secondServer != null) await(secondServer.close());
            second.close();
        }
    }

    @Test
    void duplicateAccountNamesAndMalformedRequestsAreRejected() throws Exception {
        assertError(409, "NAME_TAKEN", request(client(), "POST", "/api/auth/register",
                new JsonObject().put("name", owner.name.toUpperCase()).put("password", PASSWORD)));
        assertStatus(400, request(client(), "POST", "/api/auth/register", new JsonObject().put("name", "xx").put("password", "short")));
        HttpRequest malformed = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{broken")).build();
        assertEquals(400, client().send(malformed, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void crossSiteRequestsAreRejectedEvenWithValidSession() throws Exception {
        HttpRequest crossSite = HttpRequest.newBuilder(URI.create(base + "/api/files")).header("Origin", "https://untrusted.invalid").GET().build();
        assertEquals(403, owner.client.send(crossSite, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void uploadRoundTripUsesUniqueIdsAndEncryptedObjectSize() throws Exception {
        JsonObject first = upload(owner, "duplicate.txt"), second = upload(owner, "duplicate.txt");
        assertNotEquals(first.getString("fileId"), second.getString("fileId"));
        JsonObject stored = document(first);
        assertEquals(CONTENT.length + org.mahat.handlers.Encryptor.ENCRYPTION_OVERHEAD,
                minio.statObject(StatObjectArgs.builder().bucket(bucket).object(stored.getString("storageObjectKey")).build()).size());
        assertArrayEquals(CONTENT, download(owner, first, CODE).response.body());
        assertArrayEquals(CONTENT, download(owner, second, CODE).response.body());
    }

    @Test
    void nonownersCannotDownloadShareOrDeleteAFile() throws Exception {
        JsonObject file = upload(owner, "owner-only.txt");
        assertError(403, "ACCESS_DENIED", download(outsider, file, CODE));
        assertError(403, "OWNER_REQUIRED", request(outsider.client, "POST", path(file) + "/shares", policy(bob, 1, null)));
        assertError(403, "OWNER_REQUIRED", request(outsider.client, "GET", path(file) + "/shares", null));
        assertError(403, "OWNER_REQUIRED", request(outsider.client, "DELETE", path(file), null));
        assertEquals("ACTIVE", document(file).getString("status"));
    }

    @Test
    void independentRecipientLimitsNeverRestrictOwnerAccess() throws Exception {
        JsonObject file = upload(owner, "independent.txt");
        share(file, bob, 1, null); share(file, charlie, 3, null);
        assertStatus(200, download(bob, file, CODE));
        assertError(403, "DOWNLOAD_LIMIT_REACHED", download(bob, file, CODE));
        assertEquals(3, listedShare(charlie, file).getInteger("remainingDownloads"));
        assertStatus(200, download(charlie, file, CODE));
        assertEquals(2, listedShare(charlie, file).getInteger("remainingDownloads"));
        assertStatus(200, download(owner, file, CODE)); assertStatus(200, download(owner, file, CODE));
        assertEquals(1, shareDocument(file, bob).getInteger("downloadCount"));
        assertEquals(1, shareDocument(file, charlie).getInteger("downloadCount"));
    }

    @Test
    void supportsThreeAndFiveDownloadPolicies() throws Exception {
        for (int limit : new int[]{3, 5}) {
            JsonObject file = upload(owner, "limit-" + limit + ".txt"); share(file, bob, limit, null);
            for (int index = 0; index < limit; index++) assertStatus(200, download(bob, file, CODE));
            assertError(403, "DOWNLOAD_LIMIT_REACHED", download(bob, file, CODE));
            assertEquals(limit, shareDocument(file, bob).getInteger("downloadCount"));
        }
    }

    @Test
    void incorrectCodesAndStorageFailuresDoNotConsumeAllowance() throws Exception {
        JsonObject file = upload(owner, "failures.txt"); share(file, bob, 1, null);
        assertError(400, "INVALID_CODE", download(bob, file, "incorrect-code"));
        assertEquals(0, shareDocument(file, bob).getInteger("downloadCount"));
        storage.failDownload.set(true);
        try { assertError(503, "STORAGE_UNAVAILABLE", download(bob, file, CODE)); }
        finally { storage.failDownload.set(false); }
        assertEquals(0, shareDocument(file, bob).getInteger("downloadCount"));
        assertStatus(200, download(bob, file, CODE));
        assertEquals(1, shareDocument(file, bob).getInteger("downloadCount"));
        try (var entries = Files.list(temporary.resolve("uploads"))) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith("download-")), "Plaintext temporary files must be removed.");
        }
    }

    @Test
    void concurrentRequestsCannotSpendFinalAllowanceMoreThanOnce() throws Exception {
        JsonObject file = upload(owner, "concurrent.txt"); share(file, bob, 1, null);
        List<CompletableFuture<HttpResponse<byte[]>>> downloads = new ArrayList<>();
        for (int index = 0; index < 10; index++) downloads.add(bob.client.sendAsync(downloadRequest(file, CODE), HttpResponse.BodyHandlers.ofByteArray()));
        CompletableFuture.allOf(downloads.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        int successes = 0;
        for (var future : downloads) {
            Result result = new Result(future.get());
            if (result.status() == 200) { successes++; assertArrayEquals(CONTENT, result.response.body()); }
            else assertError(403, "DOWNLOAD_LIMIT_REACHED", result);
        }
        assertEquals(1, successes);
        assertEquals(1, shareDocument(file, bob).getInteger("downloadCount"));
    }

    @Test
    void ownerCanModifyLimitsAndRevokeWithoutResettingCounters() throws Exception {
        JsonObject file = upload(owner, "modify.txt"); share(file, bob, 1, null);
        assertStatus(200, download(bob, file, CODE));
        String future = Instant.now().plusSeconds(3600).toString();
        Result updated = request(owner.client, "PUT", path(file) + "/shares/" + bob.id, policy(bob, 3, future));
        assertStatus(200, updated); assertEquals(2, updated.json().getInteger("remainingDownloads"));
        assertEquals(Instant.parse(future).toEpochMilli(), Instant.parse(updated.json().getString("expiresAt")).toEpochMilli());
        assertStatus(200, download(bob, file, CODE));
        assertStatus(200, request(owner.client, "DELETE", path(file) + "/shares/" + bob.id, null));
        assertError(403, "ACCESS_DENIED", download(bob, file, CODE));
        assertFalse(sharedIds(bob).contains(file.getString("fileId")));
        assertStatus(200, request(owner.client, "PUT", path(file) + "/shares/" + bob.id, policy(bob, 3, null)));
        assertEquals(2, shareDocument(file, bob).getInteger("downloadCount"));
        assertStatus(200, download(bob, file, CODE));
        assertError(403, "DOWNLOAD_LIMIT_REACHED", download(bob, file, CODE));
    }

    @Test
    void expirationAndLimitAreEnforcedTogetherUsingControlledData() throws Exception {
        JsonObject file = upload(owner, "expiration.txt");
        share(file, bob, 1, Instant.now().plusSeconds(3600).toString());
        assertStatus(200, download(bob, file, CODE));
        assertError(403, "DOWNLOAD_LIMIT_REACHED", download(bob, file, CODE));
        setExpiration(file, bob, Instant.now().minusSeconds(60).toEpochMilli());
        assertError(403, "SHARE_EXPIRED", download(bob, file, CODE));
        assertEquals(1, shareDocument(file, bob).getInteger("downloadCount"));
        assertTrue(Instant.parse(listedShare(bob, file).getString("expiresAt")).isBefore(Instant.now()));
        assertStatus(200, download(owner, file, CODE));
    }

    @Test
    void neverExpiringUnlimitedShareTracksDownloadsWithoutExhaustion() throws Exception {
        JsonObject file = upload(owner, "unlimited.txt"); share(file, bob, null, null);
        for (int index = 0; index < 4; index++) assertStatus(200, download(bob, file, CODE));
        JsonObject share = listedShare(bob, file);
        assertNull(share.getValue("expiresAt")); assertNull(share.getValue("maxDownloads")); assertNull(share.getValue("remainingDownloads"));
        assertEquals(4, share.getInteger("downloadCount"));
    }

    @Test
    void invalidPoliciesCannotGrantAccessOrAlterExistingPermission() throws Exception {
        JsonObject file = upload(owner, "invalid-policy.txt");
        for (JsonObject invalid : new JsonObject[]{policy(bob, 2, null), policy(bob, 1, "not-a-date"),
                policy(bob, 1, Instant.now().minusSeconds(60).toString()), policy(bob, 1, null).put("maxDownloads", 1.5)}) {
            assertError(400, "INVALID_POLICY", request(owner.client, "POST", path(file) + "/shares", invalid));
        }
        assertError(400, "INVALID_RECIPIENT", request(owner.client, "POST", path(file) + "/shares", policy(owner, 1, null)));
        assertTrue(document(file).getJsonArray("shares").isEmpty());
        share(file, bob, 1, null);
        assertError(403, "OWNER_REQUIRED", request(charlie.client, "PUT", path(file) + "/shares/" + bob.id, policy(bob, 5, null)));
        assertError(403, "OWNER_REQUIRED", request(charlie.client, "DELETE", path(file) + "/shares/" + bob.id, null));
        assertEquals(1, shareDocument(file, bob).getInteger("maxDownloads"));
    }

    @Test
    void globalDeletionRemovesObjectAndEveryRecipientButPreservesDuplicateNames() throws Exception {
        JsonObject file = upload(owner, "same-name.txt"), other = upload(outsider, "same-name.txt");
        share(file, bob, null, null); share(file, charlie, null, null);
        assertTrue(sharedIds(bob).contains(file.getString("fileId"))); assertTrue(sharedIds(charlie).contains(file.getString("fileId")));
        String objectKey = document(file).getString("storageObjectKey");
        assertStatus(204, request(owner.client, "DELETE", path(file), null));
        assertEquals("DELETED", document(file).getString("status")); assertTrue(document(file).getJsonArray("shares").isEmpty());
        assertFalse(sharedIds(bob).contains(file.getString("fileId"))); assertFalse(sharedIds(charlie).contains(file.getString("fileId")));
        assertError(410, "FILE_DELETED", download(bob, file, CODE)); assertError(410, "FILE_DELETED", download(charlie, file, CODE));
        assertObjectMissing(objectKey);
        assertStatus(204, request(owner.client, "DELETE", path(file), null));
        assertStatus(200, download(outsider, other, CODE)); assertEquals("ACTIVE", document(other).getString("status"));
    }

    @Test
    void deletionFailureRevokesImmediatelyAndExplicitRetryCompletesCleanup() throws Exception {
        JsonObject file = upload(owner, "retry-delete.txt"); share(file, bob, 1, null);
        String key = document(file).getString("storageObjectKey");
        storage.failDelete.set(true);
        try {
            assertError(503, "DELETION_PENDING", request(owner.client, "DELETE", path(file), null));
            assertEquals("DELETING", document(file).getString("status"));
            assertError(410, "FILE_DELETED", download(bob, file, CODE)); assertError(410, "FILE_DELETED", download(owner, file, CODE));
            assertFalse(sharedIds(bob).contains(file.getString("fileId")));
            minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        } finally { storage.failDelete.set(false); }
        assertStatus(204, request(owner.client, "DELETE", path(file), null));
        assertEquals("DELETED", document(file).getString("status")); assertObjectMissing(key);
    }

    @Test
    void restartRecoversPendingDeletionWithoutRestoringAccess() throws Exception {
        JsonObject file = upload(owner, "restart-delete.txt"); share(file, bob, 1, null);
        storage.failDelete.set(true);
        try { assertError(503, "DELETION_PENDING", request(owner.client, "DELETE", path(file), null)); }
        finally { storage.failDelete.set(false); }
        Api restarted = new Api(vertx, config, storage);
        try {
            await(restarted.initialize());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"DELETED".equals(document(file).getString("status")) && System.nanoTime() < deadline) Thread.sleep(25);
            assertEquals("DELETED", document(file).getString("status"));
            assertError(410, "FILE_DELETED", download(bob, file, CODE));
            assertObjectMissing(document(file).getString("storageObjectKey"));
        } finally { restarted.close(); }
    }

    @Test
    void deletionDuringDecryptionPreventsNewlyAuthorizedDownload() throws Exception {
        JsonObject file = upload(owner, "delete-race.txt"); share(file, bob, 1, null);
        Gate gate = storage.block(document(file).getString("storageObjectKey"));
        CompletableFuture<HttpResponse<byte[]>> pending = bob.client.sendAsync(downloadRequest(file, CODE), HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertTrue(gate.started.await(10, TimeUnit.SECONDS), "Download must reach storage before deletion.");
            assertStatus(204, request(owner.client, "DELETE", path(file), null));
        } finally { storage.release(); }
        assertError(410, "FILE_DELETED", new Result(pending.get(15, TimeUnit.SECONDS)));
    }

    @Test
    void expirationDuringDecryptionIsRecheckedBeforeAllowanceConsumption() throws Exception {
        JsonObject file = upload(owner, "expire-race.txt"); share(file, bob, 1, Instant.now().plusSeconds(3600).toString());
        Gate gate = storage.block(document(file).getString("storageObjectKey"));
        CompletableFuture<HttpResponse<byte[]>> pending = bob.client.sendAsync(downloadRequest(file, CODE), HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertTrue(gate.started.await(10, TimeUnit.SECONDS));
            setExpiration(file, bob, Instant.now().minusSeconds(60).toEpochMilli());
        } finally { storage.release(); }
        assertError(403, "SHARE_EXPIRED", new Result(pending.get(15, TimeUnit.SECONDS)));
        assertEquals(0, shareDocument(file, bob).getInteger("downloadCount"));
    }

    @Test
    void revocationDuringDecryptionCannotGrantOrConsumeDownload() throws Exception {
        JsonObject file = upload(owner, "revoke-race.txt"); share(file, bob, 1, null);
        Gate gate = storage.block(document(file).getString("storageObjectKey"));
        CompletableFuture<HttpResponse<byte[]>> pending = bob.client.sendAsync(downloadRequest(file, CODE), HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertTrue(gate.started.await(10, TimeUnit.SECONDS));
            assertStatus(200, request(owner.client, "DELETE", path(file) + "/shares/" + bob.id, null));
        } finally { storage.release(); }
        assertError(403, "ACCESS_DENIED", new Result(pending.get(15, TimeUnit.SECONDS)));
        assertEquals(0, shareDocument(file, bob).getInteger("downloadCount"));
    }

    private Account register(String label) throws Exception {
        String name = label + "-" + UUID.randomUUID(); HttpClient client = client();
        Result result = request(client, "POST", "/api/auth/register", new JsonObject().put("name", name).put("password", PASSWORD));
        assertStatus(201, result); return new Account(result.json().getString("userId"), name, client);
    }
    private JsonObject upload(Account account, String filename) throws Exception {
        String boundary = "Cyphernod" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"code\"\r\n\r\n" + CODE
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/files")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, CONTENT, suffix))).build();
        Result response = new Result(account.client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
        assertStatus(201, response); return response.json();
    }
    private void share(JsonObject file, Account recipient, Integer limit, String expiration) throws Exception {
        assertStatus(201, request(owner.client, "POST", path(file) + "/shares", policy(recipient, limit, expiration)));
    }
    private JsonObject policy(Account recipient, Integer limit, String expiration) {
        return new JsonObject().put("recipientId", recipient.id).put("maxDownloads", limit).put("expiresAt", expiration);
    }
    private Result download(Account account, JsonObject file, String code) throws Exception {
        return new Result(account.client.send(downloadRequest(file, code), HttpResponse.BodyHandlers.ofByteArray()));
    }
    private HttpRequest downloadRequest(JsonObject file, String code) {
        return HttpRequest.newBuilder(URI.create(base + path(file) + "/download")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("code", code).encode())).build();
    }
    private Result request(HttpClient client, String method, String path, JsonObject body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.encode()));
        return new Result(client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }
    private JsonObject document(JsonObject file) throws Exception {
        return await(mongo.findOne(Api.FILES, new JsonObject().put("_id", file.getString("fileId")), null));
    }
    private JsonObject shareDocument(JsonObject file, Account recipient) throws Exception {
        return document(file).getJsonArray("shares").stream().map(value -> (JsonObject) value)
                .filter(value -> recipient.id.equals(value.getString("recipientId"))).findFirst().orElseThrow();
    }
    private JsonObject listedShare(Account recipient, JsonObject file) throws Exception {
        return request(recipient.client, "GET", "/api/files", null).json().getJsonArray("shared").stream().map(value -> (JsonObject) value)
                .filter(value -> file.getString("fileId").equals(value.getString("fileId"))).findFirst().orElseThrow().getJsonObject("share");
    }
    private List<String> sharedIds(Account recipient) throws Exception {
        return request(recipient.client, "GET", "/api/files", null).json().getJsonArray("shared").stream().map(value -> ((JsonObject) value).getString("fileId")).toList();
    }
    private void setExpiration(JsonObject file, Account recipient, long expiration) throws Exception {
        await(mongo.updateCollection(Api.FILES, new JsonObject().put("_id", file.getString("fileId")).put("shares.recipientId", recipient.id),
                new JsonObject().put("$set", new JsonObject().put("shares.$.expiresAt", expiration)).put("$inc", new JsonObject().put("revision", 1))));
    }
    private void assertObjectMissing(String key) {
        ErrorResponseException missing = assertThrows(ErrorResponseException.class,
                () -> minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()));
        assertEquals("NoSuchKey", missing.errorResponse().code());
    }
    private void assertStatus(int expected, Result result) {
        assertEquals(expected, result.status(), () -> "Unexpected response: " + result.text());
    }
    private void assertError(int status, String code, Result result) {
        assertStatus(status, result); assertEquals(code, result.json().getString("code"), result.text());
    }
    private static String path(JsonObject file) { return "/api/files/" + file.getString("fileId"); }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null ? fallback : value; }
    private static HttpClient client() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private static <T> T await(Future<T> future) throws Exception { return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS); }
    private record Account(String id, String name, HttpClient client) {}
    private record Result(HttpResponse<byte[]> response) {
        int status() { return response.statusCode(); }
        String text() { return new String(response.body(), StandardCharsets.UTF_8); }
        JsonObject json() { return new JsonObject(text()); }
    }
    private static final class Gate {
        final String key;
        final CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
        Gate(String key) { this.key = key; }
    }
    private static final class ControlledStorage extends ObjectStorage {
        final AtomicBoolean failDelete = new AtomicBoolean(), failDownload = new AtomicBoolean();
        volatile Gate gate;
        ControlledStorage(JsonObject config) { super(config); }
        Gate block(String key) { gate = new Gate(key); return gate; }
        void release() { Gate current = gate; gate = null; if (current != null) current.proceed.countDown(); }
        @Override public void delete(String key) throws Exception {
            if (failDelete.get()) throw new IOException("Injected deletion outage");
            super.delete(key);
        }
        @Override public InputStream download(String key) throws Exception {
            if (failDownload.get()) throw new IOException("Injected read outage");
            InputStream input = super.download(key); Gate current = gate;
            if (current == null || !key.equals(current.key)) return input;
            return new FilterInputStream(input) {
                boolean paused;
                private void pause() throws IOException {
                    if (paused) return; paused = true; current.started.countDown();
                    try { if (!current.proceed.await(20, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for controlled race"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                }
                @Override public int read() throws IOException { pause(); return super.read(); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException { pause(); return super.read(bytes, offset, length); }
            };
        }
    }
}
