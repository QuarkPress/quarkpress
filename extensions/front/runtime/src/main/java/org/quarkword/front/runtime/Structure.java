package org.quarkword.front.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.TaxonomyDefinition;

/**
 * The state WordPress's rewrite object derives from the {@code permalink_structure}, {@code category_base} and
 * {@code tag_base} options: the literal front of the structure, whether links end with a slash, whether page rules
 * are verbose, the {@code index.php/} root of PATHINFO structures, and the structures of the date, author, search,
 * page, category, tag, post format, taxonomy and post type links. An empty structure is plain mode: no structure
 * exists and every derived structure is empty. Immutable; nothing here reads the database.
 */
public final class Structure {

    private static final String INDEX = "index.php";
    private static final List<String> DATE_ORDERS =
            List.of("%year%/%monthnum%/%day%", "%day%/%monthnum%/%year%", "%monthnum%/%day%/%year%");
    private static final List<String> VERBOSE_FIRST_TAGS = List.of("%postname%", "%category%", "%tag%", "%author%");

    private final String structure;
    private final String categoryBase;
    private final String tagBase;
    private final List<String> tags;
    private final String front;
    private final String root;

    private Structure(String structure, String categoryBase, String tagBase) {
        this.structure = structure;
        this.categoryBase = categoryBase;
        this.tagBase = tagBase;
        this.tags = Collections.unmodifiableList(tagsOf(structure));
        int firstTag = structure.indexOf('%');
        this.front = firstTag < 0 ? "" : structure.substring(0, firstTag);
        this.root = withoutLeadingSlashes(structure).startsWith(INDEX) ? INDEX + "/" : "";
    }

    public static Structure of(String permalinkStructure, String categoryBase, String tagBase) {
        return new Structure(
                Objects.requireNonNull(permalinkStructure, "permalinkStructure"),
                Objects.requireNonNull(categoryBase, "categoryBase"),
                Objects.requireNonNull(tagBase, "tagBase"));
    }

    /** The {@code permalink_structure} option as given. */
    public String value() {
        return structure;
    }

    /** Plain mode: the structure is empty, so every link is {@code home/?query}. */
    public boolean plain() {
        return structure.isEmpty();
    }

    /**
     * Everything before the first {@code %} ({@code /} for the default structure, {@code /blog/} for
     * {@code /blog/%postname%/}); empty for a structure without one.
     */
    public String front() {
        return front;
    }

    /** Whether the structure ends with {@code /}: then every pretty link gets exactly one trailing slash. */
    public boolean trailingSlashes() {
        return structure.endsWith("/");
    }

    /** Whether the first tag is {@code %postname%}, {@code %category%}, {@code %tag%} or {@code %author%}. */
    public boolean verbosePageRules() {
        return !tags.isEmpty() && VERBOSE_FIRST_TAGS.contains(tags.get(0));
    }

    /** {@code index.php/} for a PATHINFO structure (one beginning with {@code /index.php}), else empty. */
    public String root() {
        return root;
    }

    /** Whether the structure is a PATHINFO one. */
    public boolean pathinfo() {
        return !root.isEmpty();
    }

    /** The {@code %…%} tags of the structure in order. */
    public List<String> tags() {
        return tags;
    }

    /** Whether {@code %postname%} is the first tag. */
    public boolean postnameFirst() {
        return !tags.isEmpty() && tags.get(0).equals("%postname%");
    }

    /**
     * The date structure: the front, {@code date/} when {@code %post_id%} is among the first three tags, and the
     * year, month and day tags in the order the structure spells them (year/month/day unless it contains the
     * day/month/year or month/day/year order literally).
     */
    public Optional<String> dateStructure() {
        if (plain()) {
            return Optional.empty();
        }
        String order = DATE_ORDERS.get(0);
        for (String candidate : DATE_ORDERS) {
            if (structure.contains(candidate)) {
                order = candidate;
                break;
            }
        }
        String prefix = front;
        for (int i = 0; i < tags.size() && i < 3; i++) {
            if (tags.get(i).equals("%post_id%")) {
                prefix = prefix + "date/";
                break;
            }
        }
        return Optional.of(prefix + order);
    }

    /** The date structure without its month and day, runs of slashes collapsed. */
    public Optional<String> yearStructure() {
        return dateStructure()
                .map(date -> collapseSlashes(date.replace("%monthnum%", "").replace("%day%", "")));
    }

