package org.quarkword.front.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.quarkword.core.PostId;
import org.quarkword.core.PostStatusDefinition;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.QuarkWordClock;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.Permalinks;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.QueryVars;
import org.quarkword.front.Viewer;
import org.quarkword.persistence.runtime.TenantIds;
import org.quarkword.posts.PostLookups;
import org.quarkword.posts.PostPermissions;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostStatuses;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.TaxClause;
import org.quarkword.posts.TaxQuery;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;
import org.quarkword.posts.runtime.Slugs;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfiles;
import org.quarkword.taxonomy.Taxonomies;
import org.quarkword.taxonomy.TermField;
import org.quarkword.taxonomy.TermSnapshot;
import org.quarkword.taxonomy.Terms;

/**
 * WordPress's canonical redirect ({@code redirect_canonical}) of a resolved front-end GET or HEAD request: the URL the
 * request should have used, as a 301, or nothing.
 *
 * <p>It does nothing for a search, a preview (a preview of a published post asked for by {@code p} without a valid
 * preview pair is not one), and for the requests the front end answers before its pages (the {@code error} statuses
 * that end a request, and the variables of feeds, embeds, trackbacks, robots, favicon, sitemaps, comment pages, REST
 * routes and the sign-up pages, which answer 404). Otherwise, in WordPress's order: a not-found request is redirected
 * to the post its {@code p}, {@code page_id} or {@code attachment_id} names, an impossible date to its month or year,
 * a content page the post lacks to the post, or else to the published post of a viewable type whose slug starts with
 * the requested {@code name} (narrowed by its type and date parts); a found request in pretty mode from its plain
 * query-string form to its permalink ({@code p}, {@code name}, {@code page_id}, {@code attachment_id}, the static
 * front page, the posts page, {@code m}, the date parts, a numeric {@code author} with a published post, a category,
 * tag or taxonomy term when every variable came from the query string, and a post under a {@code %category%} structure
 * requested through a category it is not in), then its content page and list page normalised, and an attachment to
 * its file when attachment pages are off. Last, the URL is normalised: the query the rules did not consume re-added,
 * the host of {@code home} for a {@code www.} variant only (any other host is kept), the port of {@code home}, a
 * trailing {@code index.php}, end punctuation and empty {@code p}, {@code page_id}, {@code cat} and {@code tag}
 * arguments removed, the trailing slash of the structure, single slashes. A target derived from a post the viewer may
 * not see is cancelled, and so is one that would itself be redirected again.
 */
@RequestScoped
public class CanonicalRedirects {

    private static final String[] PUNCTUATION = {
        " ",
        "%20",
        "!",
        "%21",
        "\"",
        "%22",
        "'",
        "%27",
        "(",
        "%28",
        ")",
        "%29",
        ",",
        "%2C",
        ".",
        "%2E",
        ";",
        "%3B",
        "{",
        "%7B",
        "}",
        "%7D",
        "%E2%80%9C",
        "%E2%80%9D"
    };
    private static final String[] SINGULAR_ARGS = {"p", "page_id", "attachment_id", "pagename", "name", "post_type"};
    private static final String[] GUESS_ARGS = {
        "page", "feed", "p", "page_id", "attachment_id", "pagename", "name", "post_type"
    };
    private static final String[] CLEARED_ARGS = {"p", "page_id", "cat", "tag"};
    private static final String[] FEED_NAMES = {"feed", "rss2", "rss", "rdf", "atom"};

    @Inject
    SiteContext site;

    @Inject
    FrontUrls urls;

    @Inject
    Permalinks permalinks;

    @Inject
    PostRepository repository;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    PostPermissions permissions;

    @Inject
    PostLookups lookups;

    @Inject
    UserProfiles profiles;

    @Inject
    Terms terms;

    @Inject
    Taxonomies taxonomies;

    @Inject
    Instance<AttachmentFiles> files;

    @Inject
    QuarkWordClock clock;

    @Inject
    CurrentIdentityAssociation identities;

    /** The canonical redirect of {@code request}, sent to {@code url}, as {@code viewer} sees the site. */
    public Optional<Redirect> decide(FrontRequest request, RequestUrl url, Viewer viewer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(viewer, "viewer");
        String method = url.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return Optional.empty();
        }
        if (DefaultFrontResolver.ENDING.contains(request.status())) {
            return Optional.empty();
        }
        for (String reserved : DefaultFrontResolver.RESERVED) {
            if (!request.vars().get(reserved).orElse("").isEmpty()) {
                return Optional.empty();
            }
        }
        return as(viewer.identity(), () -> {
            Run run = new Run(request, url, viewer);
            if (run.flags.search() || run.preview || run.flags.trackback() || run.flags.favicon()) {
                return Optional.empty();
            }
            String requested = PhpUrls.bytes(url.url());
            Optional<String> target = run.once(requested);
            if (target.isEmpty() || run.once(target.get()).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(Redirect.permanent(PhpUrls.text(target.get())));
        });
    }

    /** The parts of a URL the redirect works on; absent parts are empty, an absent port is null. */
    private static final class Parts {
        String scheme = "";
        String host = "";
        Integer port;
        String path = "";
        String query = "";

        static Parts of(PhpUrls.Url url) {
            Parts parts = new Parts();
            parts.scheme = url.scheme == null ? "" : url.scheme;
            parts.host = url.host == null ? "" : url.host;
            parts.port = url.port;
            parts.path = url.path == null ? "" : url.path;
            parts.query = url.query == null ? "" : url.query;
            return parts;
        }
    }

