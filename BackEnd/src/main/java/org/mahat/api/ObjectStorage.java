package org.mahat.api;

import io.minio.*;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Blocking S3 operations: callers must execute on a Vert.x worker. */
public class ObjectStorage {
    private final MinioClient client;
    private final String bucket;
    public ObjectStorage(JsonObject config) {
        client = MinioClient.builder().endpoint(config.getString("S3_ENDPOINT", "http://localhost:9000"))
                .credentials(required(config, "S3_ACCESS_KEY"), required(config, "S3_SECRET_KEY")).build();
        bucket = config.getString("S3_BUCKET", "cyphernod");
    }
    private static String required(JsonObject config, String name) {
        String value = config.getString(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    public void initialize() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try { client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
            catch (Exception error) { if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) throw error; }
        }
    }
    public void upload(Path path, String key) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, Files.size(path), -1).contentType("application/octet-stream").build());
        }
    }
    public InputStream download(String key) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }
    public void delete(String key) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
