package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.scheduling.runtime.ScopeProbe;

class HeldLocksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(HeldLocksDbTest.class, ScopeProbe.class);

    @Inject
    SiteScope scope;

    @Inject
    ScopeProbe probe;

    @Inject
    TestSites sites;

    @Test
    void aScopeThreadForAnotherSiteSeesWhatItsSpawnersTransactionRecorded() throws Exception {
        SiteId a = sites.create("held-a");
        SiteId b = sites.create("held-b");
        List<Boolean> seen = scope.call(a, () -> {
            HeldLocks.record("row:1");
            boolean onSpawner = HeldLocks.heldBySpawner("row:1");
            boolean onScopeThread = scope.call(b, () -> HeldLocks.heldBySpawner("row:1"));
            boolean otherName = scope.call(b, () -> HeldLocks.heldBySpawner("row:2"));
            return List.of(onSpawner, onScopeThread, otherName);
        });
        assertThat(seen).containsExactly(false, true, false);
    }

    @Test
    void namesAreCarriedThroughANetworkScopeIntoASiteScope() throws Exception {
        SiteId a = sites.create("deep-a");
        SiteId b = sites.create("deep-b");
        List<Boolean> seen = scope.call(a, () -> {
            HeldLocks.record("row:deep");
            return probe.inNetwork(() -> {
                HeldLocks.record("row:network");
                boolean inNetwork = HeldLocks.heldBySpawner("row:deep");
                List<Boolean> inSite = scope.call(
                        b, () -> List.of(HeldLocks.heldBySpawner("row:deep"), HeldLocks.heldBySpawner("row:network")));
                return List.of(inNetwork, inSite.get(0), inSite.get(1));
            });
        });
        assertThat(seen).containsExactly(true, true, true);
    }

    @Test
    void namesEndWithTheirTransaction() throws Exception {
        SiteId a = sites.create("ended-a");
        SiteId b = sites.create("ended-b");
        scope.call(a, () -> {
            HeldLocks.record("row:ended");
            return null;
        });
        // a fresh scope for the same site has a transaction of its own, which recorded nothing
        boolean afterwards = scope.call(a, () -> scope.call(b, () -> HeldLocks.heldBySpawner("row:ended")));
        assertThat(afterwards).isFalse();
        assertThat(HeldLocks.heldBySpawner("row:ended")).isFalse();
    }

    @Test
    void recordingNeedsATransaction() {
        assertThatThrownBy(() -> HeldLocks.record("row:none"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a held lock is recorded for a transaction, and none is active");
    }
}
