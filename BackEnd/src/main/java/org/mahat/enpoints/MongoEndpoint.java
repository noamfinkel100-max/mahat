package org.mahat.enpoints;

import com.google.gson.JsonParseException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.MongoClient;
import org.mahat.Server;
import org.mahat.entities.user.MongoUser;
import org.mahat.entities.user.UserCredentials;

import java.util.ArrayList;
import java.util.List;

import static org.mahat.utils.Constants.*;

public class MongoEndpoint {
    private final Server server = Server.getInstance();
    private final MongoClient client;
    private final String USERS_COLLECTION = "users";

    public MongoEndpoint() {
        client = MongoClient.create(server.vertx(), new JsonObject()
                .put(URL, server.config().getString(MONGO_URL))
                .put(DB_NAME, server.config().getString(MONGO_DB_NAME)));
    }

    public Future<List<JsonObject>> getAllUsers() {
        Promise<List<JsonObject>> findPromise = Promise.promise();

        JsonObject fields = new JsonObject()
                .put("_id", 1)
                .put("name", 1);

        client.findWithOptions(USERS_COLLECTION, new JsonObject(), new FindOptions().setFields(fields))
                .onSuccess(findPromise::tryComplete)
                .onFailure(findPromise::tryFail);

        return findPromise.future();
    }

    public Future<String> saveNewUser(JsonObject mongoUser) {
        Promise<String> savePromise = Promise.promise();

        client.save(USERS_COLLECTION, mongoUser)
                .onSuccess(savePromise::tryComplete)
                .onFailure(savePromise::tryFail);

        return savePromise.future();
    }

    public Future<Boolean> checkUserExists(String userName) {
        Promise<Boolean> findPromise = Promise.promise();

        JsonObject query = new JsonObject()
                .put("name", userName);
        client.find(USERS_COLLECTION, query)
                .onSuccess(res -> {
                    if (!res.isEmpty()) {
                        findPromise.tryComplete(true);
                    } else {
                        findPromise.tryComplete(false);
                    }
                })
                .onFailure(findPromise::tryFail);

        return findPromise.future();
    }

    public Future<MongoUser> login(UserCredentials user) {
        Promise<MongoUser> findPromise = Promise.promise();
        try {
            JsonObject query = new JsonObject(server.gson().toJson(user));
            client.find(USERS_COLLECTION, query)
                    .onSuccess(res -> {
                        if (!res.isEmpty()) {
                            findPromise.tryComplete(server.gson().fromJson(res.get(0).toString(), MongoUser.class));
                        } else {
                            findPromise.tryComplete(null);
                        }
                    })
                    .onFailure(findPromise::tryFail);
        } catch (JsonParseException e) {
            findPromise.tryFail("Failed to parse user data!");
        }

        return findPromise.future();
    }

    public Future<MongoUser> addFile(MongoUser user, String fileName, String code, boolean myFile) {
        Promise<MongoUser> addPromise = Promise.promise();
        MongoUser updatedUser = myFile ? MongoUser.addMyFile(user, fileName, code) : MongoUser.addSharedFile(user, fileName);
        client.save(USERS_COLLECTION, new JsonObject(server.gson().toJson(updatedUser)))
                .onSuccess(success -> addPromise.tryComplete(updatedUser))
                .onFailure(addPromise::tryFail);

        return addPromise.future();
    }

    public Future<MongoUser> addFile(String id, String fileName, boolean myFile) {
        Promise<MongoUser> addPromise = Promise.promise();

        client.find(USERS_COLLECTION, new JsonObject().put("_id", id))
                .onSuccess(users -> {
                    MongoUser mongoUser = server.gson().fromJson(users.get(0).toString(), MongoUser.class);
                    addFile(mongoUser, fileName, null, myFile)
                            .onSuccess(addPromise::tryComplete)
                            .onFailure(addPromise::tryFail);
                })
                .onFailure(addPromise::tryFail);

        return addPromise.future();
    }

    public Future<MongoUser> removeFile(MongoUser user, String fileName, boolean myFile) {
        Promise<MongoUser> removePromise = Promise.promise();
        MongoUser updatedUser = myFile ? MongoUser.removeMyFile(user, fileName) : MongoUser.removeSharedFile(user, fileName);
        client.save(USERS_COLLECTION, new JsonObject(server.gson().toJson(updatedUser)))
                .onSuccess(success -> removePromise.tryComplete(updatedUser))
                .onFailure(removePromise::tryFail);

        return removePromise.future();
    }

    public Future<List<MongoUser>> containingFile(String fileName) {
        Promise<List<MongoUser>> usersPromise = Promise.promise();
        client.find(USERS_COLLECTION, new JsonObject().put("sharedWithMe", fileName))
                .onSuccess(users -> {
                    List<MongoUser> foundUsers = new ArrayList<>();
                    users.forEach(user -> foundUsers.add(server.gson().fromJson(user.toString(), MongoUser.class)));
                    usersPromise.complete(foundUsers);
                })
                .onFailure(usersPromise::tryFail);

        return usersPromise.future();
    }

}