    /** One decision over the request's fixed state; {@link #once} may run twice (the chain check). */
    private final class Run {

        final FrontRequest request;
        final RequestUrl url;
        final Viewer viewer;
        final FrontFlags flags;
        final QueryVars qv;
        final Map<String, Object> get;
        final SiteOptions options;
        final boolean preview;
        final boolean isFrontPage;

        Run(FrontRequest request, RequestUrl url, Viewer viewer) {
            this.request = request;
            this.url = url;
            this.viewer = viewer;
            this.flags = request.flags();
            this.qv = request.queryVars();
            this.get = PhpUrls.parseStr(PhpUrls.bytes(url.query()));
            this.options = site.options();
            // a preview of a published post asked for by p, without a valid preview pair, is not a preview
            boolean previewing = flags.preview();
            long p = number("p");
            if (previewing && p > 0 && !request.previewArmed()) {
                Optional<PostSnapshot> post = post(p);
                if (post.isPresent() && status(post.get()).equals("publish")) {
                    previewing = false;
                }
            }
            this.preview = previewing;
            this.isFrontPage = flags.isFrontPage(options, request.queriedObject());
        }

        // ---- the decision -----------------------------------------------------------------------------------------

        Optional<String> once(String requested) {
            Optional<PhpUrls.Url> parsedOriginal = PhpUrls.parseUrl(requested);
            if (parsedOriginal.isEmpty()) {
                return Optional.empty();
            }
            Parts original = Parts.of(parsedOriginal.get());
            Parts r = Parts.of(parsedOriginal.get());
            String redirectUrl = null;
            PostSnapshot redirectPost = null;

            r.path = withoutTrailingNonBreakingSpaces(r.path);
            if (truthy(qv.get("preview").orElse(""))) {
                r.query = PhpUrls.removeQueryArgs(r.query, "preview");
            }
            long postId = number("p");

            if (flags.singular() && request.posts().posts().isEmpty() && postId > 0) {
                Optional<PostSnapshot> row = post(postId);
                if (row.isPresent()) {
                    if (row.get().type().equals("revision")
                            && row.get().parent().isPresent()) {
                        postId = row.get().parent().get().value();
                    }
                    Optional<PostSnapshot> target = post(postId);
                    redirectUrl = target.map(this::permalink).orElse(null);
                    redirectPost = target.orElse(null);
                    if (redirectUrl != null) {
                        r.query = PhpUrls.removeQueryArgsNotIn(r.query, redirectUrl, SINGULAR_ARGS);
                    }
                }
            }

            boolean pretty = !permalinks.plain();
            if (flags.is404()) {
                long id = Math.max(number("p"), Math.max(number("page_id"), number("attachment_id")));
                Optional<PostSnapshot> named = id > 0 ? post(id) : Optional.empty();
                if (named.isPresent()) {
                    PostSnapshot found = named.get();
                    boolean publicType = types.find(found.type())
                            .map(PostTypeDefinition::publicly)
                            .orElse(false);
                    if (publicType && !found.status().equals("auto-draft")) {
                        redirectUrl = permalink(found);
                        redirectPost = found;
                        r.query = PhpUrls.removeQueryArgsNotIn(r.query, redirectUrl, SINGULAR_ARGS);
                    }
                }
                long year = number("year");
                long month = number("monthnum");
                long day = number("day");
                if (year != 0 && month != 0 && day != 0) {
                    if (!checkdate(month, day, year)) {
                        redirectUrl = monthLink(year, month);
                        r.query = PhpUrls.removeQueryArgsNotIn(r.query, redirectUrl, "year", "monthnum", "day");
                    }
                } else if (year != 0 && month != 0 && month > 12) {
                    redirectUrl = yearLink(year);
                    r.query = PhpUrls.removeQueryArgsNotIn(r.query, redirectUrl, "year", "monthnum");
                }
                if (truthy(qv.get("page").orElse(""))) {
                    long pageOf = 0;
                    if (request.queriedObject().orElse(null) instanceof QueriedObject.Post queried) {
                        pageOf = queried.post().id().value();
                    } else if (!request.posts().posts().isEmpty()) {
                        pageOf = request.posts().posts().get(0).id().value();
                    }
                    if (pageOf != 0) {
                        Optional<PostSnapshot> target = post(pageOf);
                        redirectUrl = target.map(this::permalink).orElse(null);
                        redirectPost = target.orElse(null);
                        r.path = rtrimMask(r.path, Long.toString(number("page")) + "/");
                        r.query = PhpUrls.removeQueryArgs(r.query, "page");
                    }
                }
                if (redirectUrl == null) {
                    redirectUrl = guess().orElse(null);
                    if (redirectUrl != null) {
                        r.query = PhpUrls.removeQueryArgsNotIn(r.query, redirectUrl, GUESS_ARGS);
                    }
                }
            } else if (pretty) {
                Rule rule = prettyRule(r, redirectUrl);
                if (rule != null) {
                    redirectUrl = rule.url;
                    if (rule.post != null) {
                        redirectPost = rule.post;
                    }
                }
                // post paging
                if (flags.singular() && truthy(qv.get("page").orElse(""))) {
                    long page = number("page");
                    if (redirectUrl == null) {
                        Optional<PostSnapshot> queried = queriedPost();
                        redirectUrl = queried.map(this::permalink).orElse(null);
                        redirectPost = queried.orElse(null);
                    }
                    if (page > 1) {
                        String base = PhpUrls.trailingslashit(redirectUrl == null ? "" : redirectUrl);
                        redirectUrl = base + userTrailingSlash(isFrontPage ? "page/" + page : Long.toString(page));
                    }
                    r.query = PhpUrls.removeQueryArgs(r.query, "page");
                }
                // list paging
                long paged = number("paged");
                if (paged != 0) {
                    r.path = withoutPagingSuffixes(r.path);
                    String additional = "";
                    if (paged > 0) {
                        r.query = PhpUrls.removeQueryArgs(r.query, "paged");
                        if (!flags.single() && paged > 1) {
                            additional = userTrailingSlash("page/" + paged);
                        }
                    }
                    r.path = withoutTrailingIndex(r.path, false);
                    r.path = userTrailingSlash(r.path);
                    if (!additional.isEmpty() && usingIndexPermalinks() && !r.path.contains("/index.php/")) {
                        r.path = PhpUrls.trailingslashit(r.path) + "index.php/";
                    }
                    if (!additional.isEmpty()) {
                        r.path = PhpUrls.trailingslashit(r.path) + additional;
                    }
                    redirectUrl = r.scheme + "://" + r.host + r.path;
                }
            }

            // an attachment's file, when attachment pages are off
            boolean attachmentRedirect = false;
            if (flags.attachment() && !options.flag("wp_attachment_pages_enabled", false) && files.isResolvable()) {
                long attachmentId = number("attachment_id");
                Optional<PostSnapshot> attachment = attachmentId > 0 ? post(attachmentId) : currentPost();
                Optional<String> file = attachment.flatMap(found -> fileUrl(found.id()));
                if (!Objects.equals(file.orElse(null), redirectUrl)) {
                    Optional<PostId> parent = attachment.flatMap(PostSnapshot::parent);
                    if (parent.isPresent()) {
                        redirectPost = post(parent.get().value()).orElse(null);
                    }
                    redirectUrl = file.orElse(null);
                }
                attachmentRedirect = true;
            }

            r.query = withoutLeadingQuestionMark(r.query);
            if (redirectUrl != null && !r.query.isEmpty()) {
                Map<String, Object> carried = PhpUrls.parseStr(r.query);
                Optional<PhpUrls.Url> target = PhpUrls.parseUrl(redirectUrl);
                String targetQuery = target.map(parts -> parts.query).orElse(null);
                if (nonEmpty(carried.get("name")) && targetQuery != null && !targetQuery.isEmpty()) {
                    if (!nonEmpty(PhpUrls.parseStr(targetQuery).get("name"))) {
                        carried.remove("name");
                    }
                }
                redirectUrl = PhpUrls.addQueryArgs(PhpUrls.rawurlencodeDeep(carried), redirectUrl);
            }
            if (redirectUrl != null) {
                r = PhpUrls.parseUrl(redirectUrl).map(Parts::of).orElseGet(Parts::new);
            }

            PhpUrls.Url home = PhpUrls.parseUrl(PhpUrls.bytes(urls.home())).orElseGet(PhpUrls.Url::new);
            if (home.host != null && !home.host.isEmpty()) {
                r.host = home.host;
            }
            String homePath = home.path == null || home.path.isEmpty() ? "/" : home.path;
            r.port = home.port != null && home.port != 0 ? home.port : null;

            r.path = withoutTrailingIndex(r.path, true);
            r.path = withoutTrailingPunctuation(r.path);
            if (!r.query.isEmpty()) {
                r.query = withoutQueryPunctuation(r.query);
                r.query = PhpUrls.trim(withoutEmptyArgs(r.query), '&');
                r.query = withRss2(r.query);
                r.query = withoutLeadingQuestionMark(r.query);
            }
            if (!usingIndexPermalinks()) {
                r.path = r.path.replace("/index.php/", "/");
            }
            if (pretty && !attachmentRedirect && !flags.is404() && (!isFrontPage || number("paged") > 1)) {
                r.path = userTrailingSlash(r.path);
            } else if (isFrontPage) {
                r.path = PhpUrls.trailingslashit(r.path);
            }
            if (r.path.contains("//")) {
                r.path = singleSlashes(r.path);
            }
            if (PhpUrls.trailingslashit(r.path).equals(PhpUrls.trailingslashit(homePath))) {
                r.path = PhpUrls.trailingslashit(r.path);
            }
            String originalHost = original.host.toLowerCase(Locale.ROOT);
            String redirectHost = r.host.toLowerCase(Locale.ROOT);
            if (originalHost.equals(redirectHost)
                    || (!("www." + originalHost).equals(redirectHost)
                            && !("www." + redirectHost).equals(originalHost))) {
                r.host = original.host;
            }
            if (!compared(original).equals(compared(r))) {
                StringBuilder built = new StringBuilder(r.scheme).append("://").append(r.host);
                if (r.port != null && r.port != 0) {
                    built.append(':').append(r.port);
                }
                built.append(r.path);
                if (!phpEmpty(r.query)) {
                    built.append('?').append(r.query);
                }
                redirectUrl = built.toString();
            }
            if (redirectUrl == null || redirectUrl.equals(requested)) {
                return Optional.empty();
            }
            String lowered = requested.indexOf('%') >= 0 ? lowerOctets(requested) : requested;
            if (redirectPost != null && !readable(redirectPost)) {
                return Optional.empty();
            }
            if (withoutFragment(redirectUrl).equals(withoutFragment(lowered))) {
                return Optional.empty();
            }
            return Optional.of(redirectUrl);
        }

