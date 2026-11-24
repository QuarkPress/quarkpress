package org.quarkword.front.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.TaxonomyDefinition;

/**
 * WordPress's compiled rewrite table for a permalink structure and the registered post types and taxonomies: an
 * ordered list of (pattern, query) rules, the request path matched from its start against each in turn, the first
 * match winning. The patterns and query templates are the ones WordPress compiles, in its order: the REST and sitemap
 * rules, the post type archives, the taxonomy and post type permastructs, the fixed file rules, then the root,
 * comments, search, author and date groups, then the page and post groups (pages first when the structure's first tag
 * makes page rules verbose). Every pattern is anchored at the start; all but the {@code sitemap.xml} and
 * {@code wp-json/...} rules consume the whole path, tolerating one trailing slash. An empty structure has no rules.
 * Nothing here reads the database.
 */
public final class RewriteRules {

    private static final String FEED = "(feed|rdf|rss|rss2|atom)";
    private static final String NUMBER = "([0-9]{1,})";
    private static final List<String> TIME_TAGS =
            List.of("%year%", "%monthnum%", "%day%", "%hour%", "%minute%", "%second%");
    private static final Pattern MATCHES = Pattern.compile("\\$matches\\[([0-9]+)\\]");
    private static final Pattern TAG = Pattern.compile("%[^%/]+%");

    /** A compiled rule: its pattern, its query template (values may hold {@code $matches[n]}), and whether it captures a page path. */
    public record Rule(Pattern pattern, Map<String, String> query, boolean pagenameCapture) {