    /** The date structure without its day, runs of slashes collapsed. */
    public Optional<String> monthStructure() {
        return dateStructure().map(date -> collapseSlashes(date.replace("%day%", "")));
    }

    public Optional<String> authorStructure() {
        return plain() ? Optional.empty() : Optional.of(front + "author/%author%");
    }

    public Optional<String> searchStructure() {
        return plain() ? Optional.empty() : Optional.of(root + "search/%search%");
    }

    public Optional<String> pageStructure() {
        return plain() ? Optional.empty() : Optional.of(root + "%pagename%");
    }

    /**
     * The category structure: the {@code category_base} option, or {@code category}, then {@code /%category%}; under
     * the front when the base is empty or the structure is PATHINFO, else under the root.
     */
    public Optional<String> categoryStructure() {
        return baseStructure(categoryBase, "category", "%category%");
    }

    /** The tag structure, as {@link #categoryStructure} with {@code tag_base}, {@code tag} and {@code %post_tag%}. */
    public Optional<String> tagStructure() {
        return baseStructure(tagBase, "tag", "%post_tag%");
    }

    /** The post format structure: the front, {@code type/%post_format%}. */
    public Optional<String> postFormatStructure() {
        return plain() ? Optional.empty() : Optional.of(front + "type/%post_format%");
    }

    /**
     * A taxonomy's structure: its rewrite slug and {@code /%{taxonomy}%} under the front when the rewrite says
     * {@code with_front}, else under the root; the built-in ones follow the bases. Empty without rewrite rules.
     */
    public Optional<String> taxonomyStructure(TaxonomyDefinition taxonomy) {
        return switch (taxonomy.key()) {
            case "category" -> categoryStructure();
            case "post_tag" -> tagStructure();
            case "post_format" -> postFormatStructure();
            default ->
                plain()
                        ? Optional.empty()
                        : taxonomy.rewrite()
                                .map(rewrite -> (rewrite.withFront() ? front : root)
                                        + rewrite.slug()
                                        + "/%"
                                        + taxonomy.key()
                                        + "%");
        };
    }

    /** A post type's structure: its rewrite slug and {@code /%{type}%}, under the front or the root; empty without rewrite. */
    public Optional<String> postTypeStructure(PostTypeDefinition type) {
        if (plain()) {
            return Optional.empty();
        }
        return type.rewrite()
                .map(rewrite -> (rewrite.withFront() ? front : root) + rewrite.slug() + "/%" + type.key() + "%");
    }

    /**
     * The path of a post type's archive: its archive slug (the rewrite slug when {@code has_archive} is just on)
     * under the front without its leading slash, or under the root when the rewrite is not {@code with_front}.
     * Empty for a type without archive or without rewrite rules.
     */
    public Optional<String> postTypeArchive(PostTypeDefinition type) {
        if (plain() || !type.hasArchive()) {
            return Optional.empty();
        }
        return type.rewrite()
                .map(rewrite -> (rewrite.withFront() ? withoutLeadingSlashes(front) : root)
                        + type.archiveSlug().orElse(rewrite.slug()));
    }

    /**
     * WordPress's {@code user_trailingslashit}: the URL without its trailing slashes, then with exactly one when the
     * structure ends with a slash.
     */
    public String trailing(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = url.substring(0, end);
        return trailingSlashes() ? trimmed + "/" : trimmed;
    }

    private Optional<String> baseStructure(String base, String defaultBase, String tag) {
        if (plain()) {
            return Optional.empty();
        }
        boolean withFront = base.isEmpty() || pathinfo();
        return Optional.of((withFront ? front : root) + (base.isEmpty() ? defaultBase : base) + "/" + tag);
    }

    private static List<String> tagsOf(String structure) {
        List<String> tags = new ArrayList<>();
        int start = structure.indexOf('%');
        while (start >= 0) {
            int end = structure.indexOf('%', start + 1);
            if (end < 0) {
                break;
            }
            if (end > start + 1) {
                tags.add(structure.substring(start, end + 1));
                start = structure.indexOf('%', end + 1);
            } else {
                start = end;
            }
        }
        return tags;
    }

    private static String collapseSlashes(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '/' || out.isEmpty() || out.charAt(out.length() - 1) != '/') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String withoutLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Structure that
                && structure.equals(that.structure)
                && categoryBase.equals(that.categoryBase)
                && tagBase.equals(that.tagBase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(structure, categoryBase, tagBase);
    }

    @Override
    public String toString() {
        return "Structure[" + structure + "]";
    }
}
