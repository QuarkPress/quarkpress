package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.MemberColumn;
import org.quarkpress.security.MemberOrder;
import org.quarkpress.security.MemberPage;
import org.quarkpress.security.MemberQuery;
import org.quarkpress.security.Memberships;

/**
 * On a single site the listing holds every user, as WordPress's single-site user query does: a profile without a
 * membership row is listed, found by search and counted, but never matches a role or capability filter.
 */
class MembersSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(MembersSingleSiteDbTest.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    Memberships memberships;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    String u;
    UserId editor;
    UserId roleless;
    UserId outsider;

    @BeforeEach
    void users() {
        u = UUID.randomUUID().toString().substring(0, 8);
        editor = profile("ed-" + u, "Edna " + u);
        TestData.member(dataSource, MAIN, editor, "[\"editor\"]", "{}");
        roleless = profile("ro-" + u, "Rolf " + u);
        TestData.member(dataSource, MAIN, roleless, "[]", "{}");
        outsider = profile("ou-" + u, "Otto " + u);
    }

    private UserId profile(String nicename, String displayName) {
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename) values (?, ?, ?)",
                nicename + "-subject",
                displayName,
                nicename);
        return UserId.of(Jdbc.count(dataSource, "select id from qp_user_profile where nicename = ?", nicename));
    }

    @Test
    void everyUserIsListedAndCounted() {
        MemberPage page = memberships.members(
                MAIN,
                MemberQuery.builder()
                        .include(List.of(editor, roleless, outsider))
                        .orderBy(MemberOrder.ID)
                        .build());
        assertThat(page.users()).containsExactly(editor, roleless, outsider);
        assertThat(page.total()).isEqualTo(3);
        MemberPage found = memberships.members(
                MAIN,
                MemberQuery.builder()
                        .search(u)
                        .searchColumns(Set.of(MemberColumn.DISPLAY_NAME))
                        .orderBy(MemberOrder.NAME)
                        .build());
        assertThat(found.users()).containsExactly(editor, outsider, roleless);
        // a search by id finds the user without a membership row
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .search(Long.toString(outsider.value()))
                                        .searchColumns(Set.of(MemberColumn.ID))
                                        .build())
                        .users())
                .containsExactly(outsider);
        assertThat(memberships.isMember(MAIN, outsider)).isTrue();
    }

    @Test
    void roleAndCapabilityFiltersNeedAStoredKey() {
        List<UserId> ours = List.of(editor, roleless, outsider);
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .include(ours)
                                        .roles(List.of("editor"))
                                        .build())
                        .users())
                .containsExactly(editor);
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .include(ours)
                                        .capabilities(List.of("read"))
                                        .build())
                        .users())
                .containsExactly(editor);
        // exclude and slugs reach every user too
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .slugs(List.of("ou-" + u, "ro-" + u))
                                        .exclude(List.of(roleless))
                                        .build())
                        .users())
                .containsExactly(outsider);
    }
}
