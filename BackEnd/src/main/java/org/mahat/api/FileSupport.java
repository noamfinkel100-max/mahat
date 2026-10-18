package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.RoutingContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.Instant;
import java.util.concurrent.Callable;

/** Shared MongoDB, authorization, and HTTP helpers for file endpoints. */
abstract class FileSupport {
    protected final Vertx vertx;
    protected final MongoClient mongo;
    protected final ObjectStorage storage;
    protected final Path uploads;
    protected final long maxUpload;

    FileSupport(Vertx vertx, MongoClient mongo, ObjectStorage storage, Path uploads, long maxUpload) {
        this.vertx = vertx;
        this.mongo = mongo;
        this.storage = storage;
        this.uploads = uploads;
        this.maxUpload = maxUpload;
    }

    protected void run(RoutingContext ctx, Callable<Future<?>> action) {
        try {
            action.call().onFailure(error -> fail(ctx, error));
        } catch (Throwable error) {
            fail(ctx, error);
        }
    }

    protected void fail(RoutingContext ctx, Throwable error) {
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

    protected void json(RoutingContext ctx, int status, Object body) {
        String encoded =
                body instanceof JsonObject
                        ? ((JsonObject) body).encode()
                        : ((JsonArray) body).encode();
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .end(encoded);
    }

    protected JsonObject body(RoutingContext ctx) {
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

    protected String text(JsonObject body, String field, int min, int max) {
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

    protected String userId(RoutingContext ctx) {
        return user(ctx).getString("_id");
    }

    protected JsonObject user(RoutingContext ctx) {
        return ctx.get("user");
    }

    protected JsonObject publicUser(JsonObject user) {
        return new JsonObject()
                .put("userId", user.getString("_id"))
                .put("name", user.getString("name"));
    }

    protected <T> Future<T> worker(Callable<T> action) {
        return vertx.executeBlocking(action, false);
    }

    protected Path privateTemp(String prefix) throws Exception {
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

    // File metadata and access checks

    protected Future<?> listFiles(RoutingContext ctx) {
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

        return mongo.find(Api.FILES, query)
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

    protected Future<JsonObject> file(String id) {
        return mongo.findOne(Api.FILES, new JsonObject().put("_id", id), null)
                .compose(
                        file ->
                                file == null
                                        ? Future.failedFuture(
                                                new ApiException(
                                                        404, "FILE_NOT_FOUND", "File not found."))
                                        : Future.succeededFuture(file));
    }

    protected Future<JsonObject> owned(RoutingContext ctx, boolean allowDeleted) {
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

    protected JsonObject publicFile(JsonObject f, JsonObject share) {
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

    protected JsonObject publicShare(JsonObject s) {
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

    protected JsonArray publicShares(JsonObject f) {
        JsonArray shares = new JsonArray();
        f.getJsonArray("shares", new JsonArray())
                .forEach(share -> shares.add(publicShare((JsonObject) share)));
        return shares;
    }

    protected JsonObject findShare(JsonObject file, String recipientId) {
        return findShare(file.getJsonArray("shares", new JsonArray()), recipientId);
    }

    protected JsonObject findShare(JsonArray shares, String recipientId) {
        for (Object item : shares) {
            JsonObject share = (JsonObject) item;
            if (recipientId.equals(share.getString("recipientId"))) {
                return share;
            }
        }
        return null;
    }

    protected JsonObject versionQuery(JsonObject file) {
        return new JsonObject()
                .put("_id", file.getString("_id"))
                .put("status", "ACTIVE")
                .put("revision", file.getLong("revision"));
    }
}