        /** The plain-to-pretty rules of a found request; null when none applies. */
        private Rule prettyRule(Parts r, String earlier) {
            if (earlier != null) {
                return null;
            }
            Set<String> parsed = request.vars().asMap().keySet();
            if (flags.attachment() && Set.of("attachment", "attachment_id").containsAll(parsed)) {
                if (nonEmpty(get.get("attachment_id"))) {
                    Optional<PostSnapshot> attachment = post(number("attachment_id"));
                    String link = attachment.map(this::permalink).orElse(null);
                    if (link != null) {
                        r.query = PhpUrls.removeQueryArgs(r.query, "attachment_id");
                    }
                    return new Rule(link, attachment.orElse(null));
                }
                Optional<PostSnapshot> current = currentPost();
                return new Rule(current.map(this::permalink).orElse(null), current.orElse(null));
            }
            if (flags.single() && nonEmpty(get.get("p"))) {
                Optional<PostSnapshot> post = post(number("p"));
                String link = post.map(this::permalink).orElse(null);
                if (link != null) {
                    r.query = PhpUrls.removeQueryArgs(r.query, "p", "post_type");
                }
                return new Rule(link, post.orElse(null));
            }
            if (flags.single() && nonEmpty(get.get("name"))) {
                Optional<PostSnapshot> post = queriedPost();
                String link = post.map(this::permalink).orElse(null);
                if (link != null) {
                    r.query = PhpUrls.removeQueryArgs(r.query, "name");
                }
                return new Rule(link, post.orElse(null));
            }
            if (flags.page() && nonEmpty(get.get("page_id"))) {
                Optional<PostSnapshot> page = post(number("page_id"));
                String link = page.map(this::permalink).orElse(null);
                if (link != null) {
                    r.query = PhpUrls.removeQueryArgs(r.query, "page_id");
                }
                return new Rule(link, page.orElse(null));
            }
            boolean staticFront = options.string("show_on_front", "posts").equals("page");
            if (flags.page() && !flags.feed() && staticFront && queriedId() == options.number("page_on_front", 0)) {
                return new Rule(PhpUrls.bytes(urls.home("/")), null);
            }
            long pageForPosts = options.number("page_for_posts", 0);
            if (flags.home() && nonEmpty(get.get("page_id")) && staticFront && number("page_id") == pageForPosts) {
                Optional<PostSnapshot> page = post(pageForPosts);
                String link = page.map(this::permalink).orElse(null);
                if (link != null) {
                    r.query = PhpUrls.removeQueryArgs(r.query, "page_id");
                }
                return new Rule(link, page.orElse(null));
            }
            if (nonEmpty(get.get("m")) && (flags.year() || flags.month() || flags.day())) {
                String m = qv.get("m").orElse("");
                String link = switch (m.length()) {
                    case 4 -> yearLink(FlagParser.phpInt(m));
                    case 6 -> monthLink(FlagParser.phpInt(m.substring(0, 4)), FlagParser.phpInt(m.substring(4, 6)));
                    case 8 ->
                        dayLink(
                                FlagParser.phpInt(m.substring(0, 4)),
                                FlagParser.phpInt(m.substring(4, 6)),
                                FlagParser.phpInt(m.substring(6, 8)));
                    default -> null;
                };
                if (link != null) {
                    r.query = PhpUrls.removeQueryArgs(r.query, "m");
                }
                return new Rule(link, null);
            }
            if (flags.date()) {
                long year = number("year");
                long month = number("monthnum");
                long day = number("day");
                String link = null;
                if (flags.day() && year != 0 && month != 0 && nonEmpty(get.get("day"))) {
                    link = dayLink(year, month, day);
                    r.query = PhpUrls.removeQueryArgs(r.query, "year", "monthnum", "day");
                } else if (flags.month() && year != 0 && nonEmpty(get.get("monthnum"))) {
                    link = monthLink(year, month);
                    r.query = PhpUrls.removeQueryArgs(r.query, "year", "monthnum");
                } else if (flags.year() && nonEmpty(get.get("year"))) {
                    link = yearLink(year);
                    r.query = PhpUrls.removeQueryArgs(r.query, "year");
                }
                return new Rule(link, null);
            }
            if (flags.author() && get.get("author") instanceof String author && PhpUrls.digits(author)) {
                long id = number("author");
                Optional<UserProfile> user = id > 0 ? profiles.find(UserId.of(id)) : Optional.empty();
                if (user.isPresent() && hasPublishedPost(user.get().id())) {
                    r.query = PhpUrls.removeQueryArgs(r.query, "author");
                    return new Rule(PhpUrls.bytes(permalinks.author(user.get())), null);
                }
                return new Rule(null, null);
            }
            if (flags.category() || flags.tag() || flags.tax()) {
                termRule(r);
                return null;
            }
            if (flags.single() && options.string("permalink_structure", "").contains("%category%")) {
                String categoryName = qv.get("category_name").orElse("");
                if (!categoryName.isEmpty()) {
                    Optional<PostSnapshot> post = queriedPost();
                    Optional<TermSnapshot> category = categoryByPath(categoryName);
                    boolean in = category.isPresent()
                            && post.isPresent()
                            && terms.postTerms(post.get().id(), "category").stream()
                                    .anyMatch(term ->
                                            term.id().equals(category.get().id()));
                    if (!in) {
                        return new Rule(post.map(this::permalink).orElse(null), post.orElse(null));
                    }
                }
            }
            return null;
        }

