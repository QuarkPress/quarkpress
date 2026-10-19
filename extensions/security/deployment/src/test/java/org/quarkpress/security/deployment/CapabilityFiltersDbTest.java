package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapCheck;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MetaCapRequest;
import org.quarkpress.security.MetaCapabilityFilter;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.UserCapabilitiesFilter;

/** Application filters on both steps of a check, in a network. */
class CapabilityFiltersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(
            CapabilityFiltersDbTest.class,
            TestData.class,
            NoDeletingPosts.class,
            AuthorsEditOthers.class,
            RefusesEditPosts.class);

    /** Refuses deleting posts to everyone, network administrators included. */
    @ApplicationScoped
    public static class NoDeletingPosts implements MetaCapabilityFilter {
        @Override
        public Set<String> apply(Set<String> requiredCaps, MetaCapRequest req) {
            if (!req.capability().equals("delete_post")) {
                return requiredCaps;
            }
            Set<String> refused = new LinkedHashSet<>(requiredCaps);
            refused.add("do_not_allow");
            return refused;
        }
    }

    /** Lets authors edit others' posts; never consulted for network administrators. */
    @ApplicationScoped
    @Priority(10)
    public static class AuthorsEditOthers implements UserCapabilitiesFilter {
        @Override
        public Map<String, Boolean> apply(Map<String, Boolean> caps, CapCheck check) {
            if (!Boolean.TRUE.equals(caps.get("author"))) {
                return caps;
            }
            Map<String, Boolean> more = new LinkedHashMap<>(caps);
            more.put("edit_others_posts", true);
            more.put("do_not_allow", true);
            return more;
        }
    }

    /** Refuses edit_posts to everyone it sees, and records whom it saw. */
    @ApplicationScoped
    public static class RefusesEditPosts implements UserCapabilitiesFilter {

        static final Set<Optional<UserId>> SEEN = ConcurrentHashMap.newKeySet();

        @Override
        public Map<String, Boolean> apply(Map<String, Boolean> caps, CapCheck check) {
            if (!check.capability().equals("edit_posts")) {
                return caps;
            }
            SEEN.add(check.user());
            Map<String, Boolean> refused = new LinkedHashMap<>(caps);
            refused.put("edit_posts", false);
            return refused;
        }
    }

    @Inject
    Capabilities capabilities;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void aMappingFilterRefusesEvenNetworkAdministrators() {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, admin);
        PostFacts post = PostFacts.of(PostId.of(10), PostTypeCapabilities.POST, "draft", Optional.of(admin));
        assertThat(capabilities.userCan(admin, site, "delete_post", Optional.of(post)))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "edit_post", Optional.of(post)))
                .isTrue();
    }

    @Test
    void aCapabilitiesFilterGrantsButCannotGrantDoNotAllow() {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]", "{}");
        PostFacts others = PostFacts.of(PostId.of(11), PostTypeCapabilities.POST, "draft", Optional.empty());
        assertThat(capabilities.userCan(author, site, "edit_post", Optional.of(others)))
                .isTrue();
        assertThat(capabilities.userCan(author, site, "do_not_allow", Optional.empty()))
                .isFalse();
    }

    @Test
    void capabilitiesFiltersAreNeverConsultedForNetworkAdministrators() {
        SiteId site = TestData.site(dataSource);
        UserId networkAdmin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, networkAdmin);
        UserId siteAdmin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, site, siteAdmin, "[\"administrator\"]", "{}");
        // the filter is live: it refuses an administrator of the site
        assertThat(capabilities.userCan(siteAdmin, site, "edit_posts", Optional.empty()))
                .isFalse();
        assertThat(RefusesEditPosts.SEEN).contains(Optional.of(siteAdmin));
        // the network administrator passes before the filters (and the core grants) run
        assertThat(capabilities.userCan(networkAdmin, site, "edit_posts", Optional.empty()))
                .isTrue();
        assertThat(RefusesEditPosts.SEEN).doesNotContain(Optional.of(networkAdmin));
    }
}
