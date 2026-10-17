package org.mahat.config;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Startup configuration shared by Maven, VS Code and packaged applications. */
public final class Configuration {
    private static final Set<String> KEYS = Set.of("PORT", "EXPOSED_PORT", "MONGO_URL", "MONGO_DB_NAME",
            "S3_ENDPOINT", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_BUCKET", "SESSION_SECURE", "UPLOAD_DIR",
            "MAX_UPLOAD_BYTES", "ALLOWED_ORIGIN", "CYPHERNOD_PROFILE");

    private Configuration() { }

    public static JsonObject load() throws IOException {
        return load(Path.of("").toAbsolutePath(), System.getenv(),
                System.getProperty("cyphernod.profile", "production"), LocalMinio::discover);
    }

    @FunctionalInterface
    interface CredentialsLookup {
        Map<String, String> find(String endpoint) throws IOException;
    }

    static JsonObject load(Path directory, Map<String, String> environment, String defaultProfile,
                           CredentialsLookup credentials) throws IOException {
        // Read the closest .env only, so another checkout's settings are never picked up.
        Path dotenv = directory.resolve(".env");
        if (!Files.isRegularFile(dotenv) && directory.getFileName() != null
                && directory.getFileName().toString().equalsIgnoreCase("BackEnd")) {
            dotenv = directory.getParent().resolve(".env");
        }
        Map<String, String> values = Files.isRegularFile(dotenv) ? readDotenv(dotenv) : new LinkedHashMap<>();
        JsonObject config = new JsonObject();
        for (String key : KEYS) if (values.containsKey(key)) config.put(key, values.get(key));

        // An explicitly chosen legacy JSON file overrides implicit .env defaults.
        String configPath = environment.getOrDefault("CONFIG_PATH", values.get("CONFIG_PATH"));
        if (configPath != null && !configPath.isBlank()) {
            Path path = Path.of(configPath);
            if (!path.isAbsolute()) path = directory.resolve(path);
            try { config.mergeIn(new JsonObject(Files.readString(path))); }
            catch (RuntimeException error) {
                throw new IllegalArgumentException("CONFIG_PATH must point to a valid JSON object.");
            }
        }
        for (String key : KEYS) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) config.put(key, value);
        }

        String profile = config.getString("CYPHERNOD_PROFILE", defaultProfile);
        if (!profile.equals("local") && !profile.equals("production"))
            throw new IllegalArgumentException("CYPHERNOD_PROFILE must be local or production.");
        if (profile.equals("local")) {
            if (!config.containsKey("SESSION_SECURE")) config.put("SESSION_SECURE", false);
            // Docker Desktop can expose these ports only on IPv4 while localhost resolves to ::1.
            if (!config.containsKey("MONGO_URL")) config.put("MONGO_URL", "mongodb://127.0.0.1:27017");
            if (!config.containsKey("S3_ENDPOINT")) config.put("S3_ENDPOINT", "http://127.0.0.1:9000");
            config.put("MONGO_URL", localIpv4(config.getString("MONGO_URL")));
            config.put("S3_ENDPOINT", localIpv4(config.getString("S3_ENDPOINT")));
            String endpoint = config.getString("S3_ENDPOINT");
            if (missing(config, "S3_ACCESS_KEY") || missing(config, "S3_SECRET_KEY")) {
                Map<String, String> found = credentials.find(endpoint);
                // Never combine explicitly configured credentials with a different Docker account.
                boolean compatible = Set.of("S3_ACCESS_KEY", "S3_SECRET_KEY").stream()
                        .allMatch(key -> missing(config, key) || config.getString(key).equals(found.get(key)));
                if (compatible) found.forEach((key, value) -> {
                    if (missing(config, key)) config.put(key, value);
                });
            }
        }
        if (missing(config, "S3_ACCESS_KEY") || missing(config, "S3_SECRET_KEY")) {
            throw new IllegalArgumentException("MinIO credentials are unavailable. Local runs read .env and the running "
                    + "MinIO container on the configured loopback port. Other deployments require S3_ACCESS_KEY and S3_SECRET_KEY.");
        }
        Object port = config.getValue("PORT", config.getValue("EXPOSED_PORT", 12000));
        try {
            int number = Integer.parseInt(String.valueOf(port));
            if (number < 1 || number > 65535) throw new NumberFormatException();
            config.put("PORT", number);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("PORT must be a number between 1 and 65535.");
        }
        return config;
    }

    private static boolean missing(JsonObject config, String name) {
        Object value = config.getValue(name);
        return !(value instanceof String text) || text.isBlank();
    }

    private static String localIpv4(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"localhost".equalsIgnoreCase(uri.getHost())) return endpoint;
            // Preserve TLS hostname verification, explicit IPv6, remote hosts and SRV discovery.
            boolean plainHttp = "http".equalsIgnoreCase(uri.getScheme());
            boolean plainMongo = "mongodb".equalsIgnoreCase(uri.getScheme())
                    && (uri.getRawQuery() == null || !uri.getRawQuery().toLowerCase(java.util.Locale.ROOT).matches(".*(?:tls|ssl).*"));
            if (!plainHttp && !plainMongo) return endpoint;
            // Replace only the host in the original text; never decode or re-encode credentials/query values.
            int start = endpoint.indexOf("://") + 3;
            String authority = uri.getRawAuthority();
            start += authority.lastIndexOf('@') + 1;
            return endpoint.substring(0, start) + "127.0.0.1" + endpoint.substring(start + "localhost".length());
        } catch (IllegalArgumentException error) { return endpoint; }
    }

    static Map<String, String> readDotenv(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (text.startsWith("export ")) text = text.substring(7).stripLeading();
            int equals = text.indexOf('=');
            if (equals < 1 || !text.substring(0, equals).trim().matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw invalidLine(lineNumber);
            String key = text.substring(0, equals).trim();
            String value = text.substring(equals + 1).stripLeading();
            if (value.startsWith("\"") || value.startsWith("'")) {
                char quote = value.charAt(0);
                int end = value.indexOf(quote, 1);
                if (end < 0) throw invalidLine(lineNumber);
                String trailing = value.substring(end + 1).trim();
                if (!trailing.isEmpty() && !trailing.startsWith("#")) throw invalidLine(lineNumber);
                value = value.substring(1, end);
            } else {
                for (int i = 1; i < value.length(); i++) {
                    if (value.charAt(i) == '#' && Character.isWhitespace(value.charAt(i - 1))) {
                        value = value.substring(0, i); break;
                    }
                }
                value = value.stripTrailing();
            }
            // Values are literal: no shell execution, variable interpolation or backslash rewriting.
            result.put(key, value);
        }
        return result;
    }

    private static IllegalArgumentException invalidLine(int line) {
        // Do not include the line itself: it could contain a credential.
        return new IllegalArgumentException("Invalid .env syntax at line " + line + ". Use NAME=value or a quoted value.");
    }
}
