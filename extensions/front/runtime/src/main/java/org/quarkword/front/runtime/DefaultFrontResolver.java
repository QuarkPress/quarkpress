package org.quarkword.front.runtime;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.quarkword.core.PostId;
import org.quarkword.core.QuarkWordClock;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.FrontResolver;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.QueryVars;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PostPage;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.Posts;
import org.quarkword.posts.PreviewTokens;
import org.quarkword.posts.TaxClause;
import org.quarkword.posts.TaxQuery;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;
import org.quarkword.posts.runtime.Slugs;
import org.quarkword.security.UserProfiles;
import org.quarkword.taxonomy.Taxonomies;
import org.quarkword.taxonomy.TermField;
import org.quarkword.taxonomy.TermSnapshot;
import org.quarkword.taxonomy.Terms;

/**
 * The front controller's resolution of a request of the current site, in WordPress's order: QuarkWord's request
 * bounds (else 404 before anything is read), the preview pair ({@code preview_id} and {@code preview_nonce} both given
 * must be the viewer's valid token of that post, else 403 before any query), the request parser, a plain-permalink
 * {@code error} of 403, 500, 502 or 503 (answered with that status before the query), the flags, the reserved
 * variables (404 with the flags as parsed), a not-found parse (answered 200 with the not-found flags, as WordPress
 * sends its 404 template then), the main query run as the viewer, the visibility gate, the queried object and the 404
 * decision; last, as WordPress sends its headers after the 404 decision, a status WordPress knows that the {@code
 * error} variable names (410, say) replaces the decided one, the page being resolved as without it.
 */
@RequestScoped
public class DefaultFrontResolver implements FrontResolver {

    /** Variables whose pages the front end does not serve: their requests answer 404. */
    static final Set<String> RESERVED = Set.of(
            "feed",
            "embed",
            "tb",
            "robots",
            "favicon",
            "sitemap",
            "cpage",
            "rest_route",
            "register",
            "signup",
            "activate");

    /** The {@code error} statuses after which WordPress sends nothing more. */
    static final Set<Integer> ENDING = Set.of(403, 500, 502, 503);

    /** The statuses WordPress has a description for, the only ones it sends for an {@code error} variable. */
    static final Set<Integer> KNOWN = Set.of(
            100, 101, 102, 103, 200, 201, 202, 203, 204, 205, 206, 207, 226, 300, 301, 302, 303, 304, 305, 306, 307,
            308, 400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418, 421,
            422, 423, 424, 425, 426, 428, 429, 431, 451, 500, 501, 502, 503, 504, 505, 506, 507, 510, 511);

    @Inject
    SiteContext site;

    @Inject
    FrontUrls urls;

    @Inject
    PostTypes postTypes;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PagePaths pages;

    @Inject
    MainQuery mainQuery;

    @Inject
    Posts posts;

    @Inject
    PostRepository repository;

    @Inject
    SingularGate gate;

    @Inject
    NotFoundRule notFound;

    @Inject
    PreviewTokens tokens;

    @Inject
    Terms terms;

    @Inject
    UserProfiles profiles;

    @Inject
    QuarkWordClock clock;

    @Inject
    CurrentIdentityAssociation identities;

    @Override
    public FrontRequest resolve(String rawPath, Map<String, List<String>> query, Viewer viewer) {
        Objects.requireNonNull(rawPath, "rawPath");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(viewer, "viewer");
        Instant now = clock.now();
        if (!RequestLimits.check(rawPath, null) || parameters(query) > RequestLimits.MAX_QUERY_PARAMETERS) {
            return FrontRequest.answered(QueryVars.empty(), FrontFlags.notFound(), 404, now);
        }
        return as(viewer.identity(), () -> resolveAs(rawPath, query, viewer, now));
    }

