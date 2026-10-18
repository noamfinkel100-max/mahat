package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.IndexOptions;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.mahat.handlers.Encryptor;
import org.mahat.security.PasswordHasher;
import org.mahat.StartupFailure;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Authenticated API. A file and its shares form one MongoDB atomic authorization boundary. */
public final class Api {
    static final String USERS = "users_v2", FILES = "files_v2", SESSIONS = "sessions_v2";
    private final Vertx vertx;
    private final MongoClient mongo;
    private final SessionManager sessions;
    private final ObjectStorage storage;
    private final JsonObject config;
    private final Router router;
    private final Path uploads;
    private final long maxUpload;
    private long retryTimer;

    public Api(Vertx vertx, JsonObject config) {
        this(vertx, config, new ObjectStorage(config));
    }

    public Api(Vertx vertx, JsonObject config, ObjectStorage objectStorage) {
        this.vertx = vertx;
        this.config = config;
        mongo = MongoClient.create(
                vertx,
                new JsonObject()
                        .put("connection_string", config.getString("MONGO_URL", "mongodb://localhost:27017"))
                        .put("db_name", config.getString("MONGO_DB_NAME", "cyphernod")));
        boolean secureCookie = Boolean.parseBoolean(String.valueOf(config.getValue("SESSION_SECURE", true)));
        sessions = new SessionManager(mongo, secureCookie);
        storage = objectStorage;
        uploads =
                Path.of(
                        config.getString(
                                "UPLOAD_DIR",
                                System.getProperty("java.io.tmpdir") + "/cyphernod"));
        maxUpload =
                Long.parseLong(
                        String.valueOf(config.getValue("MAX_UPLOAD_BYTES", 100L * 1024 * 1024)));
        if (maxUpload < 1 || maxUpload > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("MAX_UPLOAD_BYTES must be between 1 and 1GiB");
        }
        router = Router.router(vertx);
    }

    public Future<Void> initialize() {
        return prepareUploadDirectory()
                .compose(ignored -> initializeStorage())
                .compose(ignored -> initializeIndexes())
                .map(
                        ignored -> {
                            routes();
                            retryTimer =
                                    vertx.setPeriodic(60_000, timerId -> retryDeletions());
                            retryDeletions();
                            return null;
                        });
    }

    private Future<Void> prepareUploadDirectory() {
        return this.<Void>worker(
                        () -> {
                            Files.createDirectories(uploads);
                            try {
                                Files.setPosixFilePermissions(
                                        uploads, PosixFilePermissions.fromString("rwx------"));
                            } catch (UnsupportedOperationException ignored) {
                                // Windows applies the directory's existing ACL instead.
                            }
                            return null;
                        })
                .recover(
                        error ->
                                Future.failedFuture(
                                        StartupFailure.at(
                                                StartupFailure.Stage.UPLOAD_DIRECTORY, error)));
    }

    private Future<Void> initializeStorage() {
        return this.<Void>worker(
                        () -> {
                            storage.initialize();
                            return null;
                        })
                .recover(
                        error ->
                                Future.failedFuture(
                                        StartupFailure.at(StartupFailure.Stage.STORAGE, error)));
    }

    private Future<Void> initializeIndexes() {
        return mongo.createIndexWithOptions(
                        USERS,
                        new JsonObject().put("canonicalName", 1),
                        new IndexOptions(new JsonObject().put("unique", true)))
                .compose(
                        ignored ->
                                mongo.createIndex(
                                        FILES,
                                        new JsonObject().put("ownerId", 1).put("status", 1)))
                .compose(
                        ignored ->
                                mongo.createIndex(
                                        FILES,
                                        new JsonObject()
                                                .put("shares.recipientId", 1)
                                                .put("status", 1)))
                .compose(
                        ignored ->
                                mongo.createIndex(FILES, new JsonObject().put("status", 1)))
                .compose(
                        ignored ->
                                mongo.createIndexWithOptions(
                                        SESSIONS,
                                        new JsonObject().put("expiresAt", 1),
                                        new IndexOptions(
                                                new JsonObject().put("expireAfterSeconds", 0))))
                .mapEmpty();
    }

    public Router router() {
        return router;
    }

    public void close() {
        if (retryTimer != 0) {
            vertx.cancelTimer(retryTimer);
        }
        mongo.close();
    }

    private <T> Future<T> worker(Callable<T> action) {
        return vertx.executeBlocking(action, false);
    }

    private void routes() {
        registerRequestGuards();
        registerAuthenticationRoutes();
        registerFileRoutes();
        router.route()
                .handler(ctx -> fail(ctx, new ApiException(404, "NOT_FOUND", "Endpoint not found.")));
        router.route().failureHandler(this::handleRouteFailure);
    }

