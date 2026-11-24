package org.quarkword.front.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.posts.PostPermissions;

/**
 * The query variables a front-end request may set from its URL: WordPress's fixed list, the REST and sitemap
 * variables, the query variable of every publicly queryable taxonomy and the query variable of every viewable post
 * type, in that order. Everything else in a query string is dropped by the parser.
 */
public final class PublicQueryVars {

    static final List<String> FIXED = List.of(
            "m",
            "p",
            "posts",
            "w",
            "cat",
            "withcomments",
            "withoutcomments",
            "s",
            "search",
            "exact",
            "sentence",
            "calendar",
            "page",
            "paged",
            "more",
            "tb",
            "pb",
            "author",
            "order",
            "orderby",
            "year",
            "monthnum",
            "day",
            "hour",
            "minute",
            "second",
            "name",
            "category_name",
            "tag",
            "feed",
            "author_name",
            "pagename",
            "page_id",
            "error",
            "attachment",
            "attachment_id",
            "subpost",
            "subpost_id",
            "preview",
            "robots",
            "favicon",
            "taxonomy",
            "term",
            "cpage",
            "post_type",
            "embed");

    static final List<String> COMPONENTS = List.of("rest_route", "sitemap", "sitemap-subtype", "sitemap-stylesheet");

    private final List<String> names;
    private final Set<String> nameSet;
    private final Map<String, PostTypeDefinition> typesByVar;
    private final Set<String> taxonomyVars;
    private final Set<String> publiclyQueryableTaxonomies;
    private final Set<String> registeredTaxonomies;
    private final Set<String> publiclyQueryableTypes;

    private PublicQueryVars(List<PostTypeDefinition> types, List<TaxonomyDefinition> taxonomies) {
        Set<String> all = new LinkedHashSet<>(FIXED);
        all.addAll(COMPONENTS);
        Set<String> fromTaxonomies = new LinkedHashSet<>();
        Set<String> queryableTaxonomies = new LinkedHashSet<>();
        Set<String> registered = new LinkedHashSet<>();
        for (TaxonomyDefinition taxonomy : taxonomies) {
            registered.add(taxonomy.key());
            if (taxonomy.publiclyQueryable()) {
                queryableTaxonomies.add(taxonomy.key());
            }
            taxonomy.queryVar().ifPresent(var -> {
                fromTaxonomies.add(var);
                if (taxonomy.publiclyQueryable()) {
                    all.add(var);
                }
            });
        }
        Map<String, PostTypeDefinition> byVar = new LinkedHashMap<>();
        Set<String> queryableTypes = new LinkedHashSet<>();
        for (PostTypeDefinition type : types) {
            if (type.publiclyQueryable()) {
                queryableTypes.add(type.key());
            }
            if (PostPermissions.typeViewable(type)) {
                type.queryVar().ifPresent(var -> {
                    all.add(var);
                    byVar.putIfAbsent(var, type);
                });
            }
        }
        this.names = List.copyOf(all);
        this.nameSet = Collections.unmodifiableSet(all);
        this.typesByVar = Collections.unmodifiableMap(byVar);
        this.taxonomyVars = Collections.unmodifiableSet(fromTaxonomies);
        this.publiclyQueryableTaxonomies = Collections.unmodifiableSet(queryableTaxonomies);
        this.registeredTaxonomies = Collections.unmodifiableSet(registered);
        this.publiclyQueryableTypes = Collections.unmodifiableSet(queryableTypes);
    }

    public static PublicQueryVars of(List<PostTypeDefinition> types, List<TaxonomyDefinition> taxonomies) {
        return new PublicQueryVars(
                new ArrayList<>(Objects.requireNonNull(types, "types")),
                new ArrayList<>(Objects.requireNonNull(taxonomies, "taxonomies")));
    }

    /** The public names in WordPress's order. */
    public List<String> names() {
        return names;
    }

    public boolean contains(String name) {
        return nameSet.contains(name);
    }

    /** The viewable post type whose query variable is {@code name}. */
    public Optional<PostTypeDefinition> postType(String name) {
        return Optional.ofNullable(typesByVar.get(name));
    }

    /** The query variables of every registered taxonomy that has one (a space in their values reads as a plus). */
    public Set<String> taxonomyVars() {
        return taxonomyVars;
    }

    public boolean registeredTaxonomy(String key) {
        return registeredTaxonomies.contains(key);
    }

    public boolean publiclyQueryableTaxonomy(String key) {
        return publiclyQueryableTaxonomies.contains(key);
    }

    public boolean publiclyQueryableType(String key) {
        return publiclyQueryableTypes.contains(key);
    }
}
