package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.IndexOptions;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.mahat.security.PasswordHasher;
import org.mahat.StartupFailure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Authenticated API. A file and its shares form one MongoDB atomic authorization boundary. */
public final class Api {
    static final String USERS = "users", FILES = "files", SESSIONS = "sessions";
    private final Vertx vertx;
    private final MongoClient mongo;
    private final SessionManager sessions;
    private final FileApi fileApi;
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
        fileApi = new FileApi(vertx, mongo, storage, uploads, maxUpload);
    }

    public Future<Void> initialize() {
        return prepareUploadDirectory()
                .compose(ignored -> initializeStorage())
                .compose(ignored -> initializeIndexes())
                .map(
                        ignored -> {
                            routes();
                            retryTimer =
                                    vertx.setPeriodic(60_000, timerId -> fileApi.retryDeletions());
                            fileApi.retryDeletions();
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
        fileApi.routes(router);
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


}