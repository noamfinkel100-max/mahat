package org.mahat.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads credentials from one matching local MinIO/Silo container for local development only. */
final class LocalMinio {
    private static final String ENV_TEMPLATE =
            "{{range .Config.Env}}{{$key := index (split . \"=\") 0}}"
                    + "{{if or (eq $key \"MINIO_ROOT_USER\") (eq $key \"MINIO_ROOT_PASSWORD\") "
                    + "(eq $key \"MINIO_ACCESS_KEY\") (eq $key \"MINIO_SECRET_KEY\")}}"
                    + "{{println .}}{{end}}{{end}}";
    private static final Set<String> LOCAL_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> LOCAL_BINDINGS =
            Set.of("", "0.0.0.0", "127.0.0.1", "::", "::1");
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private LocalMinio() {}

    @FunctionalInterface
    interface Commands {
        String run(List<String> arguments) throws IOException;
    }

    static Map<String, String> discover(String endpoint) throws IOException {
        return discover(endpoint, LocalMinio::runDocker);
    }

    static Map<String, String> discover(String endpoint, Commands commands) throws IOException {
        URI uri = localEndpoint(endpoint);
        if (uri == null) {
            return Map.of();
        }

        String running;
        try {
            running = commands.run(List.of("docker", "ps", "--format", "{{.ID}} {{.Image}}"));
        } catch (IOException dockerUnavailable) {
            // Explicit credentials still work when Docker is not installed or running.
            return Map.of();
        }

        List<String> containers = matchingContainers(uri, running, commands);
        if (containers.size() != 1) {
            return Map.of();
        }
        return credentials(commands, containers.get(0));
    }

    private static URI localEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!Set.of("http", "https").contains(scheme)
                    || host == null
                    || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
    }

    private static List<String> matchingContainers(
            URI endpoint, String running, Commands commands) throws IOException {
        int port = endpoint.getPort();
        if (port == -1) {
            port = endpoint.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        }

        List<String> matches = new ArrayList<>();
        for (String line : running.lines().toList()) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length != 2 || !isContainerId(fields[0]) || !isMinioImage(fields[1])) {
                continue;
            }

            String mappings = commands.run(
                    List.of(
                            "docker",
                            "inspect",
                            "--format",
                            "{{json .NetworkSettings.Ports}}",
                            fields[0]));
            if (publishesLocalPort(mappings, port)) {
                matches.add(fields[0]);
            }
        }
        return matches;
    }

    private static boolean isContainerId(String value) {
        return value.matches("[a-fA-F0-9]{12,64}");
    }

    private static boolean isMinioImage(String imageName) {
        String image = imageName.toLowerCase(Locale.ROOT);
        String repository = image.substring(image.lastIndexOf('/') + 1).split("[:@]", 2)[0];
        // Silo is the MinIO-compatible server used by some existing local setups.
        return repository.equals("minio") || repository.equals("silo");
    }

    private static boolean publishesLocalPort(String json, int port) {
        try {
            JsonObject mappings = new JsonObject(json);
            for (String internalPort : mappings.fieldNames()) {
                if (!internalPort.endsWith("/tcp")
                        || !(mappings.getValue(internalPort) instanceof JsonArray bindings)) {
                    continue;
                }
                for (Object value : bindings) {
                    JsonObject binding = (JsonObject) value;
                    boolean correctPort = String.valueOf(port).equals(binding.getString("HostPort"));
                    boolean localOnly = LOCAL_BINDINGS.contains(binding.getString("HostIp", ""));
                    if (correctPort && localOnly) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException malformedDockerOutput) {
            return false;
        }
        return false;
    }

    private static Map<String, String> credentials(Commands commands, String containerId)
            throws IOException {
        String environment = commands.run(
                List.of("docker", "inspect", "--format", ENV_TEMPLATE, containerId));
        Map<String, String> values = parseEnvironment(environment);

        String username = values.getOrDefault("MINIO_ROOT_USER", values.get("MINIO_ACCESS_KEY"));
        String password =
                values.getOrDefault("MINIO_ROOT_PASSWORD", values.get("MINIO_SECRET_KEY"));
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Map.of();
        }
        return Map.of("S3_ACCESS_KEY", username, "S3_SECRET_KEY", password);
    }

    private static Map<String, String> parseEnvironment(String environment) {
        Map<String, String> values = new HashMap<>();
        for (String line : environment.lines().toList()) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return values;
    }

    private static String runDocker(List<String> arguments) throws IOException {
        Process process = new ProcessBuilder(arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        // Drain stdout while Docker runs or the OS pipe can fill and block the process.
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readOutput(process));
        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Local Docker inspection timed out.");
            }

            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > MAX_OUTPUT_BYTES) {
                throw new IOException("Local Docker inspection failed.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Local Docker inspection interrupted.", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new IOException("Local Docker inspection failed.", failed);
        } finally {
            process.destroyForcibly();
            output.cancel(true);
        }
    }

    private static byte[] readOutput(Process process) {
        try {
            return process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
        } catch (IOException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }
}
