package org.mahat;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.mahat.api.Api;

/** Owns the Vert.x lifecycle; all routes live under the authenticated /api contract. */
public final class Server {
    private final Vertx vertx = Vertx.vertx();
    private HttpServer httpServer;
    private Api api;

    public Future<Integer> start(JsonObject configuration) {
        try { api = new Api(vertx, configuration); }
        catch (RuntimeException error) { return Future.failedFuture(StartupFailure.at(StartupFailure.Stage.CONFIGURATION, error)); }
        return api.initialize().compose(ignored -> {
            httpServer = vertx.createHttpServer();
            int port = configuration.getInteger("PORT", 12000);
            return httpServer.requestHandler(api.router()).listen(port)
                    .recover(error -> Future.failedFuture(StartupFailure.http(error, port)));
        }).map(server -> server.actualPort());
    }

    public void start(ConfigRetrieverOptions options) {
        ConfigRetriever.create(vertx, options).getConfig().compose(this::start)
                .onSuccess(port -> System.out.println("Cyphernod API listening on port " + port))
                .onFailure(error -> { System.err.println("Backend startup failed: " + StartupFailure.describe(error)); close(); });
    }

    public Future<Void> close() {
        if (api != null) api.close();
        return vertx.close();
    }
}
