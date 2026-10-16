package org.mahat.handlers;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.Server;
import org.mahat.enpoints.MongoEndpoint;
import org.mahat.enpoints.S3Endpoint;

public class DeleteFileHandler extends AbstractVerticle {
    private final Logger LOGGER = LogManager.getLogger(DeleteFileHandler.class);
    private final Server server = Server.getInstance();
    private final S3Endpoint s3Endpoint = new S3Endpoint();
    private final MongoEndpoint mongoEndpoint = new MongoEndpoint();

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = server.router();
        router.route(HttpMethod.POST, "/delete").handler(this::deleteFile);
        startPromise.complete();
    }

    private void deleteFile(RoutingContext context) {
        LOGGER.info("Received request to delete file! Starting to handle it.");
        JsonObject body = context.body().asJsonObject();
        String userId = body.getString("_id");
        String fileName = body.getString("fileName");
        if (!server.users().containsKey(userId) || !server.users().get(userId).myFiles().containsKey(fileName)) {
            LOGGER.warn("Unauthorized user %s tried to delete a file %s.".formatted(userId, fileName));
            context.response().setStatusCode(401).end();
            return;
        }

        s3Endpoint.delete(fileName)
                .onSuccess(success -> {
                    mongoEndpoint.removeFile(server.users().get(userId), fileName, true)
                            .onSuccess(updatedUser -> {
                                LOGGER.info("File %s deleted successfully!".formatted(fileName));
                                context.response().setStatusCode(200).end();
                                server.userUpdate(updatedUser);
                            })
                            .onFailure(error -> {
                                String message = "Failed to delete file! The error: %s.".formatted(error.getMessage());
                                LOGGER.error(message);
                                context.response().setStatusCode(500).end(message);
                            });
                    mongoEndpoint.containingFile(fileName)
                            .onSuccess(affectedUsers -> affectedUsers.forEach(user -> mongoEndpoint.removeFile(user, fileName, false)
                                    .onSuccess(server::userUpdate)
                                    .onFailure(error -> LOGGER.warn("File %s did not delete from user %s! The error: %s.".formatted(fileName, user.name(), error.getMessage())))))
                            .onFailure(error -> {
                                String message = "Failed to delete file! The error: %s.".formatted(error.getMessage());
                                LOGGER.error(message);
                                context.response().setStatusCode(500).end(message);
                            });
                })
                .onFailure(error -> {
                    String message = "Failed to delete file! The error: %s.".formatted(error.getMessage());
                    LOGGER.error(message);
                    context.response().setStatusCode(500).end(message);
                });
    }
}
