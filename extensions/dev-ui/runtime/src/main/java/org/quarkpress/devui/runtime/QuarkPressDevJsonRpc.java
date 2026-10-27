package org.quarkpress.devui.runtime;

import io.quarkus.flyway.FlywayDataSource;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.SiteContext;
import org.quarkpress.devui.runtime.DevViews.FeatureView;
import org.quarkpress.devui.runtime.DevViews.MigrationView;
import org.quarkpress.devui.runtime.DevViews.PersistenceView;
import org.quarkpress.devui.runtime.DevViews.SetFeatureResult;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.QuarkPressPersistence;

/**
 * The Dev UI's JSON-RPC methods of the persistence module: the features of a site, switching one of them on a site,
 * and the applied migrations. Dev UI runs each call on a worker thread (its methods are blocking unless marked
 * otherwise); the call then hops to a detached thread of its own.
 */
public class QuarkPressDevJsonRpc {

    @Inject
    DevCalls calls;

    @Inject
    SiteContext siteContext;

    @Inject
    FeatureRegistry registry;

    @Inject
    FeatureToggles toggles;

    @Inject
    @FlywayDataSource(QuarkPressPersistence.UNIT)
    Flyway flyway;

    @ConfigProperty(name = "quarkus.datasource.\"quarkpress\".db-kind")
    String dbKind;

    @ConfigProperty(name = "quarkus.flyway.\"quarkpress\".table", defaultValue = "qp_schema_history")
    String historyTable;

    /** Every feature, by key, with its effective value on site {@code siteId} and where the value comes from. */
    public List<FeatureView> features(long siteId) {
        return calls.onSite(siteId, () -> {
            List<FeatureView> views = new ArrayList<>();
            for (FeatureValue value : siteContext.features().all()) {
                views.add(view(value));
            }
            return views;
        });
    }

    /**
     * Switches feature {@code key} on or off on site {@code siteId}, as the site's own switch (also when it equals the
     * default), and answers the feature's effective value afterwards, read in a scope of its own. An unknown feature is
     * answered as the error of the result.
     *
     * @throws IllegalArgumentException when {@code siteId} is not a valid site id, or no site has it; nothing is
     *     written
     */
    public SetFeatureResult setFeature(long siteId, String key, boolean enabled) {
        String refusal = calls.onSite(siteId, () -> {
            try {
                toggles.setForSite(key, enabled);
                return null;
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        });
        if (refusal != null) {
            return new SetFeatureResult(null, refusal);
        }
        return new SetFeatureResult(
                calls.onSite(siteId, () -> view(siteContext.features().value(key))), null);
    }

    /** QuarkPress's persistence unit and datasource, the database kind and the applied migrations. */
    public PersistenceView persistence() {
        return calls.asSystem(() -> {
            List<MigrationView> migrations = new ArrayList<>();
            for (MigrationInfo info : flyway.info().applied()) {
                migrations.add(new MigrationView(
                        info.getVersion() == null ? null : info.getVersion().getVersion(),
                        info.getDescription(),
                        fileName(info.getScript()),
                        info.getInstalledOn() == null
                                ? null
                                : info.getInstalledOn().toInstant().toString(),
                        !info.getState().isFailed()));
            }
            return new PersistenceView(
                    QuarkPressPersistence.UNIT, QuarkPressPersistence.UNIT, dbKind, historyTable, migrations);
        });
    }

    private FeatureView view(FeatureValue value) {
        FeatureDescriptor descriptor = registry.find(value.key()).orElseThrow();
        return new FeatureView(
                value.key(),
                descriptor.module(),
                descriptor.description(),
                value.enabled(),
                value.source().name().toLowerCase(Locale.ROOT));
    }

    /** The file name of a migration script, without the location Flyway found it in. */
    private static String fileName(String script) {
        return script == null ? null : script.substring(script.lastIndexOf('/') + 1);
    }
}