        public Rule {
            Objects.requireNonNull(pattern, "pattern");
            query = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(query, "query")));
        }

        /** The regular expression as WordPress spells it (without the anchor added for matching). */
        public String regex() {
            return pattern.pattern().substring(1);
        }

        /** The query template as WordPress spells it: {@code index.php?var=$matches[1]&...}. */
        public String template() {
            StringBuilder out = new StringBuilder("index.php?");
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    out.append('&');
                }
                first = false;
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return out.toString();
        }

        /** The variables of a match: each template value with its captures filled in (an absent group is empty). */
        Map<String, String> vars(Matcher matcher) {
            Map<String, String> vars = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : query.entrySet()) {
                vars.put(entry.getKey(), fill(entry.getValue(), matcher));
            }
            return vars;
        }

        private static String fill(String template, Matcher matcher) {
            Matcher placeholders = MATCHES.matcher(template);
            StringBuilder out = new StringBuilder();
            while (placeholders.find()) {
                int group = Integer.parseInt(placeholders.group(1));
                String capture = group <= matcher.groupCount() ? matcher.group(group) : null;
                placeholders.appendReplacement(out, Matcher.quoteReplacement(capture == null ? "" : capture));
            }
            placeholders.appendTail(out);
            return out.toString();
        }
    }

    /** A matched rule and the variables it sets. */
    public record Match(Rule rule, Map<String, String> vars) {
        public Match {
            Objects.requireNonNull(rule, "rule");
            vars = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(vars, "vars")));
        }
    }

    /** A structure tag: its regular expression with the capture, and the query piece before {@code $matches[n]}. */
    private record Tag(String name, String regex, String query) {
        String flat() {
            return regex.substring(1, regex.length() - 1);
        }
    }

    private final List<Rule> rules;

    private RewriteRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** No rules: plain mode. */
    public static RewriteRules none() {
        return new RewriteRules(List.of());
    }

    /**
     * The table for {@code structure} with these post types and taxonomies; {@code homeHasPath} leaves out the
     * {@code robots.txt}, {@code favicon.ico} and {@code sitemap.xml} rules (a site below the host root has none),
     * {@code staticFrontPage} adds the front page's comment paging rule.
     */
    public static RewriteRules of(
            Structure structure,
            List<PostTypeDefinition> types,
            List<TaxonomyDefinition> taxonomies,
            boolean homeHasPath,
            boolean staticFrontPage) {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(taxonomies, "taxonomies");
        if (structure.plain()) {
            return none();
        }
        return new RewriteRules(new Compiler(structure, types, taxonomies, homeHasPath, staticFrontPage).compile());
    }

    public List<Rule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The first rule matching {@code path}, raw first and then percent-decoded, with its variables. */
    public Optional<Match> match(String path) {
        return match(path, match -> true);
    }

    /**
     * The first rule matching {@code path} whose match {@code accept} takes; each rule is tried against the raw path
     * first, then against the path percent-decoded as PHP decodes it ({@code +} as a space).
     */
    public Optional<Match> match(String path, Predicate<Match> accept) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(accept, "accept");
        String decoded = phpUrldecode(path);
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(path);
            if (!matcher.lookingAt()) {
                if (decoded.equals(path)) {
                    continue;
                }
                matcher = rule.pattern().matcher(decoded);
                if (!matcher.lookingAt()) {
                    continue;
                }
            }
            Match match = new Match(rule, rule.vars(matcher));
            if (accept.test(match)) {
                return Optional.of(match);
            }
        }
        return Optional.empty();
    }

    /** PHP's {@code urldecode}: {@code +} becomes a space, {@code %XX} its byte; anything else stays. */
    static String phpUrldecode(String text) {
        if (text.indexOf('%') < 0 && text.indexOf('+') < 0) {
            return text;
        }
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '+') {
                out.write(' ');
            } else if (b == '%' && i + 2 < raw.length && hex(raw[i + 1]) >= 0 && hex(raw[i + 2]) >= 0) {
                out.write(hex(raw[i + 1]) * 16 + hex(raw[i + 2]));
                i += 2;
            } else {
                out.write(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    /** One compilation: the table built as an ordered map, a repeated pattern keeping its first position. */
    private static final class Compiler {

        private final Structure structure;
        private final List<PostTypeDefinition> types;
        private final List<TaxonomyDefinition> taxonomies;
        private final boolean homeHasPath;
        private final boolean staticFrontPage;
        private final Map<String, Tag> tags = new LinkedHashMap<>();
        private final Map<String, String> table = new LinkedHashMap<>();

        Compiler(
                Structure structure,
                List<PostTypeDefinition> types,
                List<TaxonomyDefinition> taxonomies,
                boolean homeHasPath,
                boolean staticFrontPage) {
            this.structure = structure;
            this.types = types;
            this.taxonomies = taxonomies;
            this.homeHasPath = homeHasPath;
            this.staticFrontPage = staticFrontPage;
            tag("%year%", "([0-9]{4})", "year=");
            tag("%monthnum%", "([0-9]{1,2})", "monthnum=");
            tag("%day%", "([0-9]{1,2})", "day=");
            tag("%hour%", "([0-9]{1,2})", "hour=");
            tag("%minute%", "([0-9]{1,2})", "minute=");
            tag("%second%", "([0-9]{1,2})", "second=");
            tag("%postname%", "([^/]+)", "name=");
            tag("%post_id%", "([0-9]+)", "p=");
            tag("%author%", "([^/]+)", "author_name=");
            tag("%pagename%", "([^/]+?)", "pagename=");
            tag("%search%", "(.+)", "s=");
            for (TaxonomyDefinition taxonomy : taxonomies) {
                if (taxonomy.rewrite().isPresent()) {
                    boolean hierarchical = taxonomy.rewrite().get().hierarchical();
                    String query =
                            taxonomy.queryVar().map(var -> var + "=").orElse("taxonomy=" + taxonomy.key() + "&term=");
                    tag("%" + taxonomy.key() + "%", hierarchical ? "(.+?)" : "([^/]+)", query);
                }
            }
            for (PostTypeDefinition type : types) {
                if (type.rewrite().isPresent()) {
                    String query = type.queryVar()
                            .map(var -> var + "=")
                            .orElse("post_type=" + type.key() + "&" + (type.hierarchical() ? "pagename=" : "name="));
                    tag("%" + type.key() + "%", type.hierarchical() ? "(.+?)" : "([^/]+)", query);
                }
            }
        }

        private void tag(String name, String regex, String query) {
            tags.put(name, new Tag(name, regex, query));
        }

        List<Rule> compile() {
            components();
            archives();
            permastructs();
            fixed();
            rootGroup();
            commentsGroup();
            structure.searchStructure().ifPresent(search -> archive(search, true, true, false, false));
            structure.authorStructure().ifPresent(author -> archive(author, true, true, false, true));
            structure.dateStructure().ifPresent(date -> archive(date, true, true, false, true));
            if (structure.verbosePageRules()) {
                pageGroup();
                postGroup();
            } else {
                postGroup();
                pageGroup();
            }
            List<Rule> rules = new ArrayList<>(table.size());
            table.forEach((regex, query) -> rules.add(rule(regex, query)));
            return rules;
        }

        private static Rule rule(String regex, String query) {
            Map<String, String> vars = new LinkedHashMap<>();
            for (String piece : query.split("&")) {
                int equals = piece.indexOf('=');
                if (equals > 0) {
                    vars.put(piece.substring(0, equals), piece.substring(equals + 1));
                }
            }
            boolean pagename = query.contains("pagename=$matches[");
            return new Rule(Pattern.compile("^" + regex), vars, pagename);
        }

        private void put(String regex, String query) {
            table.put(regex, query);
        }

        private void components() {
            put("^wp-json/?$", "rest_route=/");
            put("^wp-json/(.*)?", "rest_route=/$matches[1]");
            put("^index.php/wp-json/?$", "rest_route=/");
            put("^index.php/wp-json/(.*)?", "rest_route=/$matches[1]");
            put("^wp-sitemap\\.xml$", "sitemap=index");
            put("^wp-sitemap\\.xsl$", "sitemap-stylesheet=sitemap");
            put("^wp-sitemap-index\\.xsl$", "sitemap-stylesheet=index");
            put(
                    "^wp-sitemap-([a-z]+?)-([a-z\\d_-]+?)-(\\d+?)\\.xml$",
                    "sitemap=$matches[1]&sitemap-subtype=$matches[2]&paged=$matches[3]");
            put("^wp-sitemap-([a-z]+?)-(\\d+?)\\.xml$", "sitemap=$matches[1]&paged=$matches[2]");
        }

        private void archives() {
            for (PostTypeDefinition type : types) {
                if (type.rewrite().isEmpty() || !type.hasArchive()) {
                    continue;
                }
                PostTypeDefinition.Rewrite rewrite = type.rewrite().get();
                Optional<String> archive = structure.postTypeArchive(type);
                if (archive.isEmpty()) {
                    continue;
                }
                String base = archive.get();
                String query = "post_type=" + type.key();
                put(base + "/?$", query);
                if (rewrite.feeds()) {
                    put(base + "/feed/" + FEED + "/?$", query + "&feed=$matches[1]");
                    put(base + "/" + FEED + "/?$", query + "&feed=$matches[1]");
                }
                if (rewrite.pages()) {
                    put(base + "/page/" + NUMBER + "/?$", query + "&paged=$matches[1]");
                }
            }
        }

        /** The taxonomy and post type permastruct groups, built-in taxonomies first, then the custom registrations. */
        private void permastructs() {
            for (TaxonomyDefinition taxonomy : taxonomies) {
                if (taxonomy.builtIn()) {
                    taxonomyGroup(taxonomy);
                }
            }
            for (PostTypeDefinition type : types) {
                if (type.rewrite().isEmpty() || type.builtIn()) {
                    continue;
                }
                structure
                        .postTypeStructure(type)
                        .ifPresent(permastruct -> group(
                                permastruct, type.rewrite().get().feeds(), true, true, type.hierarchical(), true));
            }
            for (TaxonomyDefinition taxonomy : taxonomies) {
                if (!taxonomy.builtIn()) {
                    taxonomyGroup(taxonomy);
                }
            }
        }

        private void taxonomyGroup(TaxonomyDefinition taxonomy) {
            if (taxonomy.rewrite().isEmpty()) {
                return;
            }
            structure
                    .taxonomyStructure(taxonomy)
                    .ifPresent(permastruct -> archive(permastruct, true, true, false, true));
        }

        private void fixed() {
            if (!homeHasPath) {
                put("robots\\.txt$", "robots=1");
                put("favicon\\.ico$", "favicon=1");
                put("sitemap\\.xml", "sitemap=index");
            }
            put(".*wp-(atom|rdf|rss|rss2|feed|commentsrss2)\\.php$", "feed=old");
            put(".*wp-app\\.php(/.*)?$", "error=403");
            put(".*wp-register.php$", "register=true");
        }

        private void rootGroup() {
            String root = trimSlashes(structure.root());
            put(join(root, "feed/" + FEED + "/?$"), "feed=$matches[1]");
            put(join(root, FEED + "/?$"), "feed=$matches[1]");
            put(join(root, "embed/?$"), "embed=true");
            put(join(root, "page/?" + NUMBER + "/?$"), "paged=$matches[1]");
            if (staticFrontPage) {
                put(join(root, "comment-page-" + NUMBER + "/?$"), "cpage=$matches[1]");
            }
        }

        private void commentsGroup() {
            String comments = join(trimSlashes(structure.root()), "comments");
            put(comments + "/feed/" + FEED + "/?$", "feed=$matches[1]&withcomments=1");
            put(comments + "/" + FEED + "/?$", "feed=$matches[1]&withcomments=1");
            put(comments + "/embed/?$", "embed=true");
        }

        private void pageGroup() {
            structure.pageStructure().ifPresent(permastruct -> {
                Tag page = new Tag("%pagename%", "(.?.+?)", "pagename=");
                Map<String, Tag> withPage = new LinkedHashMap<>(tags);
                withPage.put(page.name(), page);
                generate(permastruct, withPage, true, true, true, true, false, true);
            });
        }

        private void postGroup() {
            generate(structure.value(), tags, true, true, true, false, true, true);
        }

        /** An archive structure: feeds and embed, paging, comment paging as given, every tag-bearing level. */
        private void archive(String permastruct, boolean feeds, boolean paging, boolean comments, boolean walk) {
            generate(permastruct, tags, feeds, paging, comments, false, walk, false);
        }

        /** A post-level structure (a custom post type): feeds as given, paging and comment paging on. */
        private void group(
                String permastruct, boolean feeds, boolean paging, boolean comments, boolean pageLike, boolean walk) {
            generate(permastruct, tags, feeds, paging, comments, pageLike, walk, true);
        }

        /**
         * WordPress's rule generation for one structure: its slash-separated parts are walked from the deepest prefix
         * to the shallowest (only the whole structure when {@code walk} is off), each prefix with at least one tag
         * giving a group of rules; a structure without any tag gives the root-style group of feed, embed and paging
         * rules. A prefix is post-level when it holds a post name, a post id, a page name, a post type tag or all six
         * time tags; such a prefix gets the attachment rules, the trackback and embed endpoints and the
         * {@code <!--nextpage-->} suffix, and (unless page-like) the short attachment rules.
         */
        private void generate(
                String permastruct,
                Map<String, Tag> known,
                boolean feeds,
                boolean paging,
                boolean comments,
                boolean pageLike,
                boolean walk,
                boolean postLevelAllowed) {
            String trimmed = trimSlashes(permastruct);
            List<String> dirs = trimmed.isEmpty() ? List.of() : List.of(trimmed.split("/"));
            if (!TAG.matcher(trimmed).find()) {
                taglessGroup(trimmed, feeds, paging, comments);
                return;
            }
            for (int level = dirs.size(); level >= 1; level--) {
                if (!walk && level != dirs.size()) {
                    break;
                }
                String prefix = String.join("/", dirs.subList(0, level));
                List<Tag> found = tagsIn(prefix, known);
                if (found.isEmpty()) {
                    continue;
                }
                String regex = prefix;
                String flat = prefix;
                StringBuilder query = new StringBuilder();
                int number = 0;
                for (Tag tag : found) {
                    number++;
                    regex = regex.replace(tag.name(), tag.regex());
                    flat = flat.replace(tag.name(), tag.flat());
                    if (!query.isEmpty()) {
                        query.append('&');
                    }
                    query.append(tag.query()).append("$matches[").append(number).append(']');
                }
                String q = query.toString();
                String next = "$matches[" + (number + 1) + "]";
                boolean postLevel = postLevelAllowed && postLevel(prefix, found);
                if (postLevel) {
                    attachments(flat + "/attachment/([^/]+)");
                    put(regex + "/embed/?$", q + "&embed=true");
                    put(regex + "/trackback/?$", q + "&tb=1");
                    if (feeds) {
                        put(regex + "/feed/" + FEED + "/?$", q + "&feed=" + next);
                        put(regex + "/" + FEED + "/?$", q + "&feed=" + next);
                    }
                    if (paging) {
                        put(regex + "/page/?" + NUMBER + "/?$", q + "&paged=" + next);
                    }
                    if (comments) {
                        put(regex + "/comment-page-" + NUMBER + "/?$", q + "&cpage=" + next);
                    }
                    put(regex + "(?:/([0-9]+))?/?$", q + "&page=" + next);
                    if (!pageLike) {
                        attachments(flat + "/([^/]+)");
                    }
                } else {
                    if (feeds) {
                        put(regex + "/feed/" + FEED + "/?$", q + "&feed=" + next);
                        put(regex + "/" + FEED + "/?$", q + "&feed=" + next);
                        put(regex + "/embed/?$", q + "&embed=true");
                    }
                    if (paging) {
                        put(regex + "/page/?" + NUMBER + "/?$", q + "&paged=" + next);
                    }
                    if (comments) {
                        put(regex + "/comment-page-" + NUMBER + "/?$", q + "&cpage=" + next);
                    }
                    put(regex + "/?$", q);
                }
            }
        }

        private void taglessGroup(String prefix, boolean feeds, boolean paging, boolean comments) {
            if (feeds) {
                put(join(prefix, "feed/" + FEED + "/?$"), "feed=$matches[1]");
                put(join(prefix, FEED + "/?$"), "feed=$matches[1]");
                put(join(prefix, "embed/?$"), "embed=true");
            }
            if (paging) {
                put(join(prefix, "page/?" + NUMBER + "/?$"), "paged=$matches[1]");
            }
            if (comments) {
                put(join(prefix, "comment-page-" + NUMBER + "/?$"), "cpage=$matches[1]");
            }
        }

        /** The attachment rules of a post-level prefix: the base, then trackback, feeds, comment paging and embed. */
        private void attachments(String base) {
            put(base + "/?$", "attachment=$matches[1]");
            put(base + "/trackback/?$", "attachment=$matches[1]&tb=1");
            put(base + "/feed/" + FEED + "/?$", "attachment=$matches[1]&feed=$matches[2]");
            put(base + "/" + FEED + "/?$", "attachment=$matches[1]&feed=$matches[2]");
            put(base + "/comment-page-" + NUMBER + "/?$", "attachment=$matches[1]&cpage=$matches[2]");
            put(base + "/embed/?$", "attachment=$matches[1]&embed=true");
        }

        private boolean postLevel(String prefix, List<Tag> found) {
            for (Tag tag : found) {
                switch (tag.name()) {
                    case "%postname%", "%post_id%", "%pagename%" -> {
                        return true;
                    }
                    default -> {
                        if (isTypeTag(tag.name())) {
                            return true;
                        }
                    }
                }
            }
            for (String time : TIME_TAGS) {
                if (!prefix.contains(time)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isTypeTag(String name) {
            String key = name.substring(1, name.length() - 1);
            for (PostTypeDefinition type : types) {
                if (type.key().equals(key) && type.rewrite().isPresent()) {
                    return true;
                }
            }
            return false;
        }

        private static List<Tag> tagsIn(String prefix, Map<String, Tag> known) {
            List<Tag> found = new ArrayList<>();
            Matcher matcher = TAG.matcher(prefix);
            while (matcher.find()) {
                Tag tag = known.get(matcher.group());
                if (tag != null) {
                    found.add(tag);
                }
            }
            return found;
        }

        private static String join(String prefix, String suffix) {
            return prefix.isEmpty() ? suffix : prefix + "/" + suffix;
        }

        private static String trimSlashes(String path) {
            int start = 0;
            int end = path.length();
            while (start < end && path.charAt(start) == '/') {
                start++;
            }
            while (end > start && path.charAt(end - 1) == '/') {
                end--;
            }
            return path.substring(start, end);
        }
    }
}