        /** The term rule: with every variable from the query string, the term's own link replaces the request's. */
        private void termRule(Parts r) {
            if (!(request.queriedObject().orElse(null) instanceof QueriedObject.Term queried)) {
                return;
            }
            TaxQuery taxQuery = request.query().map(query -> query.taxQuery()).orElse(TaxQuery.NONE);
            if (termCount(taxQuery) > 1) {
                return;
            }
            TermSnapshot term = queried.term();
            String taxonomy = term.taxonomy();
            String link = PhpUrls.bytes(permalinks.term(taxonomy, term));
            if (r.query.isEmpty()) {
                return;
            }
            List<String> remove = new ArrayList<>(List.of("term", "taxonomy"));
            if (flags.category()) {
                remove.add("category_name");
                remove.add("cat");
            } else if (flags.tag()) {
                remove.add("tag");
                remove.add("tag_id");
            } else {
                taxonomies.find(taxonomy).flatMap(TaxonomyDefinition::queryVar).ifPresent(remove::add);
            }
            Set<String> fromRewrite = new HashSet<>(request.vars().asMap().keySet());
            fromRewrite.removeAll(get.keySet());
            if (!fromRewrite.isEmpty()) {
                // WordPress then looks the removable names up as keys of a list: none is ever found
                return;
            }
            r.query = PhpUrls.removeQueryArgs(r.query, remove.toArray(String[]::new));
            Optional<PhpUrls.Url> target = PhpUrls.parseUrl(link);
            String targetQuery = target.map(parts -> parts.query).orElse(null);
            if (targetQuery != null && !targetQuery.isEmpty()) {
                Map<String, Object> vars = PhpUrls.parseStr(targetQuery);
                r.query = PhpUrls.addQueryArgs(vars, r.query);
            } else {
                r.path = target.map(parts -> parts.path == null ? "" : parts.path)
                        .orElse("");
            }
        }

