package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Membership;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.SystemIdentity;

class MembershipsSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(MembershipsSingleSiteDbTest.class, TestData.class);

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void onASingleSiteEveryUserIsAMember() {
        UserId user = TestData.profile(dataSource, "anyone");
        assertThat(memberships.isMember(SiteId.of(1), user)).isTrue();
        assertThat(memberships.isMember(SiteId.of(1), UserId.of(999_999_999L))).isFalse();
    }

    @Test
    void onASingleSiteMembershipsAreOfTheOnlySite() throws Exception {
        UserId user = TestData.profile(dataSource, "single");
        SiteId main = SiteId.of(1);
        SiteId other = SiteId.of(42);
        Membership added = system.call(() -> memberships.add(other, user, "author"));
        assertThat(added.site()).isEqualTo(main);
        assertThat(memberships.find(other, user)).map(Membership::site).contains(main);
        assertThat(capabilities.userCan(user, main, "publish_posts", Optional.empty()))
                .isTrue();
        assertThat(system.call(() -> memberships.setCapability(other, user, "upload_files", false))
                        .capabilities())
                .containsEntry("upload_files", false);
        assertThat(system.call(() -> memberships.remove(other, user))).isTrue();
        assertThat(memberships.find(main, user)).isEmpty();
    }
}
