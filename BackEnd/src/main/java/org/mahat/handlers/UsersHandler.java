package org.mahat.handlers;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mahat.Server;
import org.mahat.enpoints.MongoEndpoint;
import org.mahat.entities.user.MongoUser;
import org.mahat.entities.user.UserCredentials;

public class UsersHandler extends AbstractVerticle {
    private final Logger LOGGER = LogManager.getLogger(UsersHandler.class);
    private final Server server = Server.getInstance();
    private final MongoEndpoint mongoEndpoint = new MongoEndpoint();

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = server.router();
        router.route(HttpMethod.GET, "/login").handler(this::login);
        router.route(HttpMethod.POST, "/login").handler(this::login);
        router.route(HttpMethod.GET, "/all/users").handler(this::allUsers);
        router.route(HttpMethod.POST, "/sign/up").handler(this::signUp);
        startPromise.complete();
    }

    private void login(RoutingContext context) {
        LOGGER.info("Received request to login! starting to handle it");
        try {
            UserCredentials credentials;
            String body = context.body().asString();
            if (body != null && !body.isBlank()) {
                credentials = server.gson().fromJson(body, UserCredentials.class);
            } else {
                String name = context.request().getParam("name");
                String password = context.request().getParam("password");
                credentials = new UserCredentials(name, password);
            }

            if (credentials == null || credentials.name() == null || credentials.password() == null) {
                context.response().setStatusCode(400).end("Missing login credentials");
                return;
            }

            mongoEndpoint.login(credentials)
                    .onSuccess(mongoUserData -> {
                        if (mongoUserData == null) {
                            LOGGER.warn("Unrecognized user tried to login! %s.".formatted(credentials.name()));
                            context.response().setStatusCode(401).end();
                            return;
                        }
                        server.userLogIn(mongoUserData);
                        context.response()
                                .putHeader("Content-Type", "application/json")
                                .setStatusCode(200)
                                .end(userFilesResponse(mongoUserData));
                        LOGGER.info("User %s logged on successfully!".formatted(mongoUserData.name()));
                    })
                    .onFailure(error -> {
                        String message = "Failed to sign user in. The error: %s".formatted(error.getMessage());
                        LOGGER.error(message);
                        context.response().setStatusCode(500).end(message);
                    });

        } catch (Exception e) {
            String message = "Failed to sign user in. The error: %s".formatted(e.getMessage());
            LOGGER.error(message);
            context.response().setStatusCode(500).end(message);
        }
    }

    private void signUp(RoutingContext context) {
        LOGGER.info("Received request to sign up! starting to handle it");
        try {
            String body = context.body().asString();
            UserCredentials userSignUp = server.gson().fromJson(body, UserCredentials.class);
            if (userSignUp == null || userSignUp.name() == null || userSignUp.name().isBlank()
                    || userSignUp.password() == null || userSignUp.password().isBlank()) {
                context.response().setStatusCode(400).end("Name and password are required.");
                return;
            }

            mongoEndpoint.checkUserExists(userSignUp.name())
                    .onSuccess(exist -> {
                        if (exist) {
                            String message = "Received request to sign up user that already exist! The user: %s".formatted(userSignUp.name());
                            LOGGER.warn(message);
                            context.response().setStatusCode(406).end(message);
                            return;
                        }

                        mongoEndpoint.saveNewUser(MongoUser.fromCredentials(userSignUp))
                                .onSuccess(userId -> {
                                    MongoUser newUser = new MongoUser(
                                            userId,
                                            userSignUp.name(),
                                            userSignUp.password(),
                                            java.util.Map.of(),
                                            java.util.List.of()
                                    );
                                    server.userLogIn(newUser);
                                    String message = "User %s Added successfully!".formatted(userSignUp.name());
                                    LOGGER.info(message);
                                    context.response()
                                            .putHeader("Content-Type", "application/json")
                                            .setStatusCode(201)
                                            .end(userFilesResponse(newUser));
                                })
                                .onFailure(error -> {
                                    String message = "Failed to save new user %s. The error: %s.".formatted(userSignUp.name(), error.getMessage());
                                    LOGGER.error(message);
                                    context.response().setStatusCode(500).end(message);
                                });
                    })
                    .onFailure(error -> {
                        String message = "Failed to sign up user. The error: %s".formatted(error.getMessage());
                        LOGGER.error(message);
                        context.response().setStatusCode(500).end(message);
                    });

        } catch (Exception e) {
            String message = "Failed to sign up user. The error: %s".formatted(e.getMessage());
            LOGGER.error(message);
            context.response().setStatusCode(500).end(message);
        }
    }

    private void allUsers(RoutingContext context) {
        LOGGER.info("Received request to get all users! starting to handle it");
        mongoEndpoint.getAllUsers()
                .onSuccess(users -> {
                    context.response().setStatusCode(200).end(server.gson().toJson(users));
                    LOGGER.info("Users sent successfully!");
                })
                .onFailure(error -> {
                    String message = "Failed to get all users. The error: %s".formatted(error.getMessage());
                    LOGGER.error(message);
                    context.response().setStatusCode(500).end(message);
                });
    }

    private String userFilesResponse(MongoUser user) {
        return new JsonObject()
                .put("userId", user._id())
                .put("myFiles", user.myFiles())
                .put("sharedWithMe", user.sharedWithMe())
                .toString();
    }
}
