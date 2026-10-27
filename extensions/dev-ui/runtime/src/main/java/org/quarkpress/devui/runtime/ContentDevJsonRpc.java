package org.quarkpress.devui.runtime;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.devui.runtime.DevViews.Counts;
import org.quarkpress.devui.runtime.DevViews.PostTypeView;
import org.quarkpress.devui.runtime.DevViews.Registries;
import org.quarkpress.devui.runtime.DevViews.RoleView;
import org.quarkpress.devui.runtime.DevViews.StatusView;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.SiteRoles;

/**
 * The Dev UI's JSON-RPC methods of the posts module: a site's registries and how much content it holds. The parts of
 * the taxonomy and revisions modules come through {@link TaxonomyReads} and {@link RevisionReads}, which name none of
 * those modules' types, so this class loads without them; their parts are then null. The security module needs no such
 * care: the posts module cannot be installed without it.
 */
public class ContentDevJsonRpc {

    @Inject
    DevCalls calls;

    @Inject
    PostTypes postTypes;

    @Inject
    PostStatuses postStatuses;

    @Inject
    Posts posts;

    @Inject
    SiteRoles roles;

    @Inject
    Instance<TaxonomyReads> taxonomyReads;

    @Inject
    Instance<RevisionReads> revisionReads;

    /**
     * The post types, post statuses and taxonomies registered, in registration order, and the roles of site
     * {@code siteId} with their capabilities. The taxonomies are null without the taxonomy module.
     *
     * @throws IllegalArgumentException when {@code siteId} is not a valid site id, or no site has it
     */
    public Registries registries(long siteId) {
        return calls.onSite(siteId, () -> {
            List<PostTypeView> types = new ArrayList<>();
            for (PostTypeDefinition type : postTypes.all()) {
                types.add(new PostTypeView(
                        type.key(),
                        type.labels().get("name"),
                        type.hierarchical(),
                        type.publicly(),
                        type.supports(),
                        type.builtIn()));
            }
            List<StatusView> statuses = new ArrayList<>();
            for (PostStatusDefinition status : postStatuses.all()) {
                statuses.add(new StatusView(
                        status.name(),
                        status.label(),
                        status.publicly(),
                        status.privateStatus(),
                        status.internal(),
                        status.builtIn()));
            }
            List<RoleView> roleViews = new ArrayList<>();
            for (RoleDefinition role : roles.roles(SiteId.of(siteId))) {
                roleViews.add(new RoleView(role.key(), role.displayName(), role.capabilities()));
            }
            return new Registries(
                    types,
                    statuses,
                    taxonomyReads.isResolvable() ? taxonomyReads.get().taxonomies() : null,
                    roleViews);
        });
    }

    /**
     * The content of site {@code siteId}: its posts by type and status (only the pairs that have posts), its terms by
     * taxonomy (every registered taxonomy) and its revisions, autosaves included. The terms are null without the
     * taxonomy module, the revisions without the revisions module.
     *
     * @throws IllegalArgumentException when {@code siteId} is not a valid site id, or no site has it
     */
    public Counts counts(long siteId) {
        return calls.onSite(siteId, () -> {
            Map<String, Map<String, Long>> postCounts = new TreeMap<>();
            for (PostTypeDefinition type : postTypes.all()) {
                Map<String, Long> byStatus = new TreeMap<>();
                for (PostStatusDefinition status : postStatuses.all()) {
                    long found = posts.query(PostQuery.builder()
                                    .types(type.key())
                                    .statuses(status.name())
                                    .perPage(1)
                                    .build())
                            .found();
                    if (found > 0) {
                        byStatus.put(status.name(), found);
                    }
                }
                if (!byStatus.isEmpty()) {
                    postCounts.put(type.key(), byStatus);
                }
            }
            return new Counts(
                    postCounts,
                    taxonomyReads.isResolvable() ? taxonomyReads.get().termCounts() : null,
                    revisionReads.isResolvable() ? revisionReads.get().count() : null);
        });
    }
}
