package org.mahat.config;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Startup configuration shared by Maven, VS Code and packaged applications. */
public final class Configuration {
    private static final Set<String> KEYS =
            Set.of(
                    "PORT",
                    "EXPOSED_PORT",
                    "MONGO_URL",
                    "MONGO_DB_NAME",
                    "S3_ENDPOINT",
                    "S3_ACCESS_KEY",
                    "S3_SECRET_KEY",
                    "S3_BUCKET",
                    "SESSION_SECURE",
                    "UPLOAD_DIR",
                    "MAX_UPLOAD_BYTES",
                    "ALLOWED_ORIGIN",
                    "CYPHERNOD_PROFILE");

    private static final String LOCAL_PROFILE = "local";
    private static final String PRODUCTION_PROFILE = "production";

    private Configuration() {}

    public static JsonObject load() throws IOException {
        return load(
                Path.of("").toAbsolutePath(),
                System.getenv(),
                System.getProperty("cyphernod.profile", PRODUCTION_PROFILE),
                LocalMinio::discover);
    }

    @FunctionalInterface
    interface CredentialsLookup {
        Map<String, String> find(String endpoint) throws IOException;
    }

    static JsonObject load(
            Path directory,
            Map<String, String> environment,
            String defaultProfile,
            CredentialsLookup credentials)
            throws IOException {
        Map<String, String> dotenv = readDotenvValues(directory);
        JsonObject config = readDotenvDefaults(dotenv);
        applyJsonConfig(config, directory, environment, dotenv);
        applyEnvironment(config, environment);

        String profile = config.getString("CYPHERNOD_PROFILE", defaultProfile);
        validateProfile(profile);
        if (LOCAL_PROFILE.equals(profile)) {
            configureLocalProfile(config, credentials);
        }

        requireStorageCredentials(config);
        configurePort(config);
        return config;
    }

    private static JsonObject readDotenvDefaults(Map<String, String> values) {
        JsonObject config = new JsonObject();
        for (String key : KEYS) {
            if (values.containsKey(key)) {
                config.put(key, values.get(key));
            }
        }
        return config;
    }

    private static Map<String, String> readDotenvValues(Path directory) throws IOException {
        Path path = directory.resolve(".env");
        if (!Files.isRegularFile(path)
                && directory.getFileName() != null
                && directory.getFileName().toString().equalsIgnoreCase("BackEnd")) {
            path = directory.getParent().resolve(".env");
        }
        return Files.isRegularFile(path) ? readDotenv(path) : new LinkedHashMap<>();
    }

    private static void applyJsonConfig(
            JsonObject config,
            Path directory,
            Map<String, String> environment,
            Map<String, String> dotenv) throws IOException {
        // An explicitly chosen legacy JSON file overrides implicit .env defaults.
        String configPath = environment.getOrDefault("CONFIG_PATH", dotenv.get("CONFIG_PATH"));
        if (configPath == null || configPath.isBlank()) {
            return;
        }

        Path path = Path.of(configPath);
        if (!path.isAbsolute()) {
            path = directory.resolve(path);
        }
        try {
            config.mergeIn(new JsonObject(Files.readString(path)));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("CONFIG_PATH must point to a valid JSON object.");
        }
    }

