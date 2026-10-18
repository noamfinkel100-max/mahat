package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Authenticated API. A file and its shares form one MongoDB atomic authorization boundary. */
public final class Api {
    static final String USERS = "users_v2", FILES = "files_v2", SESSIONS = "sessions_v2";
    private static final String COOKIE = "cyphernod_session";
    private static final long SESSION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Vertx vertx;
    private final MongoClient mongo;
    private final ObjectStorage storage;
    private final JsonObject config;
    private final Router router;
    private final Path uploads;
    private final long maxUpload;
    private final boolean secure;
    private long retryTimer;

    public Api(Vertx vertx, JsonObject config) { this(vertx, config, new ObjectStorage(config)); }

    public Api(Vertx vertx, JsonObject config, ObjectStorage objectStorage) {
        this.vertx = vertx; this.config = config;
        mongo = MongoClient.create(vertx, new JsonObject().put("connection_string", config.getString("MONGO_URL", "mongodb://localhost:27017"))
                .put("db_name", config.getString("MONGO_DB_NAME", "cyphernod")));
        storage = objectStorage;
        uploads = Path.of(config.getString("UPLOAD_DIR", System.getProperty("java.io.tmpdir") + "/cyphernod"));
        maxUpload = Long.parseLong(String.valueOf(config.getValue("MAX_UPLOAD_BYTES", 100L * 1024 * 1024)));
        if (maxUpload < 1 || maxUpload > 1024L * 1024 * 1024) throw new IllegalArgumentException("MAX_UPLOAD_BYTES must be between 1 and 1GiB");
        secure = Boolean.parseBoolean(String.valueOf(config.getValue("SESSION_SECURE", true)));
        router = Router.router(vertx);
    }

