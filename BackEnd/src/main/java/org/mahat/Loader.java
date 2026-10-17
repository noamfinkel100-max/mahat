package org.mahat;

import io.vertx.core.json.JsonObject;
import org.mahat.config.Configuration;

/** All launch methods share the same local/configured startup behavior. */
public final class Loader {
    public static void main(String[] args) throws Exception {
        JsonObject config;
        try { config = Configuration.load(); }
        catch (IllegalArgumentException | java.io.IOException error) {
            System.err.println("Cyphernod configuration could not be loaded. " + error.getMessage());
            System.exit(1); return;
        }
        new Server().start(config).onSuccess(port -> System.out.println("Cyphernod API listening on port " + port))
                .onFailure(error -> { System.err.println("Backend startup failed: check service configuration and connectivity."); System.exit(1); });
    }
}