    private FrontRequest resolveAs(String rawPath, Map<String, List<String>> query, Viewer viewer, Instant now) {
        boolean armed = false;
        if (hasPair(query)) {
            armed = validPair(query, viewer, tokens);
            if (!armed) {
                return FrontRequest.answered(QueryVars.empty(), FrontFlags.none(), 403, now)
                        .withMessage(FrontRequest.CANNOT_PREVIEW);
            }
        }
        SiteOptions options = site.options();
        Structure structure = Structure.of(
                options.string("permalink_structure", ""),
                options.string("category_base", ""),
                options.string("tag_base", ""));
        RequestParser.Registries registries = new RequestParser.Registries(postTypes.all(), taxonomies.all());
        String homePath = urls.homePath();
        boolean staticFrontPage =
                options.string("show_on_front", "posts").equals("page") && options.number("page_on_front", 0) != 0;
        RewriteRules rules = RewriteRules.of(
                structure, registries.postTypes(), registries.taxonomies(), !homePath.isEmpty(), staticFrontPage);
        QueryVars vars = RequestParser.parse(rawPath, query, structure, rules, registries, pages, homePath);

        Optional<Integer> error = error(vars);
        if (error.isPresent() && ENDING.contains(error.get())) {
            return FrontRequest.answered(vars, FrontFlags.none(), error.get(), now);
        }
        FlagParser.Parsed parsed = FlagParser.parse(vars, options, registries, pages, structure);
        for (String reserved : RESERVED) {
            if (!vars.get(reserved).orElse("").isEmpty()) {
                return FrontRequest.answered(vars, parsed.flags(), 404, now)
                        .withQueryVars(afterQuery(parsed, options))
                        .withQueriedObject(notFound.readable(parsed.queriedFromPath(), viewer));
            }
        }
        if (parsed.flags().is404()) {
            int status = error.filter(KNOWN::contains).orElse(200);
            return FrontRequest.answered(vars, parsed.flags(), status, now)
                    .withQueryVars(afterQuery(parsed, options))
                    .withQueriedObject(notFound.readable(parsed.queriedFromPath(), viewer));
        }

        PostQuery main = mainQuery.build(parsed, options, registries, viewer).orElseThrow();
        PostPage found = posts.query(main);
        QueryVars normalized = parsed.normalized();
        FrontRequest draft = new FrontRequest(
                vars,
                afterQuery(parsed, options),
                parsed.flags(),
                parsed.queriedFromPath(),
                Optional.of(main),
                found,
                toInt(MainQuery.number(normalized, "page")),
                toInt(MainQuery.number(normalized, "paged")),
                200,
                Optional.empty(),
                armed,
                Optional.empty(),
                now);
        FrontRequest gated = gate.apply(draft, viewer, armed, query);
        FrontRequest withObject = gated.withQueriedObject(queried(gated, parsed, registries, options));
        FrontRequest decided = notFound.apply(withObject, viewer);
        // WordPress sends the headers last: a status the error variable names replaces the 404 decision's
        return error.filter(KNOWN::contains).map(decided::withStatus).orElse(decided);
    }

    // ---- the queried object ---------------------------------------------------------------------------------------

