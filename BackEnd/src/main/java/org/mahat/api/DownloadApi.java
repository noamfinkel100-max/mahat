package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.RoutingContext;
import org.mahat.handlers.Encryptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Validates access, authenticates encrypted data, and sends authorized downloads. */
final class DownloadApi extends FileSupport {
    DownloadApi(Vertx vertx, MongoClient mongo, ObjectStorage storage, Path uploads, long maxUpload) {
        super(vertx, mongo, storage, uploads, maxUpload);
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

    Future<?> download(RoutingContext ctx) {
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
                            return mongo.updateCollection(Api.FILES, query, update)
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
}