    private static void applyEnvironment(
            JsonObject config, Map<String, String> environment) {
        for (String key : KEYS) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) {
                config.put(key, value);
            }
        }
    }

    private static void validateProfile(String profile) {
        if (!LOCAL_PROFILE.equals(profile) && !PRODUCTION_PROFILE.equals(profile)) {
            throw new IllegalArgumentException(
                    "CYPHERNOD_PROFILE must be local or production.");
        }
    }

    private static void configureLocalProfile(
            JsonObject config, CredentialsLookup credentials) throws IOException {
        if (!config.containsKey("SESSION_SECURE")) {
            config.put("SESSION_SECURE", false);
        }

        // Docker Desktop can expose these ports only on IPv4 while localhost resolves to ::1.
        if (!config.containsKey("MONGO_URL")) {
            config.put("MONGO_URL", "mongodb://127.0.0.1:27017");
        }
        if (!config.containsKey("S3_ENDPOINT")) {
            config.put("S3_ENDPOINT", "http://127.0.0.1:9000");
        }

        config.put("MONGO_URL", localIpv4(config.getString("MONGO_URL")));
        config.put("S3_ENDPOINT", localIpv4(config.getString("S3_ENDPOINT")));
        loadLocalMinioCredentials(config, credentials);
    }

    private static void loadLocalMinioCredentials(
            JsonObject config, CredentialsLookup credentials) throws IOException {
        if (!missing(config, "S3_ACCESS_KEY") && !missing(config, "S3_SECRET_KEY")) {
            return;
        }

        Map<String, String> found = credentials.find(config.getString("S3_ENDPOINT"));
        boolean compatible =
                Set.of("S3_ACCESS_KEY", "S3_SECRET_KEY").stream()
                        .allMatch(
                                key ->
                                        missing(config, key)
                                                || config.getString(key).equals(found.get(key)));
        if (compatible) {
            found.forEach(
                    (key, value) -> {
                        if (missing(config, key)) {
                            config.put(key, value);
                        }
                    });
        }
    }

    private static void requireStorageCredentials(JsonObject config) {
        if (missing(config, "S3_ACCESS_KEY") || missing(config, "S3_SECRET_KEY")) {
            throw new IllegalArgumentException(
                    "MinIO credentials are unavailable. Local runs read .env and the running "
                            + "MinIO container on the configured loopback port. Other deployments "
                            + "require S3_ACCESS_KEY and S3_SECRET_KEY.");
        }
    }

    private static void configurePort(JsonObject config) {
        Object port = config.getValue("PORT", config.getValue("EXPOSED_PORT", 12000));
        try {
            int number = Integer.parseInt(String.valueOf(port));
            if (number < 1 || number > 65535) {
                throw new NumberFormatException();
            }
            config.put("PORT", number);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("PORT must be a number between 1 and 65535.");
        }
    }

    private static boolean missing(JsonObject config, String name) {
        Object value = config.getValue(name);
        return !(value instanceof String text) || text.isBlank();
    }

    private static boolean isDefaultLocalBucketCredentials(
            String accessKey, String secretKey) {
        return "cyphernod-dev".equals(accessKey)
                && "cyphernod-local-only-password".equals(secretKey);
    }

    private static String localIpv4(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"localhost".equalsIgnoreCase(uri.getHost()) || !usesPlainLocalProtocol(uri)) {
                return endpoint;
            }

            // Replace only the host in the original text; never decode or re-encode credentials/query values.
            int hostStart = endpoint.indexOf("://") + 3;
            String authority = uri.getRawAuthority();
            hostStart += authority.lastIndexOf('@') + 1;
            return endpoint.substring(0, hostStart)
                    + "127.0.0.1"
                    + endpoint.substring(hostStart + "localhost".length());
        } catch (IllegalArgumentException error) {
            return endpoint;
        }
    }

    private static boolean usesPlainLocalProtocol(URI uri) {
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (!"mongodb".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }

        String query = uri.getRawQuery();
        return query == null
                || !query.toLowerCase(java.util.Locale.ROOT).matches(".*(?:tls|ssl).*");
    }

    static Map<String, String> readDotenv(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            parseDotenvLine(line, lineNumber, result);
        }
        return result;
    }

    private static void parseDotenvLine(
            String line, int lineNumber, Map<String, String> result) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#")) {
            return;
        }
        if (text.startsWith("export ")) {
            text = text.substring(7).stripLeading();
        }

        int equals = text.indexOf('=');
        if (equals < 1 || !isValidName(text.substring(0, equals).trim())) {
            throw invalidLine(lineNumber);
        }

        String key = text.substring(0, equals).trim();
        String value = parseDotenvValue(text.substring(equals + 1).stripLeading(), lineNumber);
        result.put(key, value);
    }

    private static boolean isValidName(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static String parseDotenvValue(String value, int lineNumber) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return parseQuotedValue(value, lineNumber);
        }
        return stripInlineComment(value).stripTrailing();
    }

    private static String parseQuotedValue(String value, int lineNumber) {
        char quote = value.charAt(0);
        int end = value.indexOf(quote, 1);
        if (end < 0) {
            throw invalidLine(lineNumber);
        }

        String trailing = value.substring(end + 1).trim();
        if (!trailing.isEmpty() && !trailing.startsWith("#")) {
            throw invalidLine(lineNumber);
        }
        // Values are literal: no shell execution, variable interpolation or backslash rewriting.
        return value.substring(1, end);
    }

    private static String stripInlineComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == '#' && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static IllegalArgumentException invalidLine(int line) {
        // Do not include the line itself: it could contain a credential.
        return new IllegalArgumentException(
                "Invalid .env syntax at line " + line + ". Use NAME=value or a quoted value.");
    }
}
