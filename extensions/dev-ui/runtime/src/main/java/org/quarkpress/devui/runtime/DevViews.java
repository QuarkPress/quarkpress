package org.quarkpress.devui.runtime;

import java.util.List;
import java.util.Locale;
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
}
