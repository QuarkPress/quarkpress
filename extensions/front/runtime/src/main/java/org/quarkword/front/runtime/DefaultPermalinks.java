package org.quarkword.front.runtime;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkword.core.PostId;
import org.quarkword.core.PostStatusDefinition;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.core.TermId;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.Permalinks;
import org.quarkword.posts.PostPermissions;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostStatuses;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.runtime.Hierarchy;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;
import org.quarkword.posts.runtime.PostRules;
import org.quarkword.posts.runtime.Slugs;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfiles;
import org.quarkword.taxonomy.Taxonomies;
import org.quarkword.taxonomy.TermSnapshot;
import org.quarkword.taxonomy.Terms;

/**
 * WordPress's link template over the current site's options and registries. A post's link is plain when its status
 * is not viewable, unless it is private and the current visitor may read it (or protected and the editor's template
 * is wanted); an attachment takes its parent's status. Parents and the pages of the reading settings are read as
 * stored, without capability checks, as WordPress reads them while building a link. The structure is read once per
 * request.
 */
@RequestScoped
public class DefaultPermalinks implements Permalinks {

    private static final String POST_FORMAT_PREFIX = "post-format-";
    private static final String INDEX = "index.php";
    private static final String PAGINATION = "page/";

    @Inject
    SiteContext site;

    @Inject
    FrontUrls urls;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    PostPermissions permissions;

    @Inject
    PostRepository posts;

    @Inject
    Terms terms;

    @Inject
    Taxonomies taxonomies;

    @Inject
    UserProfiles profiles;

    private Structure structure;

    Structure structure() {
        if (structure == null) {
            structure = Structure.of(option("permalink_structure"), option("category_base"), option("tag_base"));
        }
        return structure;
    }

    @Override
    public boolean plain() {
        return structure().plain();
    }

    @Override
    public boolean trailingSlashes() {
        return structure().trailingSlashes();
    }

    @Override
    public String post(PostSnapshot post) {
        return link(post, false);
    }

    @Override
    public String postSample(PostSnapshot post) {
        return plain() ? link(post, false) : link(post, true);
    }

    private String link(PostSnapshot post, boolean sample) {
        return switch (post.type()) {
            case "page" -> page(post, sample, true);
            case "attachment" -> attachment(post, sample);
            default -> {
                Optional<PostTypeDefinition> custom = types.find(post.type()).filter(type -> !type.builtIn());
                yield custom.isPresent() ? custom(post, custom.get(), sample) : postLink(post, sample);
            }
        };
    }

    /** The post structure for {@code post} and the other built-in types. */
    private String postLink(PostSnapshot post, boolean sample) {
        if (plain() || plainForced(post, post.status(), sample)) {
            return urls.home("?p=" + post.id().value());
        }
        return pretty(TagValues.substitute(structure().value(), values(post), sample));
    }

    private String page(PostSnapshot page, boolean sample, boolean frontPageAsHome) {
        long id = page.id().value();
        if (frontPageAsHome && option("show_on_front").equals("page") && number("page_on_front") == id) {
            return urls.home("/");
        }
        if (!plain() && (sample || !plainForced(page, page.status(), false))) {
            String uri = sample ? parentPath(page) + "%pagename%" : uri(page);
            return pretty(structure().root() + uri);
        }
        return urls.home("?page_id=" + id);
    }

    private String attachment(PostSnapshot attachment, boolean sample) {
        SiteId siteId = site.siteId();
        Optional<PostSnapshot> parent = attachment
                .parent()
                .filter(id -> !id.equals(attachment.id()))
                .flatMap(id -> posts.findPost(siteId, id))
                .map(PostEntity::toSnapshot);
        String status = attachmentStatus(attachment, parent);
        if (!plain() && !plainForced(attachment, status, false)) {
            if (attachment.parent().isEmpty()) {
                return pretty(attachment.slug());
            }
            boolean parentViewable = parent.flatMap(found -> types.find(found.type()))
                    .filter(PostPermissions::typeViewable)
                    .isPresent();
            if (parentViewable) {
                PostSnapshot found = parent.get();
                String parentLink = found.type().equals("page") ? page(found, false, false) : link(found, false);
                if (!parentLink.contains("?")) {
                    String name = sample ? "%postname%" : attachmentSegment(attachment.slug());
                    return structure().trailing(withoutTrailingSlashes(parentLink) + "/" + name);
                }
            }
        }
        return urls.home("?attachment_id=" + attachment.id().value());
    }