    /**
     * WordPress's queried object, in its order: the term of a category, tag or taxonomy archive (the first term of the
     * first clause asking for posts with terms of that taxonomy, or of any taxonomy for a custom one); the post type
     * of a post type archive; the posts page; the shown post of a singular request, else the page its path named; the
     * author of an author archive.
     */
    private Optional<QueriedObject> queried(
            FrontRequest request, FlagParser.Parsed parsed, RequestParser.Registries registries, SiteOptions options) {
        FrontFlags flags = request.flags();
        if (flags.category() || flags.tag() || flags.tax()) {
            String taxonomy = flags.category() ? "category" : flags.tag() ? "post_tag" : null;
            return firstTerm(parsed.taxQuery(), taxonomy).map(QueriedObject.Term::new);
        }
        if (flags.postTypeArchive()) {
            String type = parsed.normalized().get("post_type").orElse("");
            return registries.postTypes().stream()
                    .filter(definition -> definition.key().equals(type))
                    .findFirst()
                    .map(QueriedObject.PostTypeArchive::new);
        }
        if (flags.postsPage()) {
            if (parsed.queriedFromPath().isPresent()) {
                return parsed.queriedFromPath();
            }
            long pageForPosts = options.number("page_for_posts", 0);
            return pageForPosts <= 0
                    ? Optional.empty()
                    : repository
                            .findPost(site.siteId(), PostId.of(pageForPosts))
                            .map(PostEntity::toSnapshot)
                            .map(QueriedObject.PostsPage::new);
        }
        if (flags.singular()) {
            Optional<PostSnapshot> shown = request.shownPost();
            return shown.isPresent() ? shown.map(QueriedObject.Post::new) : parsed.queriedFromPath();
        }
        if (flags.author()) {
            QueryVars vars = parsed.normalized();
            if (!vars.get("author_name").orElse("").isEmpty()) {
                return mainQuery.authorByName(vars).map(QueriedObject.Author::new);
            }
            long author = FlagParser.phpInt(vars.get("author").orElse(""));
            return author > 0 ? profiles.find(UserId.of(author)).map(QueriedObject.Author::new) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The first term of the first clause asking for posts with terms of {@code taxonomy} (any when null). */
    private Optional<TermSnapshot> firstTerm(TaxQuery taxQuery, String taxonomy) {
        for (TaxClause clause : taxQuery.clauses()) {
            Optional<Optional<TermSnapshot>> term = firstTerm(clause, taxonomy);
            if (term.isPresent()) {
                return term.get();
            }
        }
        return Optional.empty();
    }

    /** Empty when the clause does not count; else the lookup of its first term. */
    private Optional<Optional<TermSnapshot>> firstTerm(TaxClause clause, String taxonomy) {
        if (!clause.positive()) {
            return Optional.empty();
        }
        switch (clause) {
            case TaxClause.Terms ids -> {
                if ((taxonomy != null && !ids.taxonomy().equals(taxonomy))
                        || ids.termIds().isEmpty()) {
                    return Optional.empty();
                }
                long id = ids.termIds().get(0);
                return Optional.of(id > 0 ? terms.find(TermId.of(id), ids.taxonomy()) : Optional.empty());
            }
            case TaxClause.Slugs slugs -> {
                if ((taxonomy != null && !slugs.taxonomy().equals(taxonomy))
                        || slugs.slugs().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(terms.findBy(
                        slugs.taxonomy(), TermField.SLUG, slugs.slugs().get(0)));
            }
            case TaxClause.Group group -> {
                for (TaxClause inner : group.clauses()) {
                    Optional<Optional<TermSnapshot>> term = firstTerm(inner, taxonomy);
                    if (term.isPresent()) {
                        return term;
                    }
                }
                return Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    // ---- the main query's variables -------------------------------------------------------------------------------

    /**
     * The parsed variables as WordPress's query leaves them once it ran: a {@code name} sanitised for the lookup; a
     * {@code pagename} (unless it names the posts page) and an {@code attachment} reduced to their last segment,
     * sanitised and copied into {@code name}, a {@code pagename} that is an attachment's path also setting
     * {@code post_type} and {@code attachment_id}; {@code attachment_id}, then {@code page_id} (unless it is the posts
     * page), copied into {@code p}.
     */
    static QueryVars afterQuery(FlagParser.Parsed parsed, SiteOptions options) {
        QueryVars vars = parsed.normalized();
        boolean postsPageShown = options.string("show_on_front", "posts").equals("page");
        long pageForPosts = options.number("page_for_posts", 0);
        String name = vars.get("name").orElse("");
        String pagename = vars.get("pagename").orElse("");
        String attachment = vars.get("attachment").orElse("");
        if (!name.isEmpty()) {
            vars = vars.with("name", Slugs.sanitizeTitleForQuery(name));
        } else if (!pagename.isEmpty()) {
            Optional<QueriedObject> page = parsed.queriedFromPath();
            long requested = page.map(QueriedObject::id).orElse(0L);
            if (!postsPageShown || pageForPosts == 0 || requested != pageForPosts) {
                String slug = Slugs.sanitizeTitleForQuery(MainQuery.basename(pagename));
                vars = vars.with("pagename", slug).with("name", slug);
                if (page.orElse(null) instanceof QueriedObject.Post found
                        && found.post().type().equals("attachment")) {
                    vars = vars.with("post_type", "attachment").with("attachment_id", Long.toString(requested));
                }
            }
        } else if (!attachment.isEmpty()) {
            String slug = Slugs.sanitizeTitleForQuery(MainQuery.basename(attachment));
            vars = vars.with("attachment", slug).with("name", slug);
        }
        long attachmentId = MainQuery.number(vars, "attachment_id");
        if (attachmentId != 0) {
            vars = vars.with("p", Long.toString(attachmentId));
        }
        long pageId = MainQuery.number(vars, "page_id");
        if (pageId != 0 && (!postsPageShown || pageForPosts != pageId)) {
            vars = vars.with("p", Long.toString(pageId));
        }
        return vars;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Whether the query carries the preview pair ({@code preview_id} and {@code preview_nonce}, both given). */
    static boolean hasPair(Map<String, List<String>> query) {
        return query.containsKey("preview_id") && query.containsKey("preview_nonce");
    }

    /** Whether the query's preview pair is the viewer's valid token of the post it names; run as the viewer. */
    static boolean validPair(Map<String, List<String>> query, Viewer viewer, PreviewTokens tokens) {
        long id = FlagParser.phpInt(last(query, "preview_id"));
        return viewer.user().isPresent()
                && id > 0
                && tokens.verify(
                        PostId.of(id),
                        last(query, "preview_nonce"),
                        viewer.user().get());
    }

    /** The {@code error} variable's status, when it is not empty as PHP reads it. */
    private static Optional<Integer> error(QueryVars vars) {
        String error = vars.get("error").orElse("");
        if (error.isEmpty() || error.equals("0")) {
            return Optional.empty();
        }
        return Optional.of(toInt(FlagParser.phpInt(error)));
    }

    private static int parameters(Map<String, List<String>> query) {
        long count = 0;
        for (List<String> values : query.values()) {
            count += Math.max(1, values.size());
        }
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    private static String last(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? "" : values.get(values.size() - 1);
    }

    private static int toInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(value, Integer.MAX_VALUE));
    }

    private <T> T as(SecurityIdentity identity, Supplier<T> work) {
        SecurityIdentity previous = identities.getIdentity();
        identities.setIdentity(identity);
        try {
            return work.get();
        } finally {
            identities.setIdentity(previous);
        }
    }
}
