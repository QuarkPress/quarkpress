package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SiteLookupTest {

    @Test
    void theLongestNetworkPathFitsOneLookup() {
        // 49 one-letter segments: the most a 100-character path holds
        String networkPath = "/" + "a/".repeat(49);
        String request = networkPath + "b/".repeat(40);
        int segments = SiteAddresses.lookupSegments(
                "localhost", request, "localhost", networkPath, MultisiteSettingsView.MAX_PATH_SEGMENTS);
        List<String> candidates = SiteAddresses.candidatePaths(request, segments);
        // the scan stops before the segment that would make a candidate longer than a stored path can be
        assertThat(candidates).hasSize(49 + 1);
        assertThat(candidates.size()).isLessThanOrEqualTo(SiteLookup.MAX_CANDIDATES);
        assertThat(SiteLookup.marks(candidates.size())).hasSize(3 * candidates.size() - 2);
        assertThat(SiteAddresses.candidatePaths(networkPath, Integer.MAX_VALUE).size())
                .isLessThanOrEqualTo(SiteLookup.MAX_CANDIDATES);
    }

    @Test
    void theMostConfigurableSegmentsFitOneLookup() {
        String networkPath = "/a/";
        String request = networkPath + "b/".repeat(40);
        int segments = SiteAddresses.lookupSegments(
                "localhost", request, "localhost", networkPath, MultisiteSettingsView.MAX_PATH_SEGMENTS);
        List<String> candidates = SiteAddresses.candidatePaths(request, segments);
        assertThat(candidates).hasSize(1 + MultisiteSettingsView.MAX_PATH_SEGMENTS + 1);
        assertThat(candidates.size()).isLessThanOrEqualTo(SiteLookup.MAX_CANDIDATES);
    }

    @Test
    void moreValuesThanALookupComparesAreRefused() {
        assertThatThrownBy(() -> SiteLookup.marks(SiteLookup.MAX_CANDIDATES + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site lookup compares 1 to " + SiteLookup.MAX_CANDIDATES + " values: "
                        + (SiteLookup.MAX_CANDIDATES + 1));
    }
}