    /**
     * WordPress's {@code get_post_status} of an attachment: {@code inherit} is the parent's status ({@code publish}
     * without a parent); a status of its own is kept when private, trashed or auto-draft and reads as {@code publish}
     * otherwise.
     */
    private static String attachmentStatus(PostSnapshot attachment, Optional<PostSnapshot> parent) {
        String own = attachment.status();
        if (own.equals("inherit")) {
            return parent.map(PostSnapshot::status).orElse("publish");
        }
        return own.equals("private") || own.equals("trash") || own.equals("auto-draft") ? own : "publish";
    }

    private String attachmentSegment(String slug) {
        return phpNumeric(slug) || structure().value().contains("%category%") ? "attachment/" + slug : slug;
    }

    private String custom(PostSnapshot post, PostTypeDefinition type, boolean sample) {
        boolean forced = plainForced(post, post.status(), false);
        Optional<String> permastruct = structure().postTypeStructure(type);
        if (permastruct.isPresent() && (!forced || sample)) {
            String slug;
            if (sample) {
                slug = type.hierarchical() ? parentPath(post) + "%pagename%" : "%pagename%";
            } else {
                slug = type.hierarchical() ? uri(post) : post.slug();
            }
            return pretty(permastruct.get().replace("%" + type.key() + "%", slug));
        }
        if (type.queryVar().isPresent() && !forced) {
            return urls.home("?" + type.queryVar().get() + "=" + post.slug());
        }
        return urls.home("?post_type=" + type.key() + "&p=" + post.id().value());
    }

    /**
     * WordPress's {@code wp_force_plain_post_permalink} for {@code status}: a missing status or type forces a plain
     * link; a viewable status never does; a private one does unless the visitor may read the post; a protected one
     * does unless the editor's template is wanted.
     */
    private boolean plainForced(PostSnapshot post, String status, boolean sample) {
        Optional<PostStatusDefinition> definition = statuses.find(status);
        if (definition.isEmpty() || types.find(post.type()).isEmpty()) {
            return true;
        }
        PostStatusDefinition found = definition.get();
        if (PostPermissions.statusViewable(found)) {
            return false;
        }
        if (found.privateStatus() && permissions.can("read_post", post)) {
            return false;
        }
        return !(found.protectedStatus() && sample);
    }

    private Map<String, String> values(PostSnapshot post) {
        Map<String, String> values = new LinkedHashMap<>(TagValues.date(post.localDate(site.zone())));
        values.put("postname", post.slug());
        values.put("pagename", post.slug());
        values.put("post_id", Long.toString(post.id().value()));
        String permalink = structure().value();
        if (permalink.contains("%category%")) {
            values.put("category", categoryPath(post));
        }
        if (permalink.contains("%author%")) {
            values.put(
                    "author",
                    post.author()
                            .flatMap(profiles::find)
                            .map(UserProfile::nicename)
                            .orElse(""));
        }
        return values;
    }

    /** The lowest-id category of the post with its ancestors, else the default category's bare slug, else nothing. */
    private String categoryPath(PostSnapshot post) {
        Optional<TermSnapshot> category = terms.postTerms(post.id(), "category").stream()
                .min(Comparator.comparingLong(term -> term.id().value()));
        if (category.isPresent()) {
            return hierarchicalSlug(category.get(), "category");
        }
        long fallback = number("default_category");
        Optional<TermSnapshot> defaultCategory =
                fallback > 0 ? terms.find(TermId.of(fallback), "category") : Optional.empty();
        return defaultCategory.map(TermSnapshot::slug).orElse("");
    }

