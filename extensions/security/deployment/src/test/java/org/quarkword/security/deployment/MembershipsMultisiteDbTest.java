package org.quarkword.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.security.Memberships;
import org.quarkword.security.SystemIdentity;

class MembershipsMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(MembershipsMultisiteDbTest.class, TestData.class);

    @Inject
    Memberships memberships;

    @Inject
    SystemIdentity system;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void inANetworkAMemberHasARowOnASiteThatIsNotArchivedSpamOrDeleted() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "member");
        assertThat(memberships.isMember(site, user)).isFalse();
        system.call(() -> memberships.add(site, user, ""));
        assertThat(memberships.isMember(site, user)).isTrue();
        for (String flag : new String[] {"archived", "spam", "deleted"}) {
            Jdbc.update(dataSource, "update qw_site set " + flag + " = true where id = ?", site.value());
            assertThat(memberships.isMember(site, user)).as(flag).isFalse();
            Jdbc.update(dataSource, "update qw_site set " + flag + " = false where id = ?", site.value());
        }
        Jdbc.update(dataSource, "update qw_site set mature = true where id = ?", site.value());
        assertThat(memberships.isMember(site, user)).isTrue();
    }
}
