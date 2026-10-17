package org.mahat.handlers;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.Server;
import org.mahat.enpoints.MongoEndpoint;
import org.mahat.enpoints.S3Endpoint;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

public class UploadHandler extends AbstractVerticle {
    private final Logger LOGGER = LogManager.getLogger(UploadHandler.class);
    private final Server server = Server.getInstance();
    private final S3Endpoint s3Endpoint = new S3Endpoint();
    private final MongoEndpoint mongoEndpoint = new MongoEndpoint();

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = server.router();
        router.route(HttpMethod.POST, "/upload").handler(this::uploadFile);
        startPromise.complete();
    }

    private void uploadFile(RoutingContext context) {
        LOGGER.info("Received request to upload file! Starting to handle it.");
        List<FileUpload> uploadedFiles = context.fileUploads();
        String userId = context.request().getFormAttribute("_id");
        String code = context.request().getFormAttribute("code");

        try (InputStream inputStream = new FileInputStream(uploadedFiles.get(0).uploadedFileName())) {
            InputStream encryptedData = Encryptor.encrypt(inputStream, code);
            String fileName = uploadedFiles.get(0).fileName();
            String contentType = uploadedFiles.get(0).contentType();
            long fileSize = uploadedFiles.get(0).size();
            s3Endpoint.upload(encryptedData, Math.addExact(fileSize, Encryptor.ENCRYPTION_OVERHEAD), fileName, contentType)
                    .compose(ignore -> mongoEndpoint.addFile(server.users().get(userId), uploadedFiles.get(0).fileName(), code, true))
                    .onSuccess(updatedUser -> {
                        LOGGER.info("File %s that was sent by %s was uploaded successfully!".formatted(uploadedFiles.get(0).fileName(), updatedUser.name()));
                        server.userUpdate(updatedUser);
                        JsonObject files = new JsonObject()
                                .put("myFiles", server.gson().toJson(updatedUser.myFiles()))
                                .put("sharedWithMe", server.gson().toJson(updatedUser.sharedWithMe()));
                        context.response().setStatusCode(200).end(files.toString());
                    })
                    .onFailure(error -> {
                        String message = "Failed to upload file to S3! The error: %s.".formatted(error.getMessage());
                        LOGGER.error(message);
                        context.response().setStatusCode(500).end(message);
                    });

        } catch (Exception e) {
            String message = "Failed to upload file to S3! The error: %s.".formatted(e.getMessage());
            LOGGER.error(message);
            context.response().setStatusCode(500).end(message);
        }
    }
}
