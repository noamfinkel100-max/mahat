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
import org.mahat.entities.user.MongoUser;

public class AccessibleFilesHandler extends AbstractVerticle {
    private final Logger LOGGER = LogManager.getLogger(AccessibleFilesHandler.class);
    private final Server server = Server.getInstance();

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = server.router();
        router.route(HttpMethod.GET, "/accessible").handler(this::accessibleFiles);
        startPromise.complete();
    }

    private void accessibleFiles(RoutingContext context) {
        LOGGER.debug("Received request to get all accessible files for a user! Starting to handle it.");
        String userId = context.body().asJsonObject().getString("_id");
        if (server.users().containsKey(userId)) {
            MongoUser user = server.users().get(userId);
            JsonObject files = new JsonObject()
                    .put("myFiles", server.gson().toJson(user.myFiles()))
                    .put("sharedWithMe", server.gson().toJson(user.sharedWithMe()));
            context.response().setStatusCode(200).end(files.toString());
        } else {
            context.response().setStatusCode(401).end("User not recognized");
        }

    }
}