    public Future<Void> initialize() {
        return this.<Void>worker(() -> { Files.createDirectories(uploads);
            try { Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { /* Windows uses its directory ACL. */ }
            return null; })
                .recover(error -> Future.failedFuture(StartupFailure.at(StartupFailure.Stage.UPLOAD_DIRECTORY, error)))
                .compose(v -> this.<Void>worker(() -> { storage.initialize(); return null; })
                        .recover(error -> Future.failedFuture(StartupFailure.at(StartupFailure.Stage.STORAGE, error))))
                .compose(v -> initializeIndexes().recover(error -> Future.failedFuture(StartupFailure.at(StartupFailure.Stage.DATABASE, error))))
                .map(v -> { routes(); retryTimer = vertx.setPeriodic(60_000, ignored -> retryDeletions()); retryDeletions(); return null; });
    }

    private Future<Void> initializeIndexes() {
        return mongo.createIndexWithOptions(USERS, new JsonObject().put("canonicalName", 1), new IndexOptions(new JsonObject().put("unique", true)))
                .compose(v -> mongo.createIndex(FILES, new JsonObject().put("ownerId", 1).put("status", 1)))
                .compose(v -> mongo.createIndex(FILES, new JsonObject().put("shares.recipientId", 1).put("status", 1)))
                .compose(v -> mongo.createIndex(FILES, new JsonObject().put("status", 1)))
                .compose(v -> mongo.createIndexWithOptions(SESSIONS, new JsonObject().put("expiresAt", 1), new IndexOptions(new JsonObject().put("expireAfterSeconds", 0))))
                .mapEmpty();
    }
    public Router router() { return router; }
    public void close() { if (retryTimer != 0) vertx.cancelTimer(retryTimer); mongo.close(); }
    private <T> Future<T> worker(Callable<T> action) { return vertx.executeBlocking(action, false); }

    private void routes() {
        router.route().handler(ctx -> {
            ctx.response().putHeader("X-Content-Type-Options", "nosniff").putHeader("Cache-Control", "no-store");
            // Cookie auth is same-origin. Only an explicitly configured trusted dev origin may differ.
            String origin = ctx.request().getHeader("Origin");
            if ("cross-site".equals(ctx.request().getHeader("Sec-Fetch-Site"))) {
                fail(ctx, new ApiException(403, "ORIGIN_DENIED", "This request origin is not allowed.")); return;
            }
            if (origin != null && !origin.equals(ctx.request().scheme() + "://" + ctx.request().host())
                    && !origin.equals(config.getString("ALLOWED_ORIGIN"))) {
                fail(ctx, new ApiException(403, "ORIGIN_DENIED", "This request origin is not allowed.")); return;
            }
            ctx.next();
        });
        router.get("/api/health").handler(ctx -> mongo.runCommand("ping", new JsonObject().put("ping", 1))
                .onSuccess(v -> json(ctx, 200, new JsonObject().put("status", "ok")))
                .onFailure(e -> fail(ctx, new ApiException(503, "UNAVAILABLE", "Database is unavailable."))));
        BodyHandler jsonBody = BodyHandler.create().setBodyLimit(16 * 1024).setHandleFileUploads(false);
        router.route().handler(ctx -> {
            String path = ctx.request().path();
            boolean jsonRoute = !path.equals("/api/files") && !path.equals("/api/auth/logout") && (ctx.request().method().name().equals("POST") || ctx.request().method().name().equals("PUT"));
            if (jsonRoute && (ctx.request().getHeader("Content-Type") == null || !ctx.request().getHeader("Content-Type").toLowerCase(Locale.ROOT).startsWith("application/json"))) {
                fail(ctx, new ApiException(415, "CONTENT_TYPE_REQUIRED", "Use application/json for this request.")); return;
            }
            ctx.next();
        });
        router.post("/api/auth/register").handler(jsonBody).handler(ctx -> run(ctx, () -> register(ctx)));
        router.post("/api/auth/login").handler(jsonBody).handler(ctx -> run(ctx, () -> login(ctx)));
        router.route("/api/*").handler(ctx -> {
            // Authentication performs asynchronous database I/O. Pause the request so multipart
            // bytes cannot pass before BodyHandler has installed its stream consumers.
            ctx.request().pause();
            authenticate(ctx).onSuccess(user -> { ctx.put("user", user); ctx.next(); ctx.request().resume(); })
                    .onFailure(error -> { fail(ctx, error); ctx.request().resume(); });
        });
        router.get("/api/auth/me").handler(ctx -> json(ctx, 200, publicUser(user(ctx))));
        router.post("/api/auth/logout").handler(ctx -> run(ctx, () -> mongo.removeDocument(SESSIONS,
                new JsonObject().put("_id", tokenHash(ctx.getCookie(COOKIE).getValue()))).map(v -> {
                    ctx.addCookie(cookie("", 0)); ctx.response().setStatusCode(204).end(); return null;
                })));
        router.get("/api/users").handler(ctx -> run(ctx, () -> mongo.find(USERS, new JsonObject()).map(users -> {
            JsonArray result = new JsonArray(); users.forEach(u -> result.add(publicUser(u))); json(ctx, 200, result); return null;
        })));
        router.get("/api/files").handler(ctx -> run(ctx, () -> listFiles(ctx)));
        router.post("/api/files").handler(BodyHandler.create().setBodyLimit(maxUpload + 64 * 1024)
                .setUploadsDirectory(uploads.toString()).setDeleteUploadedFilesOnEnd(true))
                .handler(ctx -> run(ctx, () -> upload(ctx)));
        router.get("/api/files/:fileId/shares").handler(ctx -> run(ctx, () -> owned(ctx, false).map(file -> {
            json(ctx, 200, publicShares(file)); return null;
        })));
        router.post("/api/files/:fileId/shares").handler(jsonBody).handler(ctx -> run(ctx, () -> mutateShare(ctx, false, false)));
        router.put("/api/files/:fileId/shares/:recipientId").handler(jsonBody).handler(ctx -> run(ctx, () -> mutateShare(ctx, true, false)));
        router.delete("/api/files/:fileId/shares/:recipientId").handler(ctx -> run(ctx, () -> mutateShare(ctx, true, true)));
        router.post("/api/files/:fileId/download").handler(jsonBody).handler(ctx -> run(ctx, () -> download(ctx)));
        router.delete("/api/files/:fileId").handler(ctx -> run(ctx, () -> delete(ctx)));
        router.route().handler(ctx -> fail(ctx, new ApiException(404, "NOT_FOUND", "Endpoint not found.")));
        router.route().failureHandler(ctx -> {
            int status = ctx.statusCode();
            fail(ctx, new ApiException(status == 413 ? 413 : 400, status == 413 ? "FILE_TOO_LARGE" : "INVALID_REQUEST",
                    status == 413 ? "The uploaded file is too large." : "The request could not be read."));
        });
    }
    private void run(RoutingContext ctx, Callable<Future<?>> action) {
        try { action.call().onFailure(error -> fail(ctx, error)); } catch (Throwable error) { fail(ctx, error); }
    }
    private void fail(RoutingContext ctx, Throwable error) {
        if (ctx.response().ended() || ctx.response().headWritten()) return;
        ApiException safe = error instanceof ApiException ? (ApiException) error : new ApiException(500, "INTERNAL_ERROR", "The operation failed. Please try again.");
        json(ctx, safe.status, new JsonObject().put("error", safe.getMessage()).put("code", safe.code));
    }
    private void json(RoutingContext ctx, int status, Object body) {
        ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json; charset=utf-8")
                .end(body instanceof JsonObject ? ((JsonObject) body).encode() : ((JsonArray) body).encode());
    }
    private JsonObject body(RoutingContext ctx) {
        try { JsonObject value = ctx.body().asJsonObject(); if (value != null) return value; }
        catch (RuntimeException ignored) { }
        throw new ApiException(400, "INVALID_REQUEST", "A JSON object is required.");
    }
    private String text(JsonObject body, String field, int min, int max) {
        Object raw = body.getValue(field);
        if (!(raw instanceof String value) || value.length() < min || value.length() > max || value.isBlank())
            throw new ApiException(400, "INVALID_REQUEST", field + " must contain " + min + " to " + max + " characters.");
        return value;
    }
    private JsonObject user(RoutingContext ctx) { return ctx.get("user"); }
    private String userId(RoutingContext ctx) { return user(ctx).getString("_id"); }
    private JsonObject publicUser(JsonObject u) { return new JsonObject().put("userId", u.getString("_id")).put("name", u.getString("name")); }

    private Future<?> register(RoutingContext ctx) {
        JsonObject input = body(ctx); String name = text(input, "name", 3, 80).trim();
        if (name.length() < 3 || name.chars().anyMatch(Character::isISOControl))
            throw new ApiException(400, "INVALID_REQUEST", "Name must contain at least 3 characters and no control characters.");
        String password = text(input, "password", 12, 256);
        return worker(() -> PasswordHasher.hash(password)).compose(hash -> {
            JsonObject u = new JsonObject().put("_id", UUID.randomUUID().toString()).put("name", name)
                    .put("canonicalName", name.toLowerCase(Locale.ROOT)).put("passwordHash", hash).put("createdAt", Instant.now().toString());
            return mongo.insert(USERS, u).recover(error -> Future.failedFuture(error.getMessage() != null && error.getMessage().contains("E11000")
                    ? new ApiException(409, "NAME_TAKEN", "That account name is already in use.") : error))
                    .compose(v -> createSession(ctx, u)).map(v -> { json(ctx, 201, publicUser(u)); return null; });
        });
    }
    private Future<?> login(RoutingContext ctx) {
        JsonObject input = body(ctx); String name = text(input, "name", 3, 80).trim(); String password = text(input, "password", 1, 256);
        return mongo.findOne(USERS, new JsonObject().put("canonicalName", name.toLowerCase(Locale.ROOT)), null).compose(u ->
                worker(() -> PasswordHasher.verify(password, u == null ? PasswordHasher.DUMMY_HASH : u.getString("passwordHash")))
                        .compose(valid -> {
                            if (!valid || u == null) return Future.failedFuture(new ApiException(401, "INVALID_CREDENTIALS", "Incorrect account name or password."));
                            return createSession(ctx, u).map(v -> { json(ctx, 200, publicUser(u)); return null; });
                        }));
    }
    private Future<Void> createSession(RoutingContext ctx, JsonObject u) {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String old = ctx.getCookie(COOKIE) == null ? null : ctx.getCookie(COOKIE).getValue();
        Future<?> remove = old == null ? Future.succeededFuture() : mongo.removeDocument(SESSIONS, new JsonObject().put("_id", tokenHash(old)));
        return remove.compose(v -> mongo.insert(SESSIONS, new JsonObject().put("_id", tokenHash(token)).put("userId", u.getString("_id"))
                .put("expiresAt", new JsonObject().put("$date", Instant.ofEpochMilli(System.currentTimeMillis() + SESSION_MILLIS).toString()))))
                .map(v -> { ctx.addCookie(cookie(token, SESSION_MILLIS / 1000)); return null; });
    }
    private Cookie cookie(String token, long age) { return Cookie.cookie(COOKIE, token).setPath("/").setHttpOnly(true).setSecure(secure).setSameSite(CookieSameSite.STRICT).setMaxAge(age); }
    private static String tokenHash(String token) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Future<JsonObject> authenticate(RoutingContext ctx) {
        Cookie cookie = ctx.getCookie(COOKIE);
        if (cookie == null || cookie.getValue().length() > 128) return Future.failedFuture(unauthorized());
        return mongo.findOne(SESSIONS, new JsonObject().put("_id", tokenHash(cookie.getValue()))
                .put("expiresAt", new JsonObject().put("$gt", new JsonObject().put("$date", Instant.now().toString()))), null)
                .compose(session -> session == null ? Future.failedFuture(unauthorized()) : mongo.findOne(USERS, new JsonObject().put("_id", session.getString("userId")), null))
                .compose(u -> u == null ? Future.failedFuture(unauthorized()) : Future.succeededFuture(u));
    }
    private ApiException unauthorized() { return new ApiException(401, "UNAUTHENTICATED", "Sign in to continue."); }

    private Future<?> listFiles(RoutingContext ctx) {
        String id = userId(ctx);
        return mongo.find(FILES, new JsonObject().put("$or", new JsonArray()
                .add(new JsonObject().put("ownerId", id).put("status", new JsonObject().put("$in", new JsonArray().add("ACTIVE").add("DELETING"))))
                .add(new JsonObject().put("status", "ACTIVE").put("shares", new JsonObject().put("$elemMatch", new JsonObject().put("recipientId", id).put("revoked", false))))))
                .map(files -> {
                    JsonArray owned = new JsonArray(), shared = new JsonArray();
                    for (JsonObject file : files) {
                        if (id.equals(file.getString("ownerId"))) owned.add(publicFile(file, null));
                        else shared.add(publicFile(file, findShare(file, id)));
                    }
                    json(ctx, 200, new JsonObject().put("owned", owned).put("shared", shared)); return null;
                });
    }
    private Future<JsonObject> file(String id) {
        return mongo.findOne(FILES, new JsonObject().put("_id", id), null).compose(f -> f == null ? Future.failedFuture(new ApiException(404, "FILE_NOT_FOUND", "File not found.")) : Future.succeededFuture(f));
    }
    private Future<JsonObject> owned(RoutingContext ctx, boolean allowDeleted) {
        return file(ctx.pathParam("fileId")).compose(f -> {
            if (!userId(ctx).equals(f.getString("ownerId"))) return Future.failedFuture(new ApiException(403, "OWNER_REQUIRED", "Only the file owner can perform this action."));
            if (!allowDeleted && !"ACTIVE".equals(f.getString("status"))) return Future.failedFuture(new ApiException(410, "FILE_DELETED", "This file is no longer available."));
            return Future.succeededFuture(f);
        });
    }
    private JsonObject publicFile(JsonObject f, JsonObject share) {
        JsonObject result = new JsonObject();
        for (String key : new String[]{"fileId", "ownerId", "originalFileName", "contentType", "fileSize", "createdAt", "status"}) result.put(key, f.getValue(key));
        if (share != null) result.put("share", publicShare(share)); return result;
    }
    private JsonObject publicShare(JsonObject s) {
        JsonObject result = s.copy();
        Long expires = s.getLong("expiresAt"); result.put("expiresAt", expires == null ? null : Instant.ofEpochMilli(expires).toString());
        Integer limit = s.getInteger("maxDownloads"); result.put("remainingDownloads", limit == null ? null : Math.max(0, limit - s.getInteger("downloadCount", 0))); return result;
    }
    private JsonArray publicShares(JsonObject f) {
        JsonArray out = new JsonArray(); f.getJsonArray("shares", new JsonArray()).forEach(s -> out.add(publicShare((JsonObject) s))); return out;
    }
    private JsonObject findShare(JsonObject file, String recipientId) {
        for (Object item : file.getJsonArray("shares", new JsonArray())) {
            JsonObject s = (JsonObject) item; if (recipientId.equals(s.getString("recipientId"))) return s;
        } return null;
    }
    private Future<?> upload(RoutingContext ctx) {
        if (ctx.fileUploads().size() != 1) throw new ApiException(400, "INVALID_FILE", "Select exactly one file.");
        FileUpload upload = ctx.fileUploads().iterator().next();
        String code = ctx.request().getFormAttribute("code");
        text(new JsonObject().put("code", code), "code", 8, 256);
        if (upload.size() <= 0 || upload.size() > maxUpload) throw new ApiException(400, "INVALID_FILE", "Select a nonempty file within the upload size limit.");
        String original = upload.fileName().replace('\\', '/'); original = original.substring(original.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (original.isBlank() || original.length() > 255) throw new ApiException(400, "INVALID_FILE", "The filename is invalid or too long.");
        String type = upload.contentType();
        if (type == null || !type.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) type = "application/octet-stream";
        String id = UUID.randomUUID().toString();
        JsonObject f = new JsonObject().put("_id", id).put("fileId", id).put("ownerId", userId(ctx)).put("storageObjectKey", userId(ctx) + "/" + id)
                .put("originalFileName", original).put("contentType", type).put("fileSize", upload.size()).put("createdAt", Instant.now().toString())
                .put("uploadStartedAt", System.currentTimeMillis()).put("status", "PENDING").put("revision", 0L).put("shares", new JsonArray());
        return mongo.insert(FILES, f).compose(v -> worker(() -> {
            Path encrypted = privateTemp("encrypted-");
            try {
                try (InputStream input = Files.newInputStream(Path.of(upload.uploadedFileName())); OutputStream output = Files.newOutputStream(encrypted)) {
                    Encryptor.encryptTo(input, output, code);
                }
                storage.upload(encrypted, f.getString("storageObjectKey"));
                return null;
            } finally { Files.deleteIfExists(encrypted); }
        }).compose(ignored -> mongo.updateCollection(FILES, new JsonObject().put("_id", id).put("status", "PENDING"),
                new JsonObject().put("$set", new JsonObject().put("status", "ACTIVE")))).map(result -> {
                    if (result.getDocModified() != 1) throw new ApiException(503, "UPLOAD_FAILED", "Upload could not be completed. Please retry.");
                    f.put("status", "ACTIVE"); json(ctx, 201, publicFile(f, null)); return null;
                }).recover(error -> mongo.updateCollection(FILES, new JsonObject().put("_id", id),
                        new JsonObject().put("$set", new JsonObject().put("status", "DELETING")))
                        .compose(ignored -> cleanup(f)).recover(cleanupError -> Future.succeededFuture()).compose(ignored -> Future.failedFuture(error))));
    }
    private Path privateTemp(String prefix) throws Exception {
        try { return Files.createTempFile(uploads, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException ignored) { return Files.createTempFile(uploads, prefix, ".tmp"); }
    }
    private JsonObject policy(JsonObject input) {
        Object raw = input.getValue("maxDownloads");
        Integer max = null;
        if (raw != null) {
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).intValue()) throw new ApiException(400, "INVALID_POLICY", "Choose one, three, five, or unlimited downloads.");
            max = ((Number) raw).intValue();
            if (max != 1 && max != 3 && max != 5) throw new ApiException(400, "INVALID_POLICY", "Choose one, three, five, or unlimited downloads.");
        }
        Object expiration = input.getValue("expiresAt"); Long expires = null;
        if (expiration != null) {
            if (!(expiration instanceof String)) throw new ApiException(400, "INVALID_POLICY", "Expiration must be a UTC date and time.");
            try { expires = Instant.parse((String) expiration).toEpochMilli(); }
            catch (DateTimeParseException | ArithmeticException e) { throw new ApiException(400, "INVALID_POLICY", "Expiration must be a valid UTC date and time."); }
            if (expires <= System.currentTimeMillis()) throw new ApiException(400, "INVALID_POLICY", "Choose an expiration in the future.");
        }
        return new JsonObject().put("maxDownloads", max).put("expiresAt", expires);
    }
    private Future<?> mutateShare(RoutingContext ctx, boolean existing, boolean revoke) {
        JsonObject input = revoke ? new JsonObject() : body(ctx);
        String recipient = existing ? ctx.pathParam("recipientId") : text(input, "recipientId", 1, 80);
        JsonObject policy = revoke ? null : policy(input);
        if (recipient.equals(userId(ctx))) throw new ApiException(400, "INVALID_RECIPIENT", "Owners already have access to their own files.");
        return mongo.findOne(USERS, new JsonObject().put("_id", recipient), null).compose(u -> {
            if (u == null) return Future.failedFuture(new ApiException(404, "RECIPIENT_NOT_FOUND", "Recipient not found."));
            return changeShare(ctx, recipient, u.getString("name"), policy, existing, revoke, 0);
        });
    }
    private Future<Void> changeShare(RoutingContext ctx, String recipient, String name, JsonObject policy, boolean existing, boolean revoke, int attempt) {
        if (attempt > 20) return Future.failedFuture(new ApiException(409, "RETRY_REQUIRED", "Sharing changed concurrently. Please retry."));
        return owned(ctx, false).compose(f -> {
            JsonArray shares = f.getJsonArray("shares").copy(); JsonObject current = null;
            for (Object item : shares) if (recipient.equals(((JsonObject) item).getString("recipientId"))) current = (JsonObject) item;
            if (existing && current == null) return Future.failedFuture(new ApiException(404, "SHARE_NOT_FOUND", "Sharing permission not found."));
            if (current == null) {
                current = new JsonObject().put("recipientId", recipient).put("recipientName", name).put("downloadCount", 0)
                        .put("createdAt", Instant.now().toString()); shares.add(current);
            }
            if (!revoke) current.mergeIn(policy);
            current.put("revoked", revoke).put("updatedAt", Instant.now().toString());
            JsonObject resultShare = current;
            return mongo.updateCollection(FILES, versionQuery(f), new JsonObject().put("$set", new JsonObject().put("shares", shares)).put("$inc", new JsonObject().put("revision", 1)))
                    .compose(result -> {
                        if (result.getDocModified() != 1) return changeShare(ctx, recipient, name, policy, existing, revoke, attempt + 1);
                        json(ctx, existing ? 200 : 201, publicShare(resultShare)); return Future.succeededFuture();
                    });
        });
    }
    private JsonObject versionQuery(JsonObject f) {
        return new JsonObject().put("_id", f.getString("_id")).put("status", "ACTIVE").put("revision", f.getLong("revision"));
    }
    private void checkAccess(JsonObject f, String id) {
        if (!"ACTIVE".equals(f.getString("status"))) throw new ApiException(410, "FILE_DELETED", "This file is no longer available.");
        if (id.equals(f.getString("ownerId"))) return;
        JsonObject s = findShare(f, id);
        if (s == null || s.getBoolean("revoked", false)) throw new ApiException(403, "ACCESS_DENIED", "You do not have access to this file.");
        Long expires = s.getLong("expiresAt");
        if (expires != null && expires <= System.currentTimeMillis()) throw new ApiException(403, "SHARE_EXPIRED", "Access to this file has expired.");
        Integer limit = s.getInteger("maxDownloads");
        if (limit != null && s.getInteger("downloadCount", 0) >= limit) throw new ApiException(403, "DOWNLOAD_LIMIT_REACHED", "The download allowance for this file is exhausted.");
    }
    private Future<?> download(RoutingContext ctx) {
        String code = text(body(ctx), "code", 1, 256); String id = ctx.pathParam("fileId"); String requester = userId(ctx);
        return file(id).compose(f -> {
            checkAccess(f, requester);
            return worker(() -> {
                Path plaintext = privateTemp("download-");
                try {
                    try (InputStream input = storage.download(f.getString("storageObjectKey")); OutputStream output = Files.newOutputStream(plaintext)) {
                        Encryptor.decryptTo(input, output, code);
                    }
                    return plaintext;
                } catch (Exception e) {
                    Files.deleteIfExists(plaintext);
                    if (e instanceof Encryptor.UnsupportedEncryptedFileException) throw new ApiException(422, "LEGACY_ENCRYPTION", "This file requires a separate legacy migration.");
                    if (e instanceof java.security.GeneralSecurityException) throw new ApiException(400, "INVALID_CODE", "The encryption code is incorrect or the file could not be authenticated.");
                    throw new ApiException(503, "STORAGE_UNAVAILABLE", "File storage is unavailable. Please try again.");
                }
            }).compose(path -> {
                // Authentication and policy may have changed while decrypting. The CAS is the consumption point.
                if (ctx.response().closed()) { deleteTemp(path); return Future.succeededFuture(); }
                return consume(id, requester, 0).compose(ignored -> {
                    ctx.response().putHeader("Content-Type", f.getString("contentType"))
                            .putHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(f.getString("originalFileName"), StandardCharsets.UTF_8).replace("+", "%20"));
                    return ctx.response().sendFile(path.toString()).eventually(() -> deleteTemp(path));
                }).recover(error -> deleteTemp(path).compose(v -> Future.failedFuture(error)));
            });
        });
    }
    private Future<Void> deleteTemp(Path path) { return worker(() -> { Files.deleteIfExists(path); return null; }); }
    private Future<Void> consume(String fileId, String requester, int attempt) {
        if (attempt > 30) return Future.failedFuture(new ApiException(409, "RETRY_REQUIRED", "Access changed concurrently. Please retry."));
        return file(fileId).compose(f -> {
            checkAccess(f, requester); JsonObject query = versionQuery(f);
            JsonObject update = new JsonObject().put("$inc", new JsonObject().put("revision", 1));
            if (!requester.equals(f.getString("ownerId"))) {
                JsonObject share = findShare(f, requester); Long expires = share.getLong("expiresAt");
                if (expires != null) {
                    // Evaluate against the database clock during the atomic write, not a stale application read.
                    query.put("$expr", new JsonObject().put("$gt", new JsonArray().add(expires).add(new JsonObject().put("$toLong", "$$NOW"))));
                }
                JsonArray shares = f.getJsonArray("shares").copy();
                for (Object value : shares) {
                    JsonObject s = (JsonObject) value;
                    if (requester.equals(s.getString("recipientId"))) s.put("downloadCount", s.getInteger("downloadCount", 0) + 1).put("updatedAt", Instant.now().toString());
                }
                update.put("$set", new JsonObject().put("shares", shares));
            }
            return mongo.updateCollection(FILES, query, update).compose(result -> result.getDocModified() == 1 ? Future.succeededFuture() : consume(fileId, requester, attempt + 1));
        });
    }
    private Future<?> delete(RoutingContext ctx) {
        return owned(ctx, true).compose(f -> {
            if ("DELETED".equals(f.getString("status"))) { ctx.response().setStatusCode(204).end(); return Future.succeededFuture(); }
            return mongo.updateCollection(FILES, new JsonObject().put("_id", f.getString("_id")).put("ownerId", userId(ctx)).put("status", new JsonObject().put("$ne", "DELETED")),
                    new JsonObject().put("$set", new JsonObject().put("status", "DELETING")).put("$inc", new JsonObject().put("revision", 1)))
                    .recover(error -> Future.failedFuture(new ApiException(503, "DELETION_FAILED", "Access could not be revoked. Please retry deletion.")))
                    .compose(ignored -> cleanup(f).recover(error -> Future.failedFuture(new ApiException(503, "DELETION_PENDING",
                            "Access has been revoked. Storage cleanup is pending; retry deletion to finish."))))
                    .map(ignored -> { ctx.response().setStatusCode(204).end(); return null; });
        });
    }
    private Future<Void> cleanup(JsonObject f) {
        return worker(() -> { storage.delete(f.getString("storageObjectKey")); return null; })
                .compose(ignored -> mongo.updateCollection(FILES, new JsonObject().put("_id", f.getString("_id")).put("status", "DELETING"),
                        new JsonObject().put("$set", new JsonObject().put("status", "DELETED").put("shares", new JsonArray()).put("deletedAt", Instant.now().toString())))).mapEmpty();
    }
    private void retryDeletions() {
        // Incomplete uploads cannot grant access. Stale uploads are recovered after one hour.
        mongo.updateCollection(FILES, new JsonObject().put("status", "PENDING").put("uploadStartedAt", new JsonObject().put("$lt", System.currentTimeMillis() - 3_600_000)),
                new JsonObject().put("$set", new JsonObject().put("status", "DELETING")))
                .compose(ignored -> mongo.find(FILES, new JsonObject().put("status", "DELETING")))
                .onSuccess(files -> files.forEach(f -> cleanup(f).onFailure(ignored -> System.err.println("A file deletion remains pending; the next scheduled retry will try again."))))
                .onFailure(ignored -> System.err.println("Deletion recovery could not reach the database; it will retry."));
    }
}