        // ---- the 404 guess ----------------------------------------------------------------------------------------

        private Optional<String> guess() {
            String name = qv.get("name").orElse("");
            if (name.isEmpty()) {
                return Optional.empty();
            }
            List<String> viewableStatuses = statuses.all().stream()
                    .filter(PostPermissions::statusViewable)
                    .map(PostStatusDefinition::name)
                    .toList();
            List<String> viewableTypes = types.all().stream()
                    .filter(type -> !type.excludeFromSearch())
                    .filter(PostPermissions::typeViewable)
                    .map(PostTypeDefinition::key)
                    .toList();
            String postType = qv.get("post_type").orElse("");
            List<String> searched = viewableTypes;
            if (truthy(postType)) {
                if (!viewableTypes.contains(postType)) {
                    return Optional.empty();
                }
                searched = List.of(postType);
            }
            PostLookups.DateParts date = PostLookups.DateParts.of(number("year"), number("monthnum"), number("day"));
            Optional<PostId> found = lookups.firstBySlugPrefix(searched, viewableStatuses, name, date);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Optional<PostSnapshot> post = post(found.get().value());
            if (post.isEmpty()) {
                return Optional.empty();
            }
            String link = permalink(post.get());
            long page = number("page");
            if (page > 1) {
                return Optional.of(PhpUrls.trailingslashit(link) + userTrailingSlash(Long.toString(page)));
            }
            return Optional.of(link);
        }

        // ---- helpers over the request -----------------------------------------------------------------------------

        private long number(String name) {
            return FlagParser.phpInt(qv.get(name).orElse(""));
        }

        private long queriedId() {
            return request.queriedObject().map(QueriedObject::id).orElse(0L);
        }

        /** The queried post (or page, attachment, posts page) read as stored. */
        private Optional<PostSnapshot> queriedPost() {
            long id = queriedId();
            return id > 0 && isPostObject() ? post(id) : Optional.empty();
        }

        private boolean isPostObject() {
            return request.queriedObject().orElse(null) instanceof QueriedObject.Post
                    || request.queriedObject().orElse(null) instanceof QueriedObject.PostsPage;
        }

        /** WordPress's current post: the first post the request shows. */
        private Optional<PostSnapshot> currentPost() {
            if (request.shownPost().isPresent()) {
                return post(request.shownPost().get().id().value());
            }
            return request.posts().posts().isEmpty()
                    ? Optional.empty()
                    : post(request.posts().posts().get(0).id().value());
        }

        private boolean readable(PostSnapshot post) {
            String status = status(post);
            boolean privateStatus = statuses.find(status)
                    .map(PostStatusDefinition::privateStatus)
                    .orElse(false);
            if (privateStatus && permissions.can(viewer.identity(), "read_post", post)) {
                return true;
            }
            boolean typeViewable =
                    types.find(post.type()).map(PostPermissions::typeViewable).orElse(false);
            boolean statusViewable =
                    statuses.find(status).map(PostPermissions::statusViewable).orElse(false);
            return typeViewable && statusViewable;
        }

        private boolean usingIndexPermalinks() {
            String structure = options.string("permalink_structure", "");
            return PhpUrls.ltrim(structure, '/').startsWith("index.php");
        }

        private String userTrailingSlash(String text) {
            return permalinks.trailingSlashes() ? PhpUrls.trailingslashit(text) : PhpUrls.untrailingslashit(text);
        }

        private String yearLink(long year) {
            return PhpUrls.bytes(permalinks.year(orToday(year, LocalDate::getYear)));
        }

