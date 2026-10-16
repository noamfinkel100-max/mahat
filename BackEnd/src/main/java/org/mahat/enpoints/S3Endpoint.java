package org.mahat.enpoints;

import io.minio.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.Server;

import java.io.InputStream;

import static org.mahat.utils.Constants.*;

public class S3Endpoint {
    private final Logger LOGGER = LogManager.getLogger(S3Endpoint.class);
    private final Server server = Server.getInstance();
    private final MinioClient minioClient;
    private final String BUCKET = "secure-drop";

    public S3Endpoint() {
        minioClient = MinioClient.builder()
                .endpoint(server.config().getString(S3_ENDPOINT))
                .credentials(server.config().getString(S3_ACCESS_KEY), server.config().getString(S3_SECRET_KEY))
                .build();
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
                LOGGER.info("Bucket secure-drop created on the S3 server!");
            }
        } catch (Exception e) {
            LOGGER.info("Failed to initiate S3 bucket! Shutting down");
            System.exit(500);
        }
    }

    public Future<Void> upload(InputStream data, long fileSize, String fileName, String contentType) {
        Promise<Void> uploadPromise = Promise.promise();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(fileName)
                    .stream(data, fileSize, -1)
                    .contentType(contentType)
                    .build());

            uploadPromise.tryComplete();
        } catch (Exception e) {
            uploadPromise.fail(e.getMessage());
        }

        return uploadPromise.future();
    }

    public Future<InputStream> download(String fileName) {
        Promise<InputStream> downloadPromise = Promise.promise();

        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(fileName)
                    .build());

            downloadPromise.tryComplete(stream);
        } catch (Exception e) {
            downloadPromise.tryFail(e.getMessage());
        }

        return downloadPromise.future();
    }

    public Future<Void> delete(String fileName) {
        Promise<Void> deletePromise = Promise.promise();

        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(fileName)
                    .build());

            deletePromise.tryComplete();
        } catch (Exception e) {
            deletePromise.tryFail(e.getMessage());
        }

        return deletePromise.future();
    }
}
