package org.quarkpress.devui.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.devui.runtime.DevRealm.RealmUser;
import org.quarkpress.devui.runtime.DevViews.DevUserView;
import org.quarkpress.devui.runtime.DevViews.SiteRolesView;
import org.quarkpress.security.Membership;

class UsersDevJsonRpcTest {

    @Test
    void aRealmUserWithoutProfileHasNoSiteRolesAndIsNoNetworkAdministrator() {
        UserId amy = UserId.of(7);
        List<DevUserView> views = UsersDevJsonRpc.join(
                List.of(new RealmUser("s-amy", "amy", List.of("x")), new RealmUser("s-ghost", "ghost", List.of())),
                subject -> subject.equals("s-amy") ? Optional.of(amy) : Optional.empty(),
                user -> {
                    if (!user.equals(amy)) {
                        throw new AssertionError("memberships asked for " + user);
                    }
                    return List.of(
                            new Membership(SiteId.of(1), amy, List.of("editor"), Map.of(), Instant.EPOCH),
                            new Membership(SiteId.of(3), amy, List.of("author", "editor"), Map.of(), Instant.EPOCH));
                },
                List.of(amy));
        assertThat(views)
                .containsExactly(
                        new DevUserView(
                                "amy",
                                "s-amy",
                                List.of("x"),
                                true,
                                true,
                                List.of(
                                        new SiteRolesView(1, List.of("editor")),
                                        new SiteRolesView(3, List.of("author", "editor")))),
                        new DevUserView("ghost", "s-ghost", List.of(), false, false, List.of()));
    }
}
