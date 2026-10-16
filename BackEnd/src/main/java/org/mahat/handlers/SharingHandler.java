package org.mahat.handlers;

import com.google.gson.reflect.TypeToken;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.Server;
import org.mahat.enpoints.MongoEndpoint;
import org.mahat.entities.user.MongoUser;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class SharingHandler extends AbstractVerticle {
    private final Logger LOGGER = LogManager.getLogger(SharingHandler.class);
    private final Server server = Server.getInstance();
    private final MongoEndpoint mongoEndpoint = new MongoEndpoint();

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = server.router();
        router.route(HttpMethod.POST, "/share").handler(this::shareFile);
        startPromise.complete();
    }

    private void shareFile(RoutingContext context) {
        LOGGER.info("Received request to share file! Starting to handle it.");
        Type listType = new TypeToken<List<String>>(){}.getType();
        String fileName = context.body().asJsonObject().getString("fileName");
        List<String> updatedAllowedUsers = server.gson().fromJson(context.body().asJsonObject().getString("allowed"), listType);
        mongoEndpoint.containingFile(fileName)
                .onSuccess(allowedUsers ->  {
                    allowedUsers.forEach(mongoUser -> {
                        if (!updatedAllowedUsers.contains(mongoUser._id())) {
                            mongoEndpoint.removeFile(mongoUser, fileName, false);
                        }
                    });
                    List<Future<MongoUser>> compositeFuture = new ArrayList<>();
                    updatedAllowedUsers.forEach(id -> compositeFuture.add(mongoEndpoint.addFile(id, fileName, false).onSuccess(server::userUpdate)));
                    Future.all(compositeFuture)
                            .onSuccess(success -> {
                                LOGGER.info("File %s shared successfully!".formatted(fileName));
                                context.response().setStatusCode(200).end();
                            })
                            .onFailure(error -> {
                                String message = "Failed to share file! The error: %s.".formatted(error.getMessage());
                                LOGGER.error(message);
                                context.response().setStatusCode(500).end(message);
                            });
                })
                .onFailure(error -> {
                    String message = "Failed to share file! The error: %s.".formatted(error.getMessage());
                    LOGGER.error(message);
                    context.response().setStatusCode(500).end(message);
                });
    }
}
