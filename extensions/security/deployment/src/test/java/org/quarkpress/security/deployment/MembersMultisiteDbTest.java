package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MemberColumn;
import org.quarkpress.security.MemberPage;
import org.quarkpress.security.MemberQuery;
import org.quarkpress.security.Memberships;

/** In a network a site lists only its own members, whatever filter names another site's member. */
class MembersMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(MembersMultisiteDbTest.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void aMemberOfAnotherSiteOnlyIsNotListedForTheMainSite() {
        SiteId other = TestData.site(dataSource);
        UserId elsewhere = TestData.profile(dataSource, "elsewhere");
        TestData.member(dataSource, other, elsewhere, "[\"administrator\"]", "{}");
        UserId both = TestData.profile(dataSource, "both");
        TestData.member(dataSource, MAIN, both, "[\"subscriber\"]", "{}");
        TestData.member(dataSource, other, both, "[\"editor\"]", "{}");

        MemberPage main = memberships.members(
                MAIN, MemberQuery.builder().include(List.of(elsewhere, both)).build());
        assertThat(main.users()).containsExactly(both);
        assertThat(main.total()).isEqualTo(1);
        // the other site's role and capabilities never count on the main site
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .roles(List.of("administrator", "editor"))
                                        .build())
                        .users())
                .doesNotContain(elsewhere, both);
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .capabilities(List.of("edit_others_posts"))
                                        .build())
                        .users())
                .doesNotContain(elsewhere, both);
        assertThat(memberships
                        .members(
                                MAIN,
                                MemberQuery.builder()
                                        .search(String.valueOf(elsewhere.value()))
                                        .searchColumns(Set.of(MemberColumn.ID))
                                        .build())
                        .users())
                .isEmpty();

        MemberPage onOther = memberships.members(
                other,
                MemberQuery.builder().roles(List.of("administrator", "editor")).build());
        assertThat(onOther.users()).containsExactly(both, elsewhere);
        assertThat(onOther.total()).isEqualTo(2);

        assertThat(capabilities.allCapabilities(elsewhere, MAIN)).isEmpty();
        assertThat(capabilities.allCapabilities(elsewhere, other)).containsEntry("administrator", true);
        assertThat(capabilities.allCapabilities(both, MAIN))
                .containsEntry("subscriber", true)
                .doesNotContainKey("editor");
    }
}