    private void registerRequestGuards() {
        router.route().handler(ctx -> {
            ctx.response()
                    .putHeader("X-Content-Type-Options", "nosniff")
                    .putHeader("Cache-Control", "no-store");
            // Cookie auth is same-origin. Only an explicitly configured trusted dev origin may differ.
            String origin = ctx.request().getHeader("Origin");
            if ("cross-site".equals(ctx.request().getHeader("Sec-Fetch-Site"))) {
                fail(
                        ctx,
                        new ApiException(
                                403, "ORIGIN_DENIED", "This request origin is not allowed."));
                return;
            }
            String requestOrigin = ctx.request().scheme() + "://" + ctx.request().host();
            if (origin != null
                    && !origin.equals(requestOrigin)
                    && !origin.equals(config.getString("ALLOWED_ORIGIN"))) {
                fail(
                        ctx,
                        new ApiException(
                                403, "ORIGIN_DENIED", "This request origin is not allowed."));
                return;
            }
            ctx.next();
        });

        router.get("/api/health")
                .handler(
                        ctx ->
                                mongo.runCommand("ping", new JsonObject().put("ping", 1))
                                        .onSuccess(
                                                ignored ->
                                                        json(
                                                                ctx,
                                                                200,
                                                                new JsonObject().put("status", "ok")))
                                        .onFailure(
                                                error ->
                                                        fail(
                                                                ctx,
                                                                new ApiException(
                                                                        503,
                                                                        "UNAVAILABLE",
                                                                        "Database is unavailable."))));

        registerJsonContentTypeGuard();
    }

