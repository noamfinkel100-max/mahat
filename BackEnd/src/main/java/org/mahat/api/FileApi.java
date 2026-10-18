package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.mahat.handlers.Encryptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Registers file routes and handles uploads and deletion cleanup. */
final class FileApi extends FileSupport {
    private final ShareApi shares;
    private final DownloadApi downloads;

    FileApi(Vertx vertx, MongoClient mongo, ObjectStorage storage, Path uploads, long maxUpload) {
        super(vertx, mongo, storage, uploads, maxUpload);
        shares = new ShareApi(vertx, mongo, storage, uploads, maxUpload);
        downloads = new DownloadApi(vertx, mongo, storage, uploads, maxUpload);
    }

    void routes(Router router) {
        BodyHandler jsonBody = BodyHandler.create().setBodyLimit(16 * 1024).setHandleFileUploads(false);

        router.get("/api/users")
                .handler(
                        ctx ->
                                run(
                                        ctx,
                                        () ->
                                                mongo.find(Api.USERS, new JsonObject())
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
                .handler(ctx -> run(ctx, () -> shares.mutateShare(ctx, false, false)));
        router.put("/api/files/:fileId/shares/:recipientId")
                .handler(jsonBody)
                .handler(ctx -> run(ctx, () -> shares.mutateShare(ctx, true, false)));
        router.delete("/api/files/:fileId/shares/:recipientId")
                .handler(ctx -> run(ctx, () -> shares.mutateShare(ctx, true, true)));
        router.post("/api/files/:fileId/download")
                .handler(jsonBody)
                .handler(ctx -> run(ctx, () -> downloads.download(ctx)));
        router.delete("/api/files/:fileId").handler(ctx -> run(ctx, () -> delete(ctx)));
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
        return mongo.insert(Api.FILES, file)
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
                        Api.FILES,
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
                        Api.FILES,
                        new JsonObject().put("_id", file.getString("_id")),
                        new JsonObject().put("$set", new JsonObject().put("status", "DELETING")))
                .compose(ignored -> cleanup(file))
                .recover(ignored -> Future.succeededFuture());
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
                        Api.FILES,
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
                                        Api.FILES,
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

    void retryDeletions() {
        // Abandoned uploads are private; after an hour they can be cleaned like deletions.
        long staleUploadCutoff = System.currentTimeMillis() - 3_600_000;
        mongo.updateCollection(
                        Api.FILES,
                        new JsonObject()
                                .put("status", "PENDING")
                                .put(
                                        "uploadStartedAt",
                                        new JsonObject().put("$lt", staleUploadCutoff)),
                        new JsonObject()
                                .put("$set", new JsonObject().put("status", "DELETING")))
                .compose(ignored -> mongo.find(Api.FILES, new JsonObject().put("status", "DELETING")))
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
