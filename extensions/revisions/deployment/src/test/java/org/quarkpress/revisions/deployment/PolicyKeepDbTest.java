package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.revisions.runtime.RevisionPolicy;

/** The configured limit is every post's starting point. */
class PolicyKeepDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RevisionsApp.with(PolicyKeepDbTest.class).overrideConfigKey("quarkpress.revisions.keep", "2");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    RevisionPolicy policy;

    static final java.time.Instant AT = java.time.Instant.parse("2026-01-15T12:00:00Z");

    static org.quarkpress.posts.PostSnapshot post(SiteId site, String type) {
        return new org.quarkpress.posts.PostSnapshot(
                org.quarkpress.core.PostId.of(7),
                site,
                type,
                "publish",
                "s",
                "t",
                "c",
                "e",
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                0,
                java.util.Optional.of(AT),
                AT,
                AT,
                "open",
                "open",
                "",
                false,
                "g",
                java.util.Map.of(),
                1);
    }

    @Test
    void aConfiguredLimitAppliesToEveryRevisionedType() throws Exception {
        SiteId site = TestData.site(dataSource);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "post"))))
                .isEqualTo(2);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "page"))))
                .isEqualTo(2);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "attachment"))))
                .isZero();
    }
}
