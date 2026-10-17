package org.mahat.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Read-only Docker discovery, used only by the explicitly local development launchers. */
final class LocalMinio {
    private static final String ENV_TEMPLATE = "{{range .Config.Env}}{{$key := index (split . \"=\") 0}}"
            + "{{if or (eq $key \"MINIO_ROOT_USER\") (eq $key \"MINIO_ROOT_PASSWORD\") "
            + "(eq $key \"MINIO_ACCESS_KEY\") (eq $key \"MINIO_SECRET_KEY\")}}{{println .}}{{end}}{{end}}";

    private LocalMinio() { }

    @FunctionalInterface
    interface Commands { String run(List<String> arguments) throws IOException; }

    static Map<String, String> discover(String endpoint) throws IOException {
        return discover(endpoint, LocalMinio::run);
    }

    static Map<String, String> discover(String endpoint, Commands commands) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); } catch (IllegalArgumentException error) { return Map.of(); }
        if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()))
                || !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(String.valueOf(uri.getHost()))
                || uri.getUserInfo() != null) return Map.of();
        int port = uri.getPort() == -1 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
        String containers;
        try { containers = commands.run(List.of("docker", "ps", "--format", "{{.ID}} {{.Image}}")); }
        catch (IOException error) { return Map.of(); } // Docker is optional when credentials are configured.
        List<String> matches = new ArrayList<>();
        for (String line : containers.lines().toList()) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length != 2 || !fields[0].matches("[a-fA-F0-9]{12,64}")) continue;
            String image = fields[1].toLowerCase(Locale.ROOT);
            // The Silo distribution used by existing securedrop-minio containers is compatible.
            if (!image.matches(".*(?:^|/)(?:minio|silo)(?::|@|$).*")) continue;
            String ports = commands.run(List.of("docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", fields[0]));
            if (publishedAt(ports, port)) matches.add(fields[0]);
        }
        if (matches.size() != 1) return Map.of(); // Refuse ambiguous containers rather than guessing.
        String settings = commands.run(List.of("docker", "inspect", "--format", ENV_TEMPLATE, matches.get(0)));
        Map<String, String> values = new java.util.HashMap<>();
        for (String line : settings.lines().toList()) {
            int equals = line.indexOf('=');
            if (equals > 0) values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        String user = values.getOrDefault("MINIO_ROOT_USER", values.get("MINIO_ACCESS_KEY"));
        String password = values.getOrDefault("MINIO_ROOT_PASSWORD", values.get("MINIO_SECRET_KEY"));
        if (user == null || user.isBlank() || password == null || password.isBlank()) return Map.of();
        return Map.of("S3_ACCESS_KEY", user, "S3_SECRET_KEY", password);
    }

    private static boolean publishedAt(String json, int port) {
        try {
            JsonObject mappings = new JsonObject(json);
            for (String internal : mappings.fieldNames()) {
                if (!internal.endsWith("/tcp") || !(mappings.getValue(internal) instanceof JsonArray bindings)) continue;
                for (Object value : bindings) {
                    JsonObject binding = (JsonObject) value;
                    if (String.valueOf(port).equals(binding.getString("HostPort"))
                            && Set.of("", "0.0.0.0", "127.0.0.1", "::", "::1").contains(binding.getString("HostIp", ""))) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static String run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Read concurrently so Docker cannot block on a full stdout pipe. Bound output and execution time.
        var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readNBytes(64 * 1024 + 1); }
            catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
        });
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Local Docker inspection timed out.");
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 64 * 1024) throw new IOException("Local Docker inspection failed.");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("Local Docker inspection interrupted.");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IOException("Local Docker inspection failed.");
        } finally {
            process.destroyForcibly(); output.cancel(true);
        }
    }
}