    /** The slugs of the term's ancestors, farthest first, then its own, joined with slashes. */
    private String hierarchicalSlug(TermSnapshot term, String taxonomy) {
        List<TermId> ancestors = terms.ancestors(term.id(), taxonomy);
        List<String> slugs = new ArrayList<>();
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            terms.find(ancestors.get(i), taxonomy).ifPresent(ancestor -> slugs.add(ancestor.slug()));
        }
        slugs.add(term.slug());
        return String.join("/", slugs);
    }

    /** WordPress's {@code get_page_uri}: the ancestors' slugs, farthest first, then the post's own. */
    private String uri(PostSnapshot post) {
        String parents = parentPath(post);
        return parents + post.slug();
    }

    /** The ancestors' slugs, farthest first, each followed by a slash; empty for a top-level post. */
    private String parentPath(PostSnapshot post) {
        SiteId siteId = site.siteId();
        List<PostId> ancestors = Hierarchy.ancestors(
                post.id(),
                post.parent(),
                id -> posts.findPost(siteId, id)
                        .flatMap(row -> Optional.ofNullable(row.parentId))
                        .map(PostId::of));
        StringBuilder path = new StringBuilder();
        for (PostId ancestor : ancestors) {
            Optional<PostEntity> row = posts.findPost(siteId, ancestor);
            if (row.isPresent() && !PostRules.phpEmpty(row.get().slug)) {
                path.insert(0, row.get().slug + "/");
            }
        }
        return path.toString();
    }

    @Override
    public String term(String taxonomy, TermSnapshot term) {
        Optional<TaxonomyDefinition> definition = taxonomies.find(taxonomy);
        String slug = formatSlug(taxonomy, term.slug());
        Optional<String> permastruct = definition.flatMap(structure()::taxonomyStructure);
        if (permastruct.isEmpty()) {
            return plainTerm(taxonomy, term.id().value(), slug);
        }
        boolean hierarchical = definition
                .flatMap(TaxonomyDefinition::rewrite)
                .map(TaxonomyDefinition.Rewrite::hierarchical)
                .orElse(false);
        String path = hierarchical ? hierarchicalSlug(term, taxonomy) : slug;
        return pretty(permastruct.get().replace("%" + taxonomy + "%", path));
    }

    /** The plain-mode term link: the category's id, the taxonomy's query variable, or taxonomy and term. */
    String plainTerm(String taxonomy, long id, String slug) {
        if (taxonomy.equals("category")) {
            return urls.home("?cat=" + id);
        }
        Optional<String> queryVar = taxonomies.find(taxonomy).flatMap(TaxonomyDefinition::queryVar);
        String value = formatSlug(taxonomy, slug);
        return queryVar.isPresent()
                ? urls.home("?" + queryVar.get() + "=" + value)
                : urls.home("?taxonomy=" + taxonomy + "&term=" + value);
    }

    /** A post format term's slug without its {@code post-format-} prefix, as the format links name it. */
    private static String formatSlug(String taxonomy, String slug) {
        return taxonomy.equals("post_format") && slug.startsWith(POST_FORMAT_PREFIX)
                ? slug.substring(POST_FORMAT_PREFIX.length())
                : slug;
    }

    @Override
    public String author(UserProfile author) {
        return author(author.id().value(), author.nicename());
    }

    /** The author archive of the user {@code id} with {@code nicename} (empty for a user without profile). */
    String author(long id, String nicename) {
        return structure()
                .authorStructure()
                .map(permastruct -> pretty(permastruct.replace("%author%", nicename)))
                .orElseGet(() -> urls.home("?author=" + id));
    }

    @Override
    public String year(int year) {
        return date(structure().yearStructure(), year, 0, 0);
    }

    @Override
    public String month(int year, int month) {
        return date(structure().monthStructure(), year, month, 0);
    }

    @Override
    public String day(int year, int month, int day) {
        return date(structure().dateStructure(), year, month, day);
    }

    private String date(Optional<String> permastruct, int year, int month, int day) {
        if (permastruct.isEmpty()) {
            StringBuilder value = new StringBuilder("?m=").append(String.format(Locale.ROOT, "%04d", year));
            if (month > 0) {
                value.append(String.format(Locale.ROOT, "%02d", month));
                if (day > 0) {
                    value.append(String.format(Locale.ROOT, "%02d", day));
                }
            }
            return urls.home(value.toString());
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("year", String.format(Locale.ROOT, "%04d", year));
        values.put("monthnum", String.format(Locale.ROOT, "%02d", month));
        values.put("day", String.format(Locale.ROOT, "%02d", day));
        return pretty(TagValues.substitute(permastruct.get(), values, false));
    }

    @Override
    public String search(String query) {
        String encoded = Slugs.phpUrlencode(query);
        return structure()
                .searchStructure()
                .map(permastruct -> pretty(permastruct.replace("%search%", encoded.replace("%2F", "/"))))
                .orElseGet(() -> urls.home("?s=" + encoded));
    }

    @Override
    public Optional<String> postTypeArchive(String type) {
        if (type.equals("post")) {
            long postsPage = number("page_for_posts");
            if (option("show_on_front").equals("page") && postsPage > 0) {
                return posts.findPost(site.siteId(), PostId.of(postsPage))
                        .map(PostEntity::toSnapshot)
                        .map(this::post);
            }
            return Optional.of(urls.home());
        }
        Optional<PostTypeDefinition> definition = types.find(type).filter(PostTypeDefinition::hasArchive);
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        if (!plain() && definition.get().rewrite().isPresent()) {
            return structure().postTypeArchive(definition.get()).map(this::pretty);
        }
        return Optional.of(urls.home("?post_type=" + definition.get().key()));
    }

    @Override
    public String postFormat(String format) {
        return structure()
                .postFormatStructure()
                .map(permastruct -> pretty(permastruct.replace("%post_format%", format)))
                .orElseGet(() -> urls.home("?post_format=" + format));
    }

    @Override
    public String paged(String requestPathAndQuery, int n) {
        String request = withoutLeadingSlashes(withoutHomePath(withoutPagedArgument(requestPathAndQuery)));
        if (plain()) {
            String result = urls.home("/") + request;
            return n > 1 ? withQueryArgument(result, "paged=" + n) : result;
        }
        int queryStart = request.indexOf('?');
        String query = queryStart < 0 ? "" : request.substring(queryStart);
        String path = queryStart < 0 ? request : request.substring(0, queryStart);
        path = withoutTrailingPaging(path);
        if (path.regionMatches(true, 0, INDEX, 0, INDEX.length())) {
            path = path.substring(INDEX.length());
        }
        path = withoutTrailingSlashes(withoutLeadingSlashes(path));
        StringBuilder url = new StringBuilder(urls.home());
        if (structure().pathinfo() && (n > 1 || !path.isEmpty())) {
            url.append('/').append(INDEX);
        }
        // a path of exactly "0" is dropped, as PHP's array_filter drops it
        if (!path.isEmpty() && !path.equals("0")) {
            url.append('/').append(path);
        }
        if (n > 1) {
            url.append('/').append(PAGINATION).append(n);
        }
        return structure().trailing(url.toString()) + query;
    }

    /** The request without the path of the home URL at its start, compared ignoring case. */
    private String withoutHomePath(String request) {
        String homePath = urls.homePath();
        if (homePath.isEmpty()) {
            return request;
        }
        String prefix = "/" + homePath;
        return request.regionMatches(true, 0, prefix, 0, prefix.length())
                ? request.substring(prefix.length())
                : request;
    }

    /** WordPress's {@code remove_query_arg('paged')}: the query string without that argument, the fragment kept. */
    private static String withoutPagedArgument(String request) {
        int hash = request.indexOf('#');
        String fragment = hash < 0 ? "" : request.substring(hash);
        String url = hash < 0 ? request : request.substring(0, hash);
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url + fragment;
        }
        List<String> kept = new ArrayList<>();
        String query = url.substring(queryStart + 1);
        int start = 0;
        while (start <= query.length()) {
            int end = query.indexOf('&', start);
            String argument = end < 0 ? query.substring(start) : query.substring(start, end);
            int equals = argument.indexOf('=');
            String key = equals < 0 ? argument : argument.substring(0, equals);
            if (!key.equals("paged")) {
                kept.add(argument);
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        String path = url.substring(0, queryStart);
        return kept.isEmpty() ? path + fragment : path + "?" + String.join("&", kept) + fragment;
    }

    /** WordPress's {@code add_query_arg}: the argument appended to the query string, before any fragment. */
    private static String withQueryArgument(String url, String argument) {
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash);
        String base = hash < 0 ? url : url.substring(0, hash);
        if (base.indexOf('?') < 0) {
            return base + "?" + argument + fragment;
        }
        return (base.endsWith("?") || base.endsWith("&") ? base : base + "&") + argument + fragment;
    }

    /** The path without a {@code page/N} and at most one slash at its end, wherever {@code page/} starts. */
    private static String withoutTrailingPaging(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int digits = trimmed.length();
        while (digits > 0 && isAsciiDigit(trimmed.charAt(digits - 1))) {
            digits--;
        }
        if (digits == trimmed.length() || digits < PAGINATION.length()) {
            return path;
        }
        return trimmed.startsWith(PAGINATION, digits - PAGINATION.length())
                ? trimmed.substring(0, digits - PAGINATION.length())
                : path;
    }

    /** {@code home_url(path)} with the trailing-slash rule, as every pretty link is composed. */
    private String pretty(String path) {
        return structure().trailing(urls.home(path));
    }

    private String option(String name) {
        return site.options().string(name, "");
    }

    private long number(String name) {
        return site.options().number(name, 0);
    }

    /**
     * PHP's {@code is_numeric} for a slug: digits with an optional fraction and an optional exponent ({@code 1e5},
     * {@code 1e-5}); nothing else a sanitized slug can hold is numeric.
     */
    static boolean phpNumeric(String text) {
        int i = 0;
        int length = text.length();
        if (i < length && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            i++;
        }
        int wholeDigits = digitsFrom(text, i);
        i += wholeDigits;
        int fractionDigits = 0;
        if (i < length && text.charAt(i) == '.') {
            fractionDigits = digitsFrom(text, i + 1);
            i += 1 + fractionDigits;
        }
        if (wholeDigits + fractionDigits == 0) {
            return false;
        }
        if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            if (i < length && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
                i++;
            }
            int exponentDigits = digitsFrom(text, i);
            if (exponentDigits == 0) {
                return false;
            }
            i += exponentDigits;
        }
        return i == length;
    }

    private static int digitsFrom(String text, int start) {
        int i = start;
        while (i < text.length() && isAsciiDigit(text.charAt(i))) {
            i++;
        }
        return i - start;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String withoutLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    private static String withoutTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
