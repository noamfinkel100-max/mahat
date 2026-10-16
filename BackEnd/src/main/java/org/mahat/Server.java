package org.mahat;

import com.google.gson.Gson;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.entities.user.MongoUser;
import org.mahat.handlers.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mahat.utils.Constants.*;

public class Server {
    private final Logger LOGGER = LogManager.getLogger(Server.class);
    private static Server server;
    private final Vertx vertx;
    private final Router router;
    private final HttpServer httpServer;
    private final Gson gson = new Gson();
    private final JsonObject config = new JsonObject();
    private final Map<String, MongoUser> users = new HashMap<>();

    public Server() {
        Server.server = this;
        vertx = Vertx.vertx();
        router = Router.router(vertx);
        httpServer = vertx.createHttpServer();

    }

    public void start(ConfigRetrieverOptions options) {
        ConfigRetriever.create(vertx, options)
                .getConfig()
                .compose(this::storeConfig)
                .compose(this::prepareRouter)
                .compose(this::deployVerticals)
                .compose(this::startHttpServer)
                .onSuccess(actualPort -> {
                    String message = "*** The Secure Drop server has started successfully ***";
                    String stars = "*".repeat(message.length());
                    LOGGER.info(stars);
                    LOGGER.info(message);
                    LOGGER.info(stars);
                })
                .onFailure(error -> {
                    LOGGER.error("Failed to start the service! The error: %s.".formatted(error.getMessage()));
                    System.exit(500);
                });
    }

    private Future<Void> storeConfig(JsonObject config) {
        LOGGER.info("Configuration retried!");
        this.config.mergeIn(config);
        StringBuilder configPrint = new StringBuilder();
        String[] params = new String[]{EXPOSED_PORT, MONGO_URL, MONGO_DB_NAME, S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY};
        for (String param : params) {
            if (config.containsKey(param)) {
                configPrint.append("***  %s - %s\n".formatted(param, config.getString(param)));
            }
        }

        LOGGER.info("The configuration is: \n%s".formatted(configPrint.toString()));
        return Future.succeededFuture();
    }

    private Future<Void> prepareRouter(Void ignore) {
        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("origin");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("accept");

        Set<HttpMethod> allowedMethods = new HashSet<>();
        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);
        allowedMethods.add(HttpMethod.DELETE);

        router.route().handler(CorsHandler.create()
                .allowedHeaders(allowedHeaders)
                .allowedMethods(allowedMethods));
        router.route().handler(BodyHandler.create().setUploadsDirectory("C:\\Secure Drop").setDeleteUploadedFilesOnEnd(true));

        return Future.succeededFuture();
    }

    private Future<String> deployVerticals(Void ignore) {
        Promise<String> deploymentPromise = Promise.promise();
        vertx.deployVerticle(UsersHandler.class.getName())
                .compose(var -> vertx.deployVerticle(DownloadHandler.class.getName()))
                .compose(var -> vertx.deployVerticle(UploadHandler.class.getName()))
                .compose(var -> vertx.deployVerticle(SharingHandler.class.getName()))
                .compose(var -> vertx.deployVerticle(AccessibleFilesHandler.class.getName()))
                .compose(var -> vertx.deployVerticle(DeleteFileHandler.class.getName()))
                .onSuccess(deploymentPromise::tryComplete)
                .onFailure(deploymentPromise::tryFail);

        return deploymentPromise.future();
    }

    private Future<Void> startHttpServer(String ignore) {
        Promise<Void> promise = Promise.promise();
        int port = config.getInteger(EXPOSED_PORT);
        httpServer.requestHandler(router).listen(port)
                .onSuccess(var -> promise.tryComplete())
                .onFailure(promise::tryFail);

        return promise.future();
    }

    public static Server getInstance() {
        return server;
    }

    public Vertx vertx() {
        return vertx;
    }

    public Router router() {
        return router;
    }

    public Gson gson() {
        return gson;
    }

    public JsonObject config() {
        return config;
    }

    public void userLogIn(MongoUser user) {
        users.put(user._id(), user);
    }

    public void userUpdate(MongoUser user) {
        users.replace(user._id(), user);
    }

    public Map<String, MongoUser> users() {
        return users;
    }
}
