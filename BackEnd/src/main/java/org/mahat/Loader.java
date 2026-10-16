package org.mahat;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.mahat.utils.Constants.CONFIG_PATH;

public class Loader {
    private static final Logger LOGGER = LogManager.getLogger(Loader.class);
    public static void main(String[] args) {
        LOGGER.info("****   Secure Drop started!   ****");
        if (!System.getenv().containsKey(CONFIG_PATH)) {
            LOGGER.error("No configuration file found! Shutting down.");
            System.exit(500);
        }
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setConfig(new JsonObject().put("path", System.getenv().get(CONFIG_PATH)));
        ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                .addStore(fileStore)
                .setScanPeriod(30000);
        new Server().start(options);
    }
}