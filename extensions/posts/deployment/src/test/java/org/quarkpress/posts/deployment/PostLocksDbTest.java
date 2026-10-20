package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.runtime.PostLocks;

class PostLocksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostLocksDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PostLocks locks;

    @Inject
    SiteScope scope;

    @Test
    void theLockRowIsCreatedOnce() throws Exception {
        SiteId site = TestData.site(dataSource);
        scope.run(site, () -> locks.lock(site));
        scope.run(site, () -> locks.lock(site));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post_lock where site_id = ?", site.value()))
                .isEqualTo(1);
    }

    @Test
    void writersOfOneSiteRunOneAfterTheOther() throws Exception {
        SiteId site = TestData.site(dataSource);
        assertWritersDoNotOverlap(site);
    }

    @Test
    void writersOfOneSiteRunOneAfterTheOtherOnceTheLockRowExists() throws Exception {
        SiteId site = TestData.site(dataSource);
        scope.run(site, () -> locks.lock(site));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post_lock where site_id = ?", site.value()))
                .isEqualTo(1);
        assertWritersDoNotOverlap(site);
    }

    private void assertWritersDoNotOverlap(SiteId site) throws Exception {
        AtomicLong inside = new AtomicLong();
        AtomicLong overlaps = new AtomicLong();
        Callable<Object> writer = () -> {
            scope.run(site, () -> {
                locks.lock(site);
                if (inside.incrementAndGet() > 1) {
                    overlaps.incrementAndGet();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inside.decrementAndGet();
            });
            return null;
        };
        assertThat(Races.run(List.of(writer, writer, writer))).containsExactly(null, null, null);
        assertThat(overlaps.get()).isZero();
    }

    @Test
    void aNestedScopeThatWouldWaitForItsSpawnersLockIsRefused() {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> scope.run(site, () -> {
                    locks.lock(site);
                    scope.run(other, () -> scope.run(site, () -> locks.lock(site)));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the posts of site " + site.value()
                        + " are locked by a transaction that waits for this change: change the posts of one site in"
                        + " one transaction at a time");
    }

    @Test
    void theLockRefusesAnotherSitesBinding() {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> scope.run(site, () -> locks.lock(other)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the post lock of site " + other.value() + " taken under " + Binding.site(site));
    }
}
