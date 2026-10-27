package org.quarkpress.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.scheduling.ScheduledPosts;
import org.quarkpress.scheduling.ScheduledPosts.Upcoming;
import org.quarkpress.security.SystemIdentity;

class ScheduledPostsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(ScheduledPostsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    ScheduledPosts scheduled;

    @Inject
    SystemIdentity system;

    @Test
    void theNextScheduledPostsOfEverySiteComeSoonestFirstOverdueIncluded() throws Exception {
        SiteId other = TestData.site(dataSource);
        TestData.featureOff(dataSource, other, "scheduling");
        PostId late = TestData.scheduled(dataSource, TestData.MAIN, "Late", "2090-01-01T00:00:00Z");
        PostId early = TestData.scheduled(dataSource, other, "Early", "2089-12-31T00:00:00Z");
        PostId tie = TestData.scheduled(dataSource, TestData.MAIN, "Tie", "2089-12-31T00:00:00Z");
        PostId overdue = TestData.scheduled(dataSource, other, "Overdue", "2020-01-01T00:00:00Z");
        TestData.post(dataSource, TestData.MAIN, "post", "publish", "", "Published");
        // a scheduled post without a GMT date is not listed
        PostId undated = TestData.scheduled(dataSource, TestData.MAIN, "Undated", "2089-06-01T00:00:00Z");
        Jdbc.update(dataSource, "update qp_post set published_at = null where id = ?", undated.value());
        assertThat(next(10))
                .containsExactly(
                        new Upcoming(other, overdue, "post", "Overdue", Instant.parse("2020-01-01T00:00:00Z")),
                        new Upcoming(TestData.MAIN, tie, "post", "Tie", Instant.parse("2089-12-31T00:00:00Z")),
                        new Upcoming(other, early, "post", "Early", Instant.parse("2089-12-31T00:00:00Z")),
                        new Upcoming(TestData.MAIN, late, "post", "Late", Instant.parse("2090-01-01T00:00:00Z")));
        assertThat(next(2)).extracting(Upcoming::post).containsExactly(overdue, tie);
    }

    @Test
    void aLimitOutsideOneToOneHundredIsRefused() throws Exception {
        assertThatThrownBy(() -> next(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100: 0");
        assertThatThrownBy(() -> next(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100: 101");
        // the bounds themselves are accepted (other tests of this class may have left scheduled posts)
        assertThat(next(1)).hasSizeLessThanOrEqualTo(1);
        assertThat(next(100)).isNotNull();
    }

    @Test
    void aCallerWithoutManageSitesIsRefusedBeforeAnythingIsRead() {
        // a network, where only network administrators and the system hold network capabilities
        TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, TestData.MAIN, admin, "[\"administrator\"]");
        assertThatThrownBy(() -> Identities.onSite(TestData.MAIN, Identities.user(admin), () -> scheduled.next(10)))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability manage_sites")
                .satisfies(e ->
                        assertThat(((CapabilityDeniedException) e).anonymous()).isFalse());
        assertThatThrownBy(() -> scheduled.next(10))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability manage_sites")
                .satisfies(e ->
                        assertThat(((CapabilityDeniedException) e).anonymous()).isTrue());
    }

    /** {@code next(limit)} as the system identity, which holds {@code manage_sites}. */
    private List<Upcoming> next(int limit) throws Exception {
        return system.call(() -> scheduled.next(limit));
    }
}
