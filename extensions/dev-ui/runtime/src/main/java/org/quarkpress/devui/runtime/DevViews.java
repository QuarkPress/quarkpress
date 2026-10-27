package org.quarkpress.devui.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.quarkpress.core.SiteInfo;

/** What the Dev UI's JSON-RPC methods answer: plain records of JDK types, serialized as they are. */
public final class DevViews {

    private DevViews() {}

    /** A site of the network; {@code status} is its derived state in lower case ({@code active}, {@code spam}...). */
    public record SiteView(
            long id,
            String domain,
            String path,
            String name,
            int publicFlag,
            boolean archived,
            boolean mature,
            boolean spam,
            boolean deleted,
            String status) {

        public static SiteView of(SiteInfo site) {
            return new SiteView(
                    site.id().value(),
                    site.domain(),
                    site.path(),
                    site.name(),
                    site.publicFlag(),
                    site.archived(),
                    site.mature(),
                    site.spam(),
                    site.deleted(),
                    site.status().name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A per-site feature on one site: its effective value and where the value comes from ({@code site},
     * {@code network} or {@code module}).
     */
    public record FeatureView(String key, String module, String description, boolean enabled, String source) {}

    /** An applied migration; {@code installedOn} is an ISO-8601 instant. */
    public record MigrationView(
            String version, String description, String script, String installedOn, boolean success) {}

    /** QuarkPress's persistence unit, its datasource and the migrations applied to it, in Flyway's order. */
    public record PersistenceView(
            String persistenceUnit,
            String datasource,
            String dbKind,
            String historyTable,
            List<MigrationView> migrations) {

        public PersistenceView {
            migrations = List.copyOf(migrations);
        }
    }

    /** What {@code createSite} answers: the new site, or the multisite module's refusal; exactly one is non-null. */
    public record CreateSiteResult(SiteView site, String error) {}

    /**
     * What {@code setFeature} and {@code clearFeature} answer: the feature's effective value after the write, or the
     * refusal of the feature toggles; exactly one is non-null.
     */
    public record SetFeatureResult(FeatureView feature, String error) {}

    /** A registered post type; {@code label} is its plural name label. */
    public record PostTypeView(
            String key, String label, boolean hierarchical, boolean publicly, List<String> supports, boolean builtIn) {

        public PostTypeView {
            supports = List.copyOf(supports);
        }
    }

    /** A registered post status. */
    public record StatusView(
            String name, String label, boolean publicly, boolean privateStatus, boolean internal, boolean builtIn) {}

    /** A registered taxonomy; {@code label} is its plural name label. */
    public record TaxonomyView(
            String key, String label, List<String> objectTypes, boolean hierarchical, boolean builtIn) {

        public TaxonomyView {
            objectTypes = List.copyOf(objectTypes);
        }
    }

    /** A role of a site: its key, its display name and its capabilities in order (false is a denial). */
    public record RoleView(String key, String name, Map<String, Boolean> capabilities) {}

    /**
     * The registries of a site: post types, post statuses, taxonomies (null without the taxonomy module) and the
     * site's roles.
     */
    public record Registries(
            List<PostTypeView> postTypes,
            List<StatusView> statuses,
            List<TaxonomyView> taxonomies,
            List<RoleView> roles) {}

    /**
     * The content of a site: post counts by type, then by status, only where posts exist; term counts by taxonomy
     * (null without the taxonomy module); the number of revisions, autosaves included (null without the revisions
     * module). The maps are ordered by key.
     */
    public record Counts(Map<String, Map<String, Long>> posts, Map<String, Long> terms, Long revisions) {}

    /** A scheduled post of the scheduler page; {@code publishAt} is an ISO-8601 instant. */
    public record UpcomingView(long site, long post, String type, String title, String publishAt) {}

    /** A post the polling job published. */
    public record PublishedView(long site, long post) {}

    /**
     * A failure of a scheduling job: the site and post it concerned (null when none), its message and when it happened
     * (an ISO-8601 instant).
     */
    public record FailureView(Long site, Long post, String message, String at) {}

    /**
     * The scheduler page: the next scheduled posts of every site, soonest first, and this node's scheduling status
     * since it started. The instants are ISO-8601; each is null, as {@code lastFailure} is, until it happened.
     */
    public record SchedulerView(
            List<UpcomingView> upcoming,
            String lastPollStarted,
            String lastPollEnded,
            List<PublishedView> lastBatch,
            long publishedSinceStart,
            FailureView lastFailure,
            String lastHousekeeping) {

        public SchedulerView {
            upcoming = List.copyOf(upcoming);
            lastBatch = List.copyOf(lastBatch);
        }
    }

    /** A user's roles on one site, in order. */
    public record SiteRolesView(long site, List<String> roles) {

        public SiteRolesView {
            roles = List.copyOf(roles);
        }
    }

    /**
     * A user of the dev realm: its user name, its subject (the realm's user id), its realm roles, whether QuarkPress
     * has a profile for it, whether that profile is a network administrator, and its roles per site, by site id.
     */
    public record DevUserView(
            String username,
            String subject,
            List<String> realmRoles,
            boolean hasProfile,
            boolean networkAdmin,
            List<SiteRolesView> siteRoles) {

        public DevUserView {
            realmRoles = List.copyOf(realmRoles);
            siteRoles = List.copyOf(siteRoles);
        }
    }
}
