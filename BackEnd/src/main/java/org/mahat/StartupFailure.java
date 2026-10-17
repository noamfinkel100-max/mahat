package org.mahat;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import io.minio.errors.ErrorResponseException;

import java.net.BindException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Safe startup diagnostics: never expose exception messages, URLs, passwords or response bodies. */
public final class StartupFailure extends RuntimeException {
    public enum Stage { UPLOAD_DIRECTORY, STORAGE, DATABASE, HTTP, CONFIGURATION }
    private final Stage stage;

    private StartupFailure(Stage stage, String message, Throwable cause) {
        super(message, cause); this.stage = stage;
    }

    public Stage stage() { return stage; }

    public static StartupFailure at(Stage stage, Throwable cause) {
        if (cause instanceof StartupFailure failure) return failure;
        String message = switch (stage) {
            case UPLOAD_DIRECTORY -> "Upload directory (UPLOAD_DIR) cannot be created or accessed. Choose a writable directory.";
            case STORAGE -> storageMessage(cause);
            case DATABASE -> databaseMessage(cause);
            case HTTP -> "HTTP server could not start.";
            case CONFIGURATION -> "Backend configuration is invalid. Check the configured field types and endpoints.";
        };
        return new StartupFailure(stage, message, cause);
    }

    public static StartupFailure http(Throwable cause, int port) {
        String message = find(cause, BindException.class) != null
                ? "HTTP port " + port + " is already in use. Another backend may already be running."
                : "HTTP server could not listen on port " + port + ".";
        return new StartupFailure(Stage.HTTP, message, cause);
    }

    public static String describe(Throwable cause) {
        return cause instanceof StartupFailure failure ? failure.getMessage()
                : "Unexpected startup failure (" + cause.getClass().getSimpleName() + ").";
    }

    private static String storageMessage(Throwable cause) {
        ErrorResponseException response = find(cause, ErrorResponseException.class);
        if (response != null) {
            String code = response.errorResponse() == null ? "" : response.errorResponse().code();
            if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code)
                    || "AccessDenied".equals(code) || "InvalidToken".equals(code)) {
                return "MinIO/Silo rejected the configured account or bucket permissions. "
                        + "Check S3_ACCESS_KEY, S3_SECRET_KEY and access to S3_BUCKET.";
            }
            return "MinIO/Silo bucket initialization failed. Check S3_BUCKET and its permissions.";
        }
        if (find(cause, SSLException.class) != null)
            return "MinIO/Silo TLS verification failed. Configure the trusted certificate for S3_ENDPOINT.";
        if (find(cause, UnknownHostException.class) != null)
            return "MinIO/Silo hostname could not be resolved. Check S3_ENDPOINT.";
        if (find(cause, ConnectException.class) != null || find(cause, java.io.InterruptedIOException.class) != null)
            return "Cannot connect to MinIO/Silo at S3_ENDPOINT. Use the published S3 API port, normally 9000.";
        return "MinIO/Silo initialization failed. Check S3_ENDPOINT uses the S3 API port, not the web console port.";
    }

    private static String databaseMessage(Throwable cause) {
        if (find(cause, MongoSecurityException.class) != null)
            return "MongoDB authentication failed. Check the account and authSource in MONGO_URL.";
        MongoCommandException command = find(cause, MongoCommandException.class);
        if (command != null) {
            if (command.getErrorCode() == 13 || command.getErrorCode() == 18)
                return "MongoDB denied database access. Check the account and database permissions in MONGO_URL.";
            return "MongoDB could not initialize database indexes (error code " + command.getErrorCode() + ").";
        }
        return "Cannot initialize MongoDB at MONGO_URL. Check its published port, authentication and connectivity.";
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (int depth = 0; error != null && depth < 20; depth++, error = error.getCause())
            if (type.isInstance(error)) return type.cast(error);
        return null;
    }
}
