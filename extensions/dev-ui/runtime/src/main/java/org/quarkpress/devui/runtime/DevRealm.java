package org.quarkpress.devui.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The users of the Keycloak realm that Dev Services imports in dev mode, read from its JSON export: their ids (the
 * subjects of their tokens), user names and realm roles.
 */
public final class DevRealm {

    private static final Logger LOG = Logger.getLogger(DevRealm.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DevRealm() {}

    /** A user of the realm; {@code realmRoles} in the export's order, empty when it lists none. */
    public record RealmUser(String id, String username, List<String> realmRoles) {

        public RealmUser {
            realmRoles = List.copyOf(realmRoles);
        }
    }

    /**
     * The users of the realm export {@code in} (its {@code users} array), sorted by user name. A user without a textual
     * {@code id} or {@code username} is left out, and logged at DEBUG with the realm's name; a realm without users
     * gives none.
     *
     * @throws UncheckedIOException when {@code in} cannot be read or is not JSON
     */
    public static List<RealmUser> read(InputStream in) {
        JsonNode realm;
        try {
            realm = MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("the realm is not readable JSON", e);
        }
        List<RealmUser> users = new ArrayList<>();
        if (realm == null) {
            return users;
        }
        for (JsonNode user : realm.path("users")) {
            JsonNode id = user.get("id");
            JsonNode username = user.get("username");
            if (id == null || !id.isTextual() || username == null || !username.isTextual()) {
                LOG.debugf(
                        "a user of the dev realm %s has no textual id or username; the Dev UI does not list it",
                        realm.path("realm").asText("(unnamed)"));
                continue;
            }
            List<String> roles = new ArrayList<>();
            for (JsonNode role : user.path("realmRoles")) {
                if (role.isTextual()) {
                    roles.add(role.asText());
                }
            }
            users.add(new RealmUser(id.asText(), username.asText(), roles));
        }
        users.sort(Comparator.comparing(RealmUser::username));
        return users;
    }

    /**
     * The users of the realm export at {@code path}: a class path resource of {@code loader}, else a file, else none. A
     * realm that cannot be read or parsed is logged at WARN and gives no users.
     */
    public static List<RealmUser> load(ClassLoader loader, String path) {
        try {
            try (InputStream resource = loader.getResourceAsStream(path)) {
                if (resource != null) {
                    return read(resource);
                }
            }
            Path file = Path.of(path);
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    return read(in);
                }
            }
            return List.of();
        } catch (IOException | UncheckedIOException | InvalidPathException e) {
            LOG.warnf(e, "the dev realm %s could not be read; the Dev UI shows no dev users", path);
            return List.of();
        }
    }

    /**
     * The users of the realm exports at {@code paths}, each loaded as {@link #load} loads it, merged: sorted by user
     * name, a user whose id (subject) an earlier realm already listed kept once, as that realm lists it.
     */
    public static List<RealmUser> loadAll(ClassLoader loader, List<String> paths) {
        List<RealmUser> users = new ArrayList<>();
        Set<String> subjects = new HashSet<>();
        for (String path : paths) {
            for (RealmUser user : load(loader, path)) {
                if (subjects.add(user.id())) {
                    users.add(user);
                }
            }
        }
        users.sort(Comparator.comparing(RealmUser::username));
        return users;
    }
}