        private String monthLink(long year, long month) {
            return PhpUrls.bytes(
                    permalinks.month(orToday(year, LocalDate::getYear), orToday(month, LocalDate::getMonthValue)));
        }

        private String dayLink(long year, long month, long day) {
            return PhpUrls.bytes(permalinks.day(
                    orToday(year, LocalDate::getYear),
                    orToday(month, LocalDate::getMonthValue),
                    orToday(day, LocalDate::getDayOfMonth)));
        }

        /** A date part, or today's in the site's zone when it is 0, as WordPress's date links take it. */
        private int orToday(long value, ToIntFunction<LocalDate> part) {
            if (value == 0) {
                return part.applyAsInt(LocalDate.ofInstant(clock.now(), site.zone()));
            }
            return (int) Math.max(Integer.MIN_VALUE, Math.min(value, Integer.MAX_VALUE));
        }

        private Optional<String> fileUrl(PostId attachment) {
            return files.get().url(attachment).map(PhpUrls::bytes);
        }

        private boolean hasPublishedPost(UserId author) {
            return repository.count(
                            "siteId = ?1 and authorId = ?2 and status = 'publish'",
                            TenantIds.param(site.siteId()),
                            author.value())
                    > 0;
        }

        /** WordPress's {@code get_category_by_path} with a full match, over the request's raw category path. */
        private Optional<TermSnapshot> categoryByPath(String rawPath) {
            String path = PhpUrls.rawurlencode(PhpUrls.urldecode(PhpUrls.bytes(rawPath)))
                    .replace("%2F", "/")
                    .replace("%20", " ");
            String paths = "/" + PhpUrls.trim(path, '/');
            List<String> segments = segments(paths);
            String leaf = Slugs.sanitizeTitle(PhpUrls.text(segments.get(segments.size() - 1)));
            StringBuilder full = new StringBuilder();
            for (String segment : segments) {
                full.append(segment.isEmpty() ? "" : "/").append(Slugs.sanitizeTitle(PhpUrls.text(segment)));
            }
            Optional<TermSnapshot> category = terms.findBy("category", TermField.SLUG, leaf);
            if (category.isEmpty()) {
                return Optional.empty();
            }
            StringBuilder chain = new StringBuilder("/").append(leaf);
            TermSnapshot current = category.get();
            int depth = 0;
            while (current.parent().isPresent() && !current.parent().get().equals(current.id()) && depth++ < 64) {
                Optional<TermSnapshot> parent = terms.find(current.parent().get(), "category");
                if (parent.isEmpty()) {
                    break;
                }
                current = parent.get();
                chain.insert(0, "/" + current.slug());
            }
            return chain.toString().equals(full.toString()) ? category : Optional.empty();
        }

        private Optional<PostSnapshot> post(long id) {
            return id > 0
                    ? repository.findPost(site.siteId(), PostId.of(id)).map(PostEntity::toSnapshot)
                    : Optional.empty();
        }

        private String permalink(PostSnapshot post) {
            return PhpUrls.bytes(permalinks.post(post));
        }

        /**
         * WordPress's {@code get_post_status}: an attachment inheriting its status has its parent's ({@code publish}
         * without a parent; a trashed parent's status before trashing); a status of its own is kept when private,
         * trashed or auto-draft and reads as {@code publish} otherwise.
         */
        private String status(PostSnapshot post) {
            if (!post.type().equals("attachment")) {
                return post.status();
            }
            String own = post.status();
            if (own.equals("inherit")) {
                Optional<PostSnapshot> parent =
                        post.parent().filter(id -> !id.equals(post.id())).flatMap(id -> post(id.value()));
                if (parent.isEmpty()) {
                    return "publish";
                }
                String status = parent.get().status();
                if (status.equals("trash")) {
                    return parent.get().meta().getOrDefault("_wp_trash_meta_status", List.of()).stream()
                            .findFirst()
                            .map(JsonNode::asText)
                            .orElse("trash");
                }
                return status;
            }
            return own.equals("private") || own.equals("trash") || own.equals("auto-draft") ? own : "publish";
        }
    }

    /** A rule's target URL (null for none) and the post it was derived from (null for none). */
    private record Rule(String url, PostSnapshot post) {}

    // ---- the static normalisations --------------------------------------------------------------------------------

    /** The number of terms of the first clause asking for posts with terms, taxonomy by taxonomy. */
    static int termCount(TaxQuery taxQuery) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (TaxClause clause : flatten(taxQuery.clauses())) {
            switch (clause) {
                case TaxClause.Terms ids
                when ids.operator() != TaxClause.Operator.NOT_IN
                        && !ids.termIds().isEmpty() ->
                    counted.putIfAbsent(ids.taxonomy(), ids.termIds().size());
                case TaxClause.Slugs slugs
                when !slugs.slugs().isEmpty() ->
                    counted.putIfAbsent(slugs.taxonomy(), slugs.slugs().size());
                default -> {}
            }
        }
        return counted.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static List<TaxClause> flatten(List<TaxClause> clauses) {
        List<TaxClause> flat = new ArrayList<>();
        for (TaxClause clause : clauses) {
            if (clause instanceof TaxClause.Group group) {
                flat.addAll(flatten(group.clauses()));
            } else {
                flat.add(clause);
            }
        }
        return flat;
    }

    /** PHP's {@code checkdate}. */
    static boolean checkdate(long month, long day, long year) {
        if (month < 1 || month > 12 || day < 1 || year < 1 || year > 32767) {
            return false;
        }
        return day <= YearMonth.of((int) year, (int) month).lengthOfMonth();
    }

