package org.mahat;

import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;

/** Environment values override the optional legacy CONFIG_PATH file. Secrets are never logged. */
public final class Loader {
    public static void main(String[] args) throws Exception {
        JsonObject config = new JsonObject();
        String path = System.getenv("CONFIG_PATH");
        if (path != null && !path.isBlank()) config = new JsonObject(Files.readString(Path.of(path)));
        for (String name : new String[]{"PORT", "MONGO_URL", "MONGO_DB_NAME", "S3_ENDPOINT", "S3_ACCESS_KEY",
                "S3_SECRET_KEY", "S3_BUCKET", "SESSION_SECURE", "UPLOAD_DIR", "MAX_UPLOAD_BYTES", "ALLOWED_ORIGIN"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) config.put(name, value);
        }
        if (!config.containsKey("PORT") && config.containsKey("EXPOSED_PORT")) config.put("PORT", config.getValue("EXPOSED_PORT"));
        config.put("PORT", Integer.parseInt(String.valueOf(config.getValue("PORT", 12000))));
        new Server().start(config).onSuccess(port -> System.out.println("Cyphernod API listening on port " + port))
                .onFailure(error -> { System.err.println("Backend startup failed: check service configuration and connectivity."); System.exit(1); });
    }
}
