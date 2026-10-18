package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.RoutingContext;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Creates, updates, and revokes recipient grants. */
final class ShareApi extends FileSupport {
    ShareApi(Vertx vertx, MongoClient mongo, ObjectStorage storage, Path uploads, long maxUpload) {
        super(vertx, mongo, storage, uploads, maxUpload);
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

    Future<?> mutateShare(RoutingContext ctx, boolean existing, boolean revoke) {
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

        return mongo.findOne(Api.USERS, new JsonObject().put("_id", recipientId), null)
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
                                            Api.FILES,
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
}
