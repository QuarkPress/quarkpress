package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * WordPress's content routes for every post type shown in REST but attachments and blocks (which have controllers
 * of their own), in registration order: {@code /<namespace>/<base>} (the collection), where the base is the type's
 * REST base or its key. Posts are read through the posts module for the request's site, which shows each caller
 * only what it may read, so the totals count readable posts.
 */
@ApplicationScoped
public class PostsController implements RestRouteProvider {

    /** The types whose routes other controllers serve. */
    private static final List<String> OWN_CONTROLLERS = List.of("attachment", "wp_block");

    @Inject
    PostTypes types;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostStatuses statuses;

    @Inject
    PostPermissions permissions;

    @Inject
    SecurityIdentity identity;

    @Inject
    MetaFields meta;

    @Inject
    PostCollection collection;

    @Inject
    RestUrls urls;

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void register(RouteRegistry routes) {
        List<String> allStatuses = new ArrayList<>();
        List<String> creatable = new ArrayList<>();
        for (PostStatusDefinition status : statuses.all()) {
            allStatuses.add(status.name());
            if (!status.internal()) {
                creatable.add(status.name());
            }
        }
        for (PostTypeDefinition type : types.all()) {
            if (!type.showInRest() || OWN_CONTROLLERS.contains(type.key())) {
                continue;
            }
            List<TaxonomyDefinition> restTaxonomies = new ArrayList<>();
            for (TaxonomyDefinition taxonomy : taxonomies.all()) {
                if (taxonomy.showInRest() && taxonomy.attachedTo(type.key())) {
                    restTaxonomies.add(taxonomy);
                }
            }
            String base = type.restBase().filter(rest -> !rest.isEmpty()).orElse(type.key());
            ObjectNode schema = PostSchema.of(type, restTaxonomies, meta.schema(type.key()), creatable);
            PostRoutes post =
                    new PostRoutes(type, TypeExtras.of(type.key()).restNamespace(), base, schema, restTaxonomies);
            register(routes, post, allStatuses);
        }
    }

    private void register(RouteRegistry routes, PostRoutes post, List<String> allStatuses) {
        routes.route(post.namespace(), "/" + post.base())
                .schema(() -> PostSchema.withLinks(post, urls))
                .endpoint(Endpoint.of("GET")
                        .args(PostSchema.collectionArgs(post, allStatuses, () -> identity, permissions))
                        .handler(request -> collection.list(post, request))
                        .allowBatch(true)
                        .build())
                .register();
    }
}