    private void registerJsonContentTypeGuard() {
        BodyHandler jsonBody = BodyHandler.create().setBodyLimit(16 * 1024).setHandleFileUploads(false);
        router.route().handler(ctx -> {
            String path = ctx.request().path();
            String method = ctx.request().method().name();
            boolean hasJsonBody = method.equals("POST") || method.equals("PUT");
            boolean isMultipartUpload = path.equals("/api/files");
            boolean isLogout = path.equals("/api/auth/logout");
            if (!hasJsonBody || isMultipartUpload || isLogout) {
                ctx.next();
                return;
            }

            String contentType = ctx.request().getHeader("Content-Type");
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                fail(
                        ctx,
                        new ApiException(
                                415,
                                "CONTENT_TYPE_REQUIRED",
                                "Use application/json for this request."));
                return;
            }
            ctx.next();
        });
    }

    private void registerAuthenticationRoutes() {
        BodyHandler jsonBody = BodyHandler.create().setBodyLimit(16 * 1024).setHandleFileUploads(false);
        router.post("/api/auth/register").handler(jsonBody).handler(ctx -> run(ctx, () -> register(ctx)));
        router.post("/api/auth/login").handler(jsonBody).handler(ctx -> run(ctx, () -> login(ctx)));

        router.route("/api/*").handler(ctx -> {
            // Authentication performs asynchronous database I/O. Pause the request so multipart
            // bytes cannot pass before BodyHandler has installed its stream consumers.
            ctx.request().pause();
            sessions
                    .authenticate(ctx)
                    .onSuccess(
                            user -> {
                                ctx.put("user", user);
                                ctx.next();
                                ctx.request().resume();
                            })
                    .onFailure(
                            error -> {
                                fail(ctx, error);
                                ctx.request().resume();
                            });
        });

        router.get("/api/auth/me").handler(ctx -> json(ctx, 200, publicUser(user(ctx))));
        router.post("/api/auth/logout")
                .handler(
                        ctx ->
                                run(
                                        ctx,
                                        () ->
                                                sessions
                                                        .invalidate(ctx)
                                                        .map(
                                                                ignored -> {
                                                                    ctx.response()
                                                                            .setStatusCode(204)
                                                                            .end();
                                                                    return null;
                                                                })));
    }

    private void registerFileRoutes() {
        BodyHandler jsonBody = BodyHandler.create().setBodyLimit(16 * 1024).setHandleFileUploads(false);

        router.get("/api/users")
                .handler(
                        ctx ->
                                run(
                                        ctx,
                                        () ->
                                                mongo.find(USERS, new JsonObject())
                                                        .map(
                                                                users -> {
                                                                    JsonArray result = new JsonArray();
                                                                    users.forEach(
                                                                            user ->
                                                                                    result.add(
                                                                                            publicUser(
                                                                                                    user)));
                                                                    json(ctx, 200, result);
                                                                    return null;
                                                                })));
        router.get("/api/files").handler(ctx -> run(ctx, () -> listFiles(ctx)));
        router.post("/api/files")
                .handler(
                        BodyHandler.create()
                                .setBodyLimit(maxUpload + 64 * 1024)
                                .setUploadsDirectory(uploads.toString())
                                .setDeleteUploadedFilesOnEnd(true))
                .handler(ctx -> run(ctx, () -> upload(ctx)));
        router.get("/api/files/:fileId/shares")
                .handler(
                        ctx ->
                                run(
                                        ctx,
                                        () ->
                                                owned(ctx, false)
                                                        .map(
                                                                file -> {
                                                                    json(ctx, 200, publicShares(file));
                                                                    return null;
                                                                })));
        router.post("/api/files/:fileId/shares")
                .handler(jsonBody)
                .handler(ctx -> run(ctx, () -> mutateShare(ctx, false, false)));
        router.put("/api/files/:fileId/shares/:recipientId")
                .handler(jsonBody)
                .handler(ctx -> run(ctx, () -> mutateShare(ctx, true, false)));
        router.delete("/api/files/:fileId/shares/:recipientId")
                .handler(ctx -> run(ctx, () -> mutateShare(ctx, true, true)));
        router.post("/api/files/:fileId/download")
                .handler(jsonBody)
                .handler(ctx -> run(ctx, () -> download(ctx)));
        router.delete("/api/files/:fileId").handler(ctx -> run(ctx, () -> delete(ctx)));
    }

    private void handleRouteFailure(RoutingContext ctx) {
        int status = ctx.statusCode();
        boolean tooLarge = status == 413;
        fail(
                ctx,
                new ApiException(
                        tooLarge ? 413 : 400,
                        tooLarge ? "FILE_TOO_LARGE" : "INVALID_REQUEST",
                        tooLarge
                                ? "The uploaded file is too large."
                                : "The request could not be read."));
    }

    private void run(RoutingContext ctx, Callable<Future<?>> action) {
        try {
            action.call().onFailure(error -> fail(ctx, error));
        } catch (Throwable error) {
            fail(ctx, error);
        }
    }

    private void fail(RoutingContext ctx, Throwable error) {
        if (ctx.response().ended() || ctx.response().headWritten()) {
            return;
        }
        ApiException safe =
                error instanceof ApiException
                        ? (ApiException) error
                        : new ApiException(
                                500, "INTERNAL_ERROR", "The operation failed. Please try again.");
        json(
                ctx,
                safe.status,
                new JsonObject().put("error", safe.getMessage()).put("code", safe.code));
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String encoded = body instanceof JsonObject ? ((JsonObject) body).encode() : ((JsonArray) body).encode();
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .end(encoded);
    }

    private JsonObject body(RoutingContext ctx) {
        try {
            JsonObject value = ctx.body().asJsonObject();
            if (value != null) {
                return value;
            }
        } catch (RuntimeException ignored) {
            // Return one consistent client error for malformed and non-object JSON bodies.
        }
        throw new ApiException(400, "INVALID_REQUEST", "A JSON object is required.");
    }

    private String text(JsonObject body, String field, int min, int max) {
        Object raw = body.getValue(field);
        if (!(raw instanceof String value)
                || value.length() < min
                || value.length() > max
                || value.isBlank()) {
            throw new ApiException(
                    400,
                    "INVALID_REQUEST",
                    field + " must contain " + min + " to " + max + " characters.");
        }
        return value;
    }

    private JsonObject user(RoutingContext ctx) {
        return ctx.get("user");
    }

    private String userId(RoutingContext ctx) {
        return user(ctx).getString("_id");
    }

    private JsonObject publicUser(JsonObject user) {
        return new JsonObject()
                .put("userId", user.getString("_id"))
                .put("name", user.getString("name"));
    }

    // Authentication

    private Future<?> register(RoutingContext ctx) {
        JsonObject input = body(ctx);
        String name = text(input, "name", 3, 80).trim();
        if (name.length() < 3 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(
                    400,
                    "INVALID_REQUEST",
                    "Name must contain at least 3 characters and no control characters.");
        }
        String password = text(input, "password", 12, 256);

        return worker(() -> PasswordHasher.hash(password))
                .compose(
                        passwordHash -> {
                            JsonObject newUser =
                                    new JsonObject()
                                            .put("_id", UUID.randomUUID().toString())
                                            .put("name", name)
                                            .put("canonicalName", name.toLowerCase(Locale.ROOT))
                                            .put("passwordHash", passwordHash)
                                            .put("createdAt", Instant.now().toString());
                            return mongo.insert(USERS, newUser)
                                    .recover(
                                            error -> {
                                                if (error.getMessage() != null
                                                        && error.getMessage().contains("E11000")) {
                                                    return Future.failedFuture(
                                                            new ApiException(
                                                                    409,
                                                                    "NAME_TAKEN",
                                                                    "That account name is already in use."));
                                                }
                                                return Future.failedFuture(error);
                                            })
                                    .compose(ignored -> sessions.create(ctx, newUser))
                                    .map(
                                            ignored -> {
                                                json(ctx, 201, publicUser(newUser));
                                                return null;
                                            });
                        });
    }

    private Future<?> login(RoutingContext ctx) {
        JsonObject input = body(ctx);
        String name = text(input, "name", 3, 80).trim();
        String password = text(input, "password", 1, 256);
        JsonObject query =
                new JsonObject().put("canonicalName", name.toLowerCase(Locale.ROOT));

        return mongo.findOne(USERS, query, null)
                .compose(
                        foundUser ->
                                worker(
                                                () ->
                                                        PasswordHasher.verify(
                                                                password,
                                                                foundUser == null
                                                                        ? PasswordHasher.DUMMY_HASH
                                                                        : foundUser.getString(
                                                                                "passwordHash")))
                                        .compose(
                                                validPassword -> {
                                                    if (!validPassword || foundUser == null) {
                                                        return Future.failedFuture(
                                                                new ApiException(
                                                                        401,
                                                                        "INVALID_CREDENTIALS",
                                                                        "Incorrect account name or password."));
                                                    }
                                                    return sessions.create(ctx, foundUser)
                                                            .map(
                                                                    ignored -> {
                                                                        json(
                                                                                ctx,
                                                                                200,
                                                                                publicUser(foundUser));
                                                                        return null;
                                                                    });
                                                }));
    }

    // File metadata and access checks

    private Future<?> listFiles(RoutingContext ctx) {
        String id = userId(ctx);
        JsonObject ownedQuery =
                new JsonObject()
                        .put("ownerId", id)
                        .put(
                                "status",
                                new JsonObject()
                                        .put("$in", new JsonArray().add("ACTIVE").add("DELETING")));
        JsonObject sharedQuery =
                new JsonObject()
                        .put("status", "ACTIVE")
                        .put(
                                "shares",
                                new JsonObject()
                                        .put(
                                                "$elemMatch",
                                                new JsonObject()
                                                        .put("recipientId", id)
                                                        .put("revoked", false)));
        JsonObject query =
                new JsonObject().put("$or", new JsonArray().add(ownedQuery).add(sharedQuery));

        return mongo.find(FILES, query)
                .map(
                        files -> {
                            JsonArray owned = new JsonArray();
                            JsonArray shared = new JsonArray();
                            for (JsonObject file : files) {
                                if (id.equals(file.getString("ownerId"))) {
                                    owned.add(publicFile(file, null));
                                } else {
                                    shared.add(publicFile(file, findShare(file, id)));
                                }
                            }
                            json(
                                    ctx,
                                    200,
                                    new JsonObject().put("owned", owned).put("shared", shared));
                            return null;
                        });
    }

    private Future<JsonObject> file(String id) {
        return mongo.findOne(FILES, new JsonObject().put("_id", id), null)
                .compose(
                        file ->
                                file == null
                                        ? Future.failedFuture(
                                                new ApiException(
                                                        404, "FILE_NOT_FOUND", "File not found."))
                                        : Future.succeededFuture(file));
    }

    private Future<JsonObject> owned(RoutingContext ctx, boolean allowDeleted) {
        return file(ctx.pathParam("fileId"))
                .compose(
                        file -> {
                            if (!userId(ctx).equals(file.getString("ownerId"))) {
                                return Future.failedFuture(
                                        new ApiException(
                                                403,
                                                "OWNER_REQUIRED",
                                                "Only the file owner can perform this action."));
                            }
                            if (!allowDeleted && !"ACTIVE".equals(file.getString("status"))) {
                                return Future.failedFuture(
                                        new ApiException(
                                                410,
                                                "FILE_DELETED",
                                                "This file is no longer available."));
                            }
                            return Future.succeededFuture(file);
                        });
    }

    private JsonObject publicFile(JsonObject f, JsonObject share) {
        JsonObject result = new JsonObject();
        for (String key :
                new String[] {
                    "fileId",
                    "ownerId",
                    "originalFileName",
                    "contentType",
                    "fileSize",
                    "createdAt",
                    "status"
                }) {
            result.put(key, f.getValue(key));
        }
        if (share != null) {
            result.put("share", publicShare(share));
        }
        return result;
    }

    private JsonObject publicShare(JsonObject s) {
        JsonObject result = s.copy();
        Long expires = s.getLong("expiresAt");
        result.put(
                "expiresAt",
                expires == null ? null : Instant.ofEpochMilli(expires).toString());
        Integer limit = s.getInteger("maxDownloads");
        result.put(
                "remainingDownloads",
                limit == null ? null : Math.max(0, limit - s.getInteger("downloadCount", 0)));
        return result;
    }

    private JsonArray publicShares(JsonObject f) {
        JsonArray shares = new JsonArray();
        f.getJsonArray("shares", new JsonArray())
                .forEach(share -> shares.add(publicShare((JsonObject) share)));
        return shares;
    }

    private JsonObject findShare(JsonObject file, String recipientId) {
        return findShare(file.getJsonArray("shares", new JsonArray()), recipientId);
    }

    private JsonObject findShare(JsonArray shares, String recipientId) {
        for (Object item : shares) {
            JsonObject share = (JsonObject) item;
            if (recipientId.equals(share.getString("recipientId"))) {
                return share;
            }
        }
        return null;
    }

    // Upload and encryption

    private Future<?> upload(RoutingContext ctx) {
        if (ctx.fileUploads().size() != 1) {
            throw new ApiException(400, "INVALID_FILE", "Select exactly one file.");
        }

        FileUpload upload = ctx.fileUploads().iterator().next();
        String code = ctx.request().getFormAttribute("code");
        text(new JsonObject().put("code", code), "code", 8, 256);
        if (upload.size() <= 0 || upload.size() > maxUpload) {
            throw new ApiException(
                    400,
                    "INVALID_FILE",
                    "Select a nonempty file within the upload size limit.");
        }

        String original = safeFileName(upload.fileName());
        if (original.isBlank() || original.length() > 255) {
            throw new ApiException(400, "INVALID_FILE", "The filename is invalid or too long.");
        }

        String contentType = upload.contentType();
        if (contentType == null
                || !contentType.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) {
            contentType = "application/octet-stream";
        }

        String id = UUID.randomUUID().toString();
        String ownerId = userId(ctx);
        JsonObject file =
                new JsonObject()
                        .put("_id", id)
                        .put("fileId", id)
                        .put("ownerId", ownerId)
                        .put("storageObjectKey", ownerId + "/" + id)
                        .put("originalFileName", original)
                        .put("contentType", contentType)
                        .put("fileSize", upload.size())
                        .put("createdAt", Instant.now().toString())
                        .put("uploadStartedAt", System.currentTimeMillis())
                        .put("status", "PENDING")
                        .put("revision", 0L)
                        .put("shares", new JsonArray());

        // Keep metadata inaccessible until the encrypted object is safely stored.
        return mongo.insert(FILES, file)
                .compose(ignored -> encryptAndStore(upload, code, file))
                .compose(ignored -> activateUploadedFile(file))
                .map(
                        ignored -> {
                            file.put("status", "ACTIVE");
                            json(ctx, 201, publicFile(file, null));
                            return null;
                        })
                .recover(error -> markFailedUploadForCleanup(file).compose(ignored -> Future.failedFuture(error)));
    }

    private String safeFileName(String fileName) {
        String normalized = fileName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.replaceAll("[\\p{Cntrl}]", "");
    }

    private Future<Void> encryptAndStore(FileUpload upload, String code, JsonObject file) {
        return worker(
                () -> {
                    Path encrypted = privateTemp("encrypted-");
                    try {
                        try (InputStream input =
                                        Files.newInputStream(Path.of(upload.uploadedFileName()));
                                OutputStream output = Files.newOutputStream(encrypted)) {
                            Encryptor.encryptTo(input, output, code);
                        }
                        storage.upload(encrypted, file.getString("storageObjectKey"));
                        return null;
                    } finally {
                        Files.deleteIfExists(encrypted);
                    }
                });
    }

    private Future<Void> activateUploadedFile(JsonObject file) {
        return mongo.updateCollection(
                        FILES,
                        new JsonObject()
                                .put("_id", file.getString("_id"))
                                .put("status", "PENDING"),
                        new JsonObject().put("$set", new JsonObject().put("status", "ACTIVE")))
                .map(
                        result -> {
                            if (result.getDocModified() != 1) {
                                throw new ApiException(
                                        503,
                                        "UPLOAD_FAILED",
                                        "Upload could not be completed. Please retry.");
                            }
                            return null;
                        });
    }

    private Future<Void> markFailedUploadForCleanup(JsonObject file) {
        return mongo.updateCollection(
                        FILES,
                        new JsonObject().put("_id", file.getString("_id")),
                        new JsonObject().put("$set", new JsonObject().put("status", "DELETING")))
                .compose(ignored -> cleanup(file))
                .recover(ignored -> Future.succeededFuture());
    }

    private Path privateTemp(String prefix) throws Exception {
        try {
            return Files.createTempFile(
                    uploads,
                    prefix,
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            // Windows applies access through the upload directory's ACL.
            return Files.createTempFile(uploads, prefix, ".tmp");
        }
    }

    // Sharing

    private JsonObject policy(JsonObject input) {
        Integer maxDownloads = null;
        Object rawLimit = input.getValue("maxDownloads");
        if (rawLimit != null) {
            if (!(rawLimit instanceof Number)
                    || ((Number) rawLimit).doubleValue() != ((Number) rawLimit).intValue()) {
                throw invalidDownloadLimit();
            }
            maxDownloads = ((Number) rawLimit).intValue();
            if (maxDownloads != 1 && maxDownloads != 3 && maxDownloads != 5) {
                throw invalidDownloadLimit();
            }
        }

        Long expiresAt = parseExpiration(input.getValue("expiresAt"));
        return new JsonObject().put("maxDownloads", maxDownloads).put("expiresAt", expiresAt);
    }

    private ApiException invalidDownloadLimit() {
        return new ApiException(
                400,
                "INVALID_POLICY",
                "Choose one, three, five, or unlimited downloads.");
    }

    private Long parseExpiration(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new ApiException(
                    400, "INVALID_POLICY", "Expiration must be a UTC date and time.");
        }

        final long timestamp;
        try {
            timestamp = Instant.parse((String) value).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException error) {
            throw new ApiException(
                    400, "INVALID_POLICY", "Expiration must be a valid UTC date and time.");
        }
        if (timestamp <= System.currentTimeMillis()) {
            throw new ApiException(
                    400, "INVALID_POLICY", "Choose an expiration in the future.");
        }
        return timestamp;
    }

    private Future<?> mutateShare(RoutingContext ctx, boolean existing, boolean revoke) {
        JsonObject input = revoke ? new JsonObject() : body(ctx);
        String recipientId =
                existing
                        ? ctx.pathParam("recipientId")
                        : text(input, "recipientId", 1, 80);
        JsonObject sharePolicy = revoke ? null : policy(input);
        if (recipientId.equals(userId(ctx))) {
            throw new ApiException(
                    400, "INVALID_RECIPIENT", "Owners already have access to their own files.");
        }

        return mongo.findOne(USERS, new JsonObject().put("_id", recipientId), null)
                .compose(
                        recipient -> {
                            if (recipient == null) {
                                return Future.failedFuture(
                                        new ApiException(
                                                404,
                                                "RECIPIENT_NOT_FOUND",
                                                "Recipient not found."));
                            }
                            return changeShare(
                                    ctx,
                                    recipientId,
                                    recipient.getString("name"),
                                    sharePolicy,
                                    existing,
                                    revoke,
                                    0);
                        });
    }

    private Future<Void> changeShare(
            RoutingContext ctx,
            String recipientId,
            String recipientName,
            JsonObject policy,
            boolean existing,
            boolean revoke,
            int attempt) {
        if (attempt > 20) {
            return Future.failedFuture(
                    new ApiException(
                            409,
                            "RETRY_REQUIRED",
                            "Sharing changed concurrently. Please retry."));
        }

        return owned(ctx, false)
                .compose(
                        file -> {
                            JsonArray shares = file.getJsonArray("shares").copy();
                            JsonObject currentShare = findShare(shares, recipientId);

                            if (existing && currentShare == null) {
                                return Future.failedFuture(
                                        new ApiException(
                                                404,
                                                "SHARE_NOT_FOUND",
                                                "Sharing permission not found."));
                            }

                            if (currentShare == null) {
                                currentShare =
                                        new JsonObject()
                                                .put("recipientId", recipientId)
                                                .put("recipientName", recipientName)
                                                .put("downloadCount", 0)
                                                .put("createdAt", Instant.now().toString());
                                shares.add(currentShare);
                            }
                            if (!revoke) {
                                currentShare.mergeIn(policy);
                            }
                            currentShare
                                    .put("revoked", revoke)
                                    .put("updatedAt", Instant.now().toString());
                            JsonObject responseShare = currentShare;

                            // Retry if another request changed the file after we read it.
                            return mongo.updateCollection(
                                            FILES,
                                            versionQuery(file),
                                            new JsonObject()
                                                    .put("$set", new JsonObject().put("shares", shares))
                                                    .put(
                                                            "$inc",
                                                            new JsonObject().put("revision", 1)))
                                    .compose(
                                            result -> {
                                                if (result.getDocModified() != 1) {
                                                    return changeShare(
                                                            ctx,
                                                            recipientId,
                                                            recipientName,
                                                            policy,
                                                            existing,
                                                            revoke,
                                                            attempt + 1);
                                                }
                                                json(
                                                        ctx,
                                                        existing ? 200 : 201,
                                                        publicShare(responseShare));
                                                return Future.succeededFuture();
                                            });
                        });
    }

    private JsonObject versionQuery(JsonObject file) {
        return new JsonObject()
                .put("_id", file.getString("_id"))
                .put("status", "ACTIVE")
                .put("revision", file.getLong("revision"));
    }

    // Download authorization

    private void checkAccess(JsonObject file, String requesterId) {
        if (!"ACTIVE".equals(file.getString("status"))) {
            throw new ApiException(
                    410, "FILE_DELETED", "This file is no longer available.");
        }
        if (requesterId.equals(file.getString("ownerId"))) {
            return;
        }

        JsonObject share = findShare(file, requesterId);
        if (share == null || share.getBoolean("revoked", false)) {
            throw new ApiException(
                    403, "ACCESS_DENIED", "You do not have access to this file.");
        }
        Long expiresAt = share.getLong("expiresAt");
        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
            throw new ApiException(
                    403, "SHARE_EXPIRED", "Access to this file has expired.");
        }
        Integer maxDownloads = share.getInteger("maxDownloads");
        if (maxDownloads != null
                && share.getInteger("downloadCount", 0) >= maxDownloads) {
            throw new ApiException(
                    403,
                    "DOWNLOAD_LIMIT_REACHED",
                    "The download allowance for this file is exhausted.");
        }
    }

    private Future<?> download(RoutingContext ctx) {
        String code = text(body(ctx), "code", 1, 256);
        String fileId = ctx.pathParam("fileId");
        String requesterId = userId(ctx);

        return file(fileId)
                .compose(
                        file -> {
                            checkAccess(file, requesterId);
                            return decryptToPrivateFile(file, code)
                                    .compose(
                                            plaintext ->
                                                    authorizeAndSend(
                                                            ctx,
                                                            file,
                                                            fileId,
                                                            requesterId,
                                                            plaintext));
                        });
    }

    private Future<Path> decryptToPrivateFile(JsonObject file, String code) {
        return this.<Path>worker(
                () -> {
                    Path plaintext = privateTemp("download-");
                    try {
                        try (InputStream encrypted =
                                        storage.download(file.getString("storageObjectKey"));
                                OutputStream output = Files.newOutputStream(plaintext)) {
                            Encryptor.decryptTo(encrypted, output, code);
                        }
                        return plaintext;
                    } catch (Exception error) {
                        Files.deleteIfExists(plaintext);
                        if (error instanceof Encryptor.UnsupportedEncryptedFileException) {
                            throw new ApiException(
                                    422,
                                    "LEGACY_ENCRYPTION",
                                    "This file requires a separate legacy migration.");
                        }
                        if (error instanceof java.security.GeneralSecurityException) {
                            throw new ApiException(
                                    400,
                                    "INVALID_CODE",
                                    "The encryption code is incorrect or the file could not be authenticated.");
                        }
                        throw new ApiException(
                                503,
                                "STORAGE_UNAVAILABLE",
                                "File storage is unavailable. Please try again.");
                    }
                });
    }

    private Future<Void> authorizeAndSend(
            RoutingContext ctx,
            JsonObject file,
            String fileId,
            String requesterId,
            Path plaintext) {
        // Decryption writes only to a private temp file. GCM must authenticate the complete
        // file before that temp file is sent or the recipient's download is reserved.
        if (ctx.response().closed()) {
            return deleteTemp(plaintext);
        }

        return consume(fileId, requesterId, 0)
                .<Void>compose(
                        ignored -> {
                            ctx.response()
                                    .putHeader("Content-Type", file.getString("contentType"))
                                    .putHeader(
                                            "Content-Disposition",
                                            contentDisposition(
                                                    file.getString("originalFileName")));
                            return ctx.response().sendFile(plaintext.toString()).mapEmpty();
                        })
                .eventually(() -> deleteTemp(plaintext));
    }

    private String contentDisposition(String fileName) {
        String encodedName =
                URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encodedName;
    }

    private Future<Void> deleteTemp(Path path) {
        return this.<Void>worker(
                () -> {
                    Files.deleteIfExists(path);
                    return null;
                });
    }

    private Future<Void> consume(String fileId, String requesterId, int attempt) {
        if (attempt > 30) {
            return Future.failedFuture(
                    new ApiException(
                            409,
                            "RETRY_REQUIRED",
                            "Access changed concurrently. Please retry."));
        }

        return file(fileId)
                .compose(
                        file -> {
                            checkAccess(file, requesterId);
                            JsonObject query = versionQuery(file);
                            JsonObject update =
                                    new JsonObject()
                                            .put(
                                                    "$inc",
                                                    new JsonObject().put("revision", 1));

                            if (!requesterId.equals(file.getString("ownerId"))) {
                                addRecipientDownload(file, requesterId, query, update);
                            }

                            // This conditional update is the moment a recipient's allowance
                            // is consumed; competing requests retry against the latest revision.
                            return mongo.updateCollection(FILES, query, update)
                                    .compose(
                                            result ->
                                                    result.getDocModified() == 1
                                                            ? Future.succeededFuture()
                                                            : consume(
                                                                    fileId,
                                                                    requesterId,
                                                                    attempt + 1));
                        });
    }

    private void addRecipientDownload(
            JsonObject file, String requesterId, JsonObject query, JsonObject update) {
        JsonObject share = findShare(file, requesterId);
        Long expiresAt = share.getLong("expiresAt");
        if (expiresAt != null) {
            // Compare expiry with Mongo's clock inside the atomic update.
            query.put(
                    "$expr",
                    new JsonObject()
                            .put(
                                    "$gt",
                                    new JsonArray()
                                            .add(expiresAt)
                                            .add(new JsonObject().put("$toLong", "$$NOW"))));
        }

        JsonArray shares = file.getJsonArray("shares").copy();
        for (Object value : shares) {
            JsonObject current = (JsonObject) value;
            if (requesterId.equals(current.getString("recipientId"))) {
                current.put(
                                "downloadCount",
                                current.getInteger("downloadCount", 0) + 1)
                        .put("updatedAt", Instant.now().toString());
            }
        }
        update.put("$set", new JsonObject().put("shares", shares));
    }

    // Deletion and recovery

    private Future<?> delete(RoutingContext ctx) {
        return owned(ctx, true)
                .compose(
                        file -> {
                            if ("DELETED".equals(file.getString("status"))) {
                                ctx.response().setStatusCode(204).end();
                                return Future.succeededFuture();
                            }
                            return markFileDeleting(file, userId(ctx))
                                    .compose(
                                            ignored ->
                                                    cleanup(file)
                                                            .recover(
                                                                    error ->
                                                                            Future.failedFuture(
                                                                                    new ApiException(
                                                                                            503,
                                                                                            "DELETION_PENDING",
                                                                                            "Access has been revoked. Storage cleanup is pending; retry deletion to finish."))))
                                    .map(
                                            ignored -> {
                                                ctx.response().setStatusCode(204).end();
                                                return null;
                                            });
                        });
    }

    private Future<Void> markFileDeleting(JsonObject file, String ownerId) {
        return mongo.updateCollection(
                        FILES,
                        new JsonObject()
                                .put("_id", file.getString("_id"))
                                .put("ownerId", ownerId)
                                .put("status", new JsonObject().put("$ne", "DELETED")),
                        new JsonObject()
                                .put("$set", new JsonObject().put("status", "DELETING"))
                                .put("$inc", new JsonObject().put("revision", 1)))
                .recover(
                        error ->
                                Future.failedFuture(
                                        new ApiException(
                                                503,
                                                "DELETION_FAILED",
                                                "Access could not be revoked. Please retry deletion.")))
                .mapEmpty();
    }

    private Future<Void> cleanup(JsonObject file) {
        return worker(
                        () -> {
                            storage.delete(file.getString("storageObjectKey"));
                            return null;
                        })
                .compose(
                        ignored ->
                                mongo.updateCollection(
                                        FILES,
                                        new JsonObject()
                                                .put("_id", file.getString("_id"))
                                                .put("status", "DELETING"),
                                        new JsonObject()
                                                .put(
                                                        "$set",
                                                        new JsonObject()
                                                                .put("status", "DELETED")
                                                                .put("shares", new JsonArray())
                                                                .put(
                                                                        "deletedAt",
                                                                        Instant.now().toString()))))
                .mapEmpty();
    }

    private void retryDeletions() {
        // Abandoned uploads are private; after an hour they can be cleaned like deletions.
        long staleUploadCutoff = System.currentTimeMillis() - 3_600_000;
        mongo.updateCollection(
                        FILES,
                        new JsonObject()
                                .put("status", "PENDING")
                                .put(
                                        "uploadStartedAt",
                                        new JsonObject().put("$lt", staleUploadCutoff)),
                        new JsonObject()
                                .put("$set", new JsonObject().put("status", "DELETING")))
                .compose(ignored -> mongo.find(FILES, new JsonObject().put("status", "DELETING")))
                .onSuccess(
                        files ->
                                files.forEach(
                                        file ->
                                                cleanup(file)
                                                        .onFailure(
                                                                ignored ->
                                                                        System.err.println(
                                                                                "A file deletion remains pending; the next scheduled retry will try again."))))
                .onFailure(
                        ignored ->
                                System.err.println(
                                        "Deletion recovery could not reach the database; it will retry."));
    }
}
