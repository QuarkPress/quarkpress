package org.quarkpress.revisions.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.quarkpress.core.Filters;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.revisions.RevisionChangeFilter;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionFieldsFilter;
import org.quarkpress.revisions.RevisionMetaKeysFilter;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.RevisionsToKeepFilter;

/**
 * What a post's revisions keep and compare: the limit, the revisioned fields and meta keys, and the change decision,
 * each passed through its filters. Call it while bound to the post's site.
 */
@ApplicationScoped
public class RevisionPolicy {

    /** The per-site switch: off, a site saves and lists no revisions. */
    public static final String SITE_FEATURE = "revisions";

    /** The meta key revisioned for every type with an editor, custom fields and revisions. */
    public static final String DEFAULT_META_KEY = "footnotes";

    @Inject
    RevisionsConfig config;

    @Inject
    PostTypes types;

    @Inject
    SiteContext context;

    @Inject
    @All
    List<InstanceHandle<RevisionsToKeepFilter>> keepFilters;

    @Inject
    @All
    List<InstanceHandle<RevisionFieldsFilter>> fieldFilters;

    @Inject
    @All
    List<InstanceHandle<RevisionMetaKeysFilter>> keyFilters;

    @Inject
    @All
    List<InstanceHandle<RevisionChangeFilter>> changeFilters;

    /**
     * The post's limit: the configured one (0 for a type without revisions support), then the filters, then 0 when
     * the site's revisions feature is off.
     */
    public int keep(PostSnapshot post) {
        int keep = RevisionRules.keep(config.keep(), types.supports(post.type(), "revisions"));
        keep = Filters.apply(Filters.ordered(keepFilters), keep, (filter, value) -> filter.apply(value, post));
        return context.features().isEnabled(SITE_FEATURE) ? keep : 0;
    }

    /** Whether the post saves revisions: its limit is not 0. */
    public boolean enabled(PostSnapshot post) {
        return RevisionRules.enabled(keep(post));
    }

    /** The revisioned fields of the post, unmodifiable, in the order the filters left them. */
    public Set<RevisionField> fields(PostSnapshot post) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Filters.apply(
                Filters.ordered(fieldFilters), RevisionField.all(), (filter, value) -> filter.apply(value, post))));
    }

    /** The revisioned meta keys of the type, unmodifiable, in the order the filters left them. */
    public Set<String> metaKeys(String type) {
        Set<String> keys = new LinkedHashSet<>();
        if (types.supports(type, "editor")
                && types.supports(type, "custom-fields")
                && types.supports(type, "revisions")) {
            keys.add(DEFAULT_META_KEY);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                Filters.apply(Filters.ordered(keyFilters), keys, (filter, value) -> filter.apply(value, type))));
    }

    /** Whether the post changed since {@code last}, its latest regular revision. */
    public boolean changed(RevisionSnapshot last, PostSnapshot current) {
        boolean changed = RevisionRules.fieldsChanged(fields(current), last, current)
                || RevisionRules.metaChanged(metaKeys(current.type()), last.meta(), current.meta());
        return Filters.apply(
                Filters.ordered(changeFilters), changed, (filter, value) -> filter.apply(value, last, current));
    }
}
