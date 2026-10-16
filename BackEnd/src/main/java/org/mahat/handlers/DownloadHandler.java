package org.mahat.handlers;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.Server;
import org.mahat.enpoints.S3Endpoint;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DownloadHandler extends AbstractVerticle {
    private final Logger LOGGER = LogManager.getLogger(DownloadHandler.class);
    private final Server server = Server.getInstance();
    private final S3Endpoint s3Endpoint = new S3Endpoint();

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = server.router();
        router.route(HttpMethod.POST, "/download").handler(this::downloadFile);
        startPromise.complete();
    }

    private void downloadFile(RoutingContext context) {
        LOGGER.info("Received request to download file! Staring to handle it.");
        String fileName = context.body().asJsonObject().getString("fileName");
        String code = context.body().asJsonObject().getString("code");
        s3Endpoint.download(fileName)
                .onSuccess(encryptedData -> {
                    InputStream decryptedData = Encryptor.decrypt(encryptedData, code);
                    try {
                        Path tempFilePath = Files.createTempFile("download-", "-" + fileName);
                        Files.copy(decryptedData, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
                        context.response()
                                .setStatusCode(200)
                                .putHeader("Content-Disposition", "attachment; filename=" + fileName)
                                .sendFile(tempFilePath.toString())
                                .onComplete(event -> {
                                    if (event.succeeded()) {
                                        LOGGER.info("File %s was sent to client successfully!".formatted(fileName));
                                    } else {
                                        LOGGER.error("Failed to send file %s to client! The error: %s.".formatted(fileName, event.cause().getMessage()));
                                    }

                                    try {
                                        Files.delete(tempFilePath);
                                    } catch (Exception e) {
                                        LOGGER.warn("Failed to delete temp file %s! The error: %s.".formatted(fileName, e.getMessage()));
                                    }
                                });
                    } catch (Exception e) {
                        String message = "Failed to download file from S3! The error: %s.".formatted(e.getMessage());
                        LOGGER.error(message);
                        context.response().setStatusCode(500).end(message);
                    }
                })
                .onFailure(error -> {
                    String message = "Failed to download file from S3! The error: %s.".formatted(error.getMessage());
                    LOGGER.error(message);
                    context.response().setStatusCode(500).end(message);
                });
    }
}
