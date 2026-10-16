package org.mahat.entities.user;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record MongoUser(String _id, String name, String password, Map<String, String> myFiles,
                        List<String> sharedWithMe) {
    public static JsonObject fromCredentials(UserCredentials credentials) {
        return new JsonObject()
                .put("name", credentials.name())
                .put("password", credentials.password())
                .put("myFiles", new HashMap<>())
                .put("sharedWithMe", new ArrayList<>());
    }

    public static MongoUser addMyFile(MongoUser origin, String fileName, String code) {
        Map<String, String> myNewFiles = new HashMap<>(origin.myFiles());
        myNewFiles.put(fileName, code);
        return new MongoUser(origin._id(), origin.name(), origin.password(), myNewFiles, origin.sharedWithMe());
    }

    public static MongoUser addSharedFile(MongoUser origin, String details) {
        List<String> newSharedWithMe = new ArrayList<>(origin.sharedWithMe());
        newSharedWithMe.add(details);
        return new MongoUser(origin._id(), origin.name(), origin.password(), origin.myFiles(), newSharedWithMe);
    }

    public static MongoUser removeMyFile(MongoUser origin, String fileName) {
        Map<String, String> myNewFiles = new HashMap<>(origin.myFiles());
        myNewFiles.remove(fileName);
        return new MongoUser(origin._id(), origin.name(), origin.password(), myNewFiles, origin.sharedWithMe());
    }

    public static MongoUser removeSharedFile(MongoUser origin, String details) {
        List<String> newSharedWithMe = new ArrayList<>(origin.sharedWithMe());
        newSharedWithMe.remove(details);
        return new MongoUser(origin._id(), origin.name(), origin.password(), origin.myFiles(), newSharedWithMe);
    }
}