    /** The path without trailing {@code %C2%A0} runs (any case). */
    static String withoutTrailingNonBreakingSpaces(String path) {
        String result = path;
        while (result.length() >= 6 && result.regionMatches(true, result.length() - 6, "%C2%A0", 0, 6)) {
            result = result.substring(0, result.length() - 6);
        }
        return result;
    }

    /** PHP's {@code rtrim} with a character mask. */
    static String rtrimMask(String text, String mask) {
        int end = text.length();
        while (end > 0 && mask.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * The path without its list-paging, feed and comment-page endings, removed as long as one ends it: {@code
     * /page/N} (an optional slash before the number), {@code /feed}, {@code /rss}, {@code /rss2}, {@code /rdf}, {@code
     * /atom} (with an optional {@code comments/} before them) and {@code /comment-page-N}, each with any trailing
     * slashes, replaced by one slash.
     */
    static String withoutPagingSuffixes(String path) {
        String current = path;
        while (true) {
            int paging = pagingSuffix(current);
            int feed = feedSuffix(current);
            int comments = commentPageSuffix(current);
            if (paging < 0 && feed < 0 && comments < 0) {
                return current;
            }
            if (paging >= 0) {
                current = current.substring(0, paging) + "/";
            }
            current = withoutFeedSegments(current);
            int again = commentPageSuffix(current);
            if (again >= 0) {
                current = current.substring(0, again) + "/";
            }
        }
    }

    /** The start of a trailing {@code /page/N} (slash before N optional) with any trailing slashes, or -1. */
    private static int pagingSuffix(String path) {
        int end = PhpUrls.rtrim(path, '/').length();
        int digits = end;
        while (digits > 0 && path.charAt(digits - 1) >= '0' && path.charAt(digits - 1) <= '9') {
            digits--;
        }
        if (digits == end) {
            return -1;
        }
        int before = digits;
        if (before > 0 && path.charAt(before - 1) == '/') {
            before--;
        }
        return before >= 5 && path.startsWith("/page", before - 5) ? before - 5 : -1;
    }

    /** The start of a trailing {@code /comment-page-N} with any trailing slashes, or -1. */
    private static int commentPageSuffix(String path) {
        int end = PhpUrls.rtrim(path, '/').length();
        int digits = end;
        while (digits > 0 && path.charAt(digits - 1) >= '0' && path.charAt(digits - 1) <= '9') {
            digits--;
        }
        if (digits == end) {
            return -1;
        }
        String marker = "/comment-page-";
        return digits >= marker.length() && path.startsWith(marker, digits - marker.length())
                ? digits - marker.length()
                : -1;
    }

    /** The start of a trailing feed segment ({@code /comments/feed} and the like) with trailing slashes, or -1. */
    private static int feedSuffix(String path) {
        int end = PhpUrls.rtrim(path, '/').length();
        for (String name : FEED_NAMES) {
            int start = end - name.length() - 1;
            if (start >= 0 && path.startsWith("/" + name, start)) {
                return start;
            }
        }
        return -1;
    }

    /**
     * Every {@code /feed} (or {@code /rss}, {@code /rss2}, {@code /rdf}, {@code /atom}, each with an optional {@code
     * comments/} before it) followed by slashes or the end, replaced by one slash, left to right.
     */
    static String withoutFeedSegments(String path) {
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            int matched = feedSegmentAt(path, i);
            if (matched > 0) {
                out.append('/');
                i += matched;
            } else {
                out.append(path.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The length of a feed segment starting at {@code at} (with its slashes), or 0. */
    private static int feedSegmentAt(String path, int at) {
        if (path.charAt(at) != '/') {
            return 0;
        }
        int[] starts = {at + 1, -1, -1};
        if (path.startsWith("comments", at + 1)) {
            int after = at + 1 + "comments".length();
            starts[1] = after < path.length() && path.charAt(after) == '/' ? after + 1 : -1;
            starts[2] = after;
        }
        for (int start : new int[] {starts[1], starts[2], starts[0]}) {
            if (start < 0) {
                continue;
            }
            for (String name : FEED_NAMES) {
                if (path.startsWith(name, start)) {
                    int end = start + name.length();
                    if (end == path.length()) {
                        return end - at;
                    }
                    if (path.charAt(end) == '/') {
                        while (end < path.length() && path.charAt(end) == '/') {
                            end++;
                        }
                        return end - at;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * A trailing {@code /index.php} replaced by a slash: with any slashes after it when {@code slashes}, else with at
     * most one.
     */
    static String withoutTrailingIndex(String path, boolean slashes) {
        String trimmed =
                slashes ? PhpUrls.rtrim(path, '/') : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (trimmed.endsWith("/index.php")) {
            return trimmed.substring(0, trimmed.length() - "/index.php".length()) + "/";
        }
        return path;
    }

    /** The path without its trailing run of spaces and end punctuation, raw or percent-encoded. */
    static String withoutTrailingPunctuation(String path) {
        String current = path;
        while (true) {
            String next = withoutOnePunctuation(current);
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
    }

    private static String withoutOnePunctuation(String text) {
        for (String token : PUNCTUATION) {
            if (text.endsWith(token)) {
                return text.substring(0, text.length() - token.length());
            }
        }
        return text;
    }

    /** End punctuation removed from the value of a last {@code p}, {@code page_id}, {@code cat} or {@code tag}. */
    static String withoutQueryPunctuation(String query) {
        int last = query.lastIndexOf('&') + 1;
        String arg = query.substring(last);
        int equals = arg.indexOf('=');
        if (equals < 0 || !isClearedName(arg.substring(0, equals))) {
            return query;
        }
        String value = arg.substring(equals + 1);
        String stripped = withoutTrailingPunctuation(value);
        return query.substring(0, last) + arg.substring(0, equals + 1) + stripped;
    }

    private static boolean isClearedName(String name) {
        for (String cleared : CLEARED_ARGS) {
            if (cleared.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Each empty {@code p}, {@code page_id}, {@code cat} or {@code tag} argument (with or without {@code =}) replaced
     * by {@code &}, scanning left to right; an argument right after one replaced is left as it is, as WordPress's
     * replacement consumes the separator.
     */
    static String withoutEmptyArgs(String query) {
        StringBuilder out = new StringBuilder(query.length());
        int i = 0;
        while (i < query.length()) {
            int matched = emptyArgAt(query, i);
            if (matched > 0) {
                out.append('&');
                i += matched;
            } else {
                out.append(query.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The length of an empty cleared argument at {@code at} (with the separator before and after it), or 0. */
    private static int emptyArgAt(String query, int at) {
        List<Integer> starts = new ArrayList<>();
        if (at == 0) {
            starts.add(0);
        }
        if (query.charAt(at) == '&') {
            starts.add(at + 1);
        }
        for (int start : starts) {
            for (String name : CLEARED_ARGS) {
                if (!query.startsWith(name, start)) {
                    continue;
                }
                int end = start + name.length();
                if (end < query.length() && query.charAt(end) == '=') {
                    end++;
                }
                if (end == query.length()) {
                    return end - at;
                }
                if (query.charAt(end) == '&') {
                    return end + 1 - at;
                }
            }
        }
        return 0;
    }

    /**
     * {@code feed=rss} arguments as {@code feed=rss2}, scanning left to right; an argument right after one replaced is
     * left as it is, as WordPress's replacement consumes the separator.
     */
    static String withRss2(String query) {
        StringBuilder out = new StringBuilder(query.length());
        int consumed = 0;
        int i = 0;
        while (i < query.length()) {
            boolean boundary = i == 0 || (query.charAt(i - 1) == '&' && i - 1 >= consumed);
            if (boundary && query.startsWith("feed=rss", i)) {
                int end = i + "feed=rss".length();
                if (end == query.length() || query.charAt(end) == '&') {
                    out.append("feed=rss2");
                    if (end < query.length()) {
                        out.append('&');
                        end++;
                    }
                    i = end;
                    consumed = end;
                    continue;
                }
            }
            out.append(query.charAt(i));
            i++;
        }
        return out.toString();
    }

    static String withoutLeadingQuestionMark(String query) {
        return query.startsWith("?") ? query.substring(1) : query;
    }

    static String singleSlashes(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && i > 0 && path.charAt(i - 1) == '/') {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Hex-encoded octets in lower case. */
    static String lowerOctets(String url) {
        StringBuilder out = new StringBuilder(url);
        for (int i = 0; i + 2 < out.length(); i++) {
            if (out.charAt(i) == '%' && PhpUrls.hex(out.charAt(i + 1)) >= 0 && PhpUrls.hex(out.charAt(i + 2)) >= 0) {
                out.setCharAt(i + 1, Character.toLowerCase(out.charAt(i + 1)));
                out.setCharAt(i + 2, Character.toLowerCase(out.charAt(i + 2)));
                i += 2;
            }
        }
        return out.toString();
    }

    /** WordPress's {@code strip_fragment_from_url}. */
    static String withoutFragment(String url) {
        Optional<PhpUrls.Url> parsed = PhpUrls.parseUrl(url);
        if (parsed.isEmpty() || parsed.get().host == null || parsed.get().host.isEmpty()) {
            return url;
        }
        PhpUrls.Url parts = parsed.get();
        StringBuilder out = new StringBuilder();
        if (parts.scheme != null && !parts.scheme.isEmpty()) {
            out.append(parts.scheme).append(':');
        }
        out.append("//").append(parts.host);
        if (parts.port != null && parts.port != 0) {
            out.append(':').append(parts.port);
        }
        if (parts.path != null) {
            out.append(parts.path);
        }
        if (parts.query != null && !parts.query.isEmpty()) {
            out.append('?').append(parts.query);
        }
        return out.toString();
    }

    /** The parts WordPress compares: host and path, then the port and the query when not empty. */
    private static List<Object> compared(Parts parts) {
        List<Object> list = new ArrayList<>(List.of(parts.host, parts.path));
        if (parts.port != null && parts.port != 0) {
            list.add(parts.port);
        }
        if (!phpEmpty(parts.query)) {
            list.add(parts.query);
        }
        return list;
    }

    /** PHP's {@code explode('/', path)}. */
    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (true) {
            int slash = path.indexOf('/', start);
            if (slash < 0) {
                segments.add(path.substring(start));
                return segments;
            }
            segments.add(path.substring(start, slash));
            start = slash + 1;
        }
    }

    static boolean phpEmpty(String value) {
        return value == null || value.isEmpty() || value.equals("0");
    }

    private static boolean truthy(String value) {
        return !phpEmpty(value);
    }

    private static boolean nonEmpty(Object value) {
        return value instanceof Map<?, ?> map ? !map.isEmpty() : value instanceof String text && !phpEmpty(text);
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
