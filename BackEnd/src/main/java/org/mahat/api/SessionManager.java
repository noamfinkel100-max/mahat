package org.mahat.api;

import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.RoutingContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

final class SessionManager {
    private static final String COOKIE_NAME = "cyphernod_session";
    private static final long SESSION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoClient mongo;
    private final boolean secureCookie;

    SessionManager(MongoClient mongo, boolean secureCookie) {
        this.mongo = mongo;
        this.secureCookie = secureCookie;
    }

    Future<Void> create(RoutingContext ctx, JsonObject user) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        Cookie previous = ctx.getCookie(COOKIE_NAME);
        Future<?> removePrevious = previous == null
                ? Future.succeededFuture()
                : mongo.removeDocument(Api.SESSIONS, new JsonObject().put("_id", hash(previous.getValue())));

        JsonObject session = new JsonObject()
                .put("_id", hash(token))
                .put("userId", user.getString("_id"))
                .put("expiresAt", new JsonObject().put("$date",
                        Instant.ofEpochMilli(System.currentTimeMillis() + SESSION_MILLIS).toString()));

        return removePrevious.compose(ignored -> mongo.insert(Api.SESSIONS, session))
                .map(ignored -> {
                    ctx.addCookie(cookie(token, SESSION_MILLIS / 1000));
                    return null;
                });
    }

    Future<JsonObject> authenticate(RoutingContext ctx) {
        Cookie sessionCookie = ctx.getCookie(COOKIE_NAME);
        if (sessionCookie == null || sessionCookie.getValue().length() > 128) {
            return Future.failedFuture(unauthorized());
        }

        JsonObject query = new JsonObject()
                .put("_id", hash(sessionCookie.getValue()))
                .put("expiresAt", new JsonObject().put("$gt",
                        new JsonObject().put("$date", Instant.now().toString())));

        return mongo.findOne(Api.SESSIONS, query, null)
                .compose(session -> session == null
                        ? Future.failedFuture(unauthorized())
                        : mongo.findOne(Api.USERS, new JsonObject().put("_id", session.getString("userId")), null))
                .compose(user -> user == null
                        ? Future.failedFuture(unauthorized())
                        : Future.succeededFuture(user));
    }

    Future<Void> invalidate(RoutingContext ctx) {
        Cookie sessionCookie = ctx.getCookie(COOKIE_NAME);
        return mongo.removeDocument(Api.SESSIONS,
                        new JsonObject().put("_id", hash(sessionCookie.getValue())))
                .map(ignored -> {
                    ctx.addCookie(cookie("", 0));
                    return null;
                });
    }

    private Cookie cookie(String token, long maxAge) {
        return Cookie.cookie(COOKIE_NAME, token)
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(secureCookie)
                .setSameSite(CookieSameSite.STRICT)
                .setMaxAge(maxAge);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHENTICATED", "Sign in to continue.");
    }
}
