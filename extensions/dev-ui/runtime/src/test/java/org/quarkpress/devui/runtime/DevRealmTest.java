package org.quarkpress.devui.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.devui.runtime.DevRealm.RealmUser;

class DevRealmTest {

    @Test
    void usersComeWithTheirIdsAndRealmRolesInUsernameOrder() {
        String json = "{\"realm\":\"r\",\"users\":[{\"id\":\"2\",\"username\":\"zed\"},"
                + "{\"id\":\"1\",\"username\":\"amy\",\"realmRoles\":[\"x\"]}]}";
        assertThat(DevRealm.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                .containsExactly(new RealmUser("1", "amy", List.of("x")), new RealmUser("2", "zed", List.of()));
    }

    @Test
    void aRealmWithoutUsersGivesNone() {
        assertThat(DevRealm.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
    }

    @Test
    void aMissingRealmGivesNoUsers() {
        assertThat(DevRealm.load(getClass().getClassLoader(), "no-such-realm.json"))
                .isEmpty();
    }

    @Test
    void theShippedRealmHasTheFiveDevUsers() {
        List<RealmUser> users = DevRealm.load(getClass().getClassLoader(), "quarkpress-dev-realm.json");
        assertThat(users).extracting(RealmUser::username).containsExactly("alice", "bob", "carol", "dave", "erin");
        assertThat(users.get(1).id()).isEqualTo("b0b00000-0000-4000-8000-000000000002");
        assertThat(users.get(1).realmRoles()).containsExactly("administrator");
    }

    @Test
    void malformedJsonIsRefused() {
        assertThatThrownBy(
                        () -> DevRealm.read(new ByteArrayInputStream("{\"users\":[".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("the realm is not readable JSON");
    }

    @Test
    void usersWithoutATextualIdOrUsernameAreLeftOut() {
        String json = "{\"realm\":\"r\",\"users\":[{\"username\":\"noid\"},{\"id\":\"2\"},"
                + "{\"id\":3,\"username\":\"numeric\"},{\"id\":\"4\",\"username\":[\"list\"]},"
                + "{\"id\":\"5\",\"username\":\"kept\"}]}";
        assertThat(DevRealm.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                .containsExactly(new RealmUser("5", "kept", List.of()));
    }

    @Test
    void aMalformedRealmGivesNoUsers() {
        assertThat(DevRealm.load(getClass().getClassLoader(), "dev-realms/malformed.json"))
                .isEmpty();
    }

    @Test
    void severalRealmsAreMergedByUsernameEachSubjectOnce() {
        assertThat(DevRealm.loadAll(
                        getClass().getClassLoader(),
                        List.of("dev-realms/first.json", "no-such-realm.json", "dev-realms/second.json")))
                .containsExactly(
                        new RealmUser("s-3", "ann", List.of()),
                        new RealmUser("s-1", "max", List.of()),
                        new RealmUser("s-2", "zoe", List.of("a")));
    }
}
