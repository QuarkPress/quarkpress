package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.core.PostId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.MetaCapabilityNames;
import org.quarkpress.security.MissingObject;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;
import org.quarkpress.security.TaxonomyCapabilities;
import org.quarkpress.security.TermFacts;
import org.quarkpress.security.UserTarget;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares {@code MetaCaps} with WordPress 7.1.3's {@code map_meta_cap} on a matrix of capabilities, objects, users
 * and both install types; {@code PostTypeCapabilities.of} with {@code get_post_type_capabilities}; and the built-in
 * role table with the one {@code populate_roles} stores. Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class MetaCapsParityTest {

    static final Path DRIVER = Path.of("src/test/php/security-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-caps-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-caps-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/formatting.php",
        "wp-includes/functions.php",
        "wp-includes/capabilities.php",
        "wp-includes/class-wp-role.php",
        "wp-includes/class-wp-roles.php",
        "wp-includes/post.php",
        "wp-admin/includes/schema.php"
    };

    /** About half of what a run compares (21,309 at the time of writing). */
    static final int MIN_COMPARISONS = 10_600;

    static final PostTypeCapabilities BOOK = PostTypeCapabilities.of("book", "book", "books", true, Map.of());
    static final PostTypeCapabilities NOVEL = PostTypeCapabilities.of("novel", "novel", "novels", false, Map.of());
    /** Does not map meta capabilities, and names its page form edit_page (not the other two). */
    static final PostTypeCapabilities TALE =
            PostTypeCapabilities.of("tale", "tale", "tales", false, Map.of("edit_page", "edit_tale_page"));

    static final List<PostTypeCapabilities> TYPES = List.of(
            PostTypeCapabilities.POST,
            PostTypeCapabilities.PAGE,
            PostTypeCapabilities.ATTACHMENT,
            PostTypeCapabilities.BLOCK,
            BOOK,
            NOVEL,
            TALE);
    static final List<TaxonomyCapabilities> TAXONOMIES = List.of(
            TaxonomyCapabilities.CATEGORY,
            TaxonomyCapabilities.POST_TAG,
            TaxonomyCapabilities.defaults("genre"),
            TaxonomyCapabilities.of("shelf", Map.of("edit_terms", "manage_shelves", "assign_terms", "edit_books")));

    /**
     * WordPress's built-in statuses as get_post_status_object describes them (public, private). QuarkPress's own
     * visibility comes from PostFacts, not from this table.
     */
    static final Map<String, PostVisibility> STATUSES = new LinkedHashMap<>();

    static {
        STATUSES.put("publish", PostVisibility.PUBLIC);
        STATUSES.put("future", PostVisibility.OTHER);
        STATUSES.put("draft", PostVisibility.OTHER);
        STATUSES.put("pending", PostVisibility.OTHER);
        STATUSES.put("private", PostVisibility.PRIVATE);
        STATUSES.put("trash", PostVisibility.OTHER);
        STATUSES.put("auto-draft", PostVisibility.OTHER);
        STATUSES.put("inherit", PostVisibility.OTHER);
        STATUSES.put("request-pending", PostVisibility.OTHER);
        STATUSES.put("request-confirmed", PostVisibility.OTHER);
        STATUSES.put("request-failed", PostVisibility.OTHER);
        STATUSES.put("request-completed", PostVisibility.OTHER);
    }

    /** The post a capability is checked against and the posts around it, as the oracle's get_post serves them. */
    record Variant(String label, PostFacts facts, Optional<PostFacts> parent, long postParent) {}

    private final List<PhpOracle.Case> cases = new ArrayList<>();
    private final Map<String, FakeContext> contexts = new LinkedHashMap<>();
    private final Map<String, Supplier<Set<String>>> ours = new LinkedHashMap<>();
    private final MetaCaps caps = new MetaCaps(
            List.of(),
            List.<MetaCapabilityNames>of(
                    new BuiltInMetaCapabilityNames(),
                    () -> Map.of("edit_book", "edit_post", "read_book", "read_post", "delete_book", "delete_post")));

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun("security-caps", ParitySkipList.load(SKIPS), FAILURES);

        postCases();
        userCases();
        termCases();
        otherCases();
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        for (Map.Entry<String, Supplier<Set<String>>> entry : ours.entrySet()) {
            run.compare(
                    entry.getKey(),
                    "map_meta_cap",
                    wordpressCaps(out.get(entry.getKey())),
                    String.join(",", entry.getValue().get()));
            if (entry.getKey().startsWith("pageform")) {
                compareDecisions(
                        run,
                        entry.getKey(),
                        out.get(entry.getKey()),
                        entry.getValue().get());
            }
        }

        cases.clear();
        ours.clear();
        for (boolean multisite : new boolean[] {false, true}) {
            for (boolean superAdmin : new boolean[] {false, true}) {
                for (String cap : List.of("unfiltered_html", "edit_css")) {
                    FakeContext context = multisite ? FakeContext.network(7) : FakeContext.single(7);
                    if (superAdmin) {
                        context.superAdmin();
                    }
                    context.disallowUnfilteredHtml = true;
                    add("disallow-" + cap + "-" + multisite + "-" + superAdmin, cap, Optional.empty(), context);
                }
            }
        }
        Map<String, JsonNode> disallowed =
                oracle.withEnvironment("QP_DISALLOW_UNFILTERED_HTML", "1").run(DRIVER, cases);
        for (Map.Entry<String, Supplier<Set<String>>> entry : ours.entrySet()) {
            run.compare(
                    entry.getKey(),
                    "map_meta_cap",
                    wordpressCaps(disallowed.get(entry.getKey())),
                    String.join(",", entry.getValue().get()));
        }

        List<PhpOracle.Case> others = new ArrayList<>();
        others.add(
                new PhpOracle.Case("roles", OracleJson.MAPPER.createObjectNode().put("op", "roles")));
        // The registrations of WordPress's own post types (wp-includes/post.php), then types of other plugins.
        Map<String, String> blockCaps = Map.of(
                "read", "edit_posts",
                "create_posts", "publish_posts",
                "edit_posts", "edit_posts",
                "edit_published_posts", "edit_published_posts",
                "delete_published_posts", "delete_published_posts",
                "delete_posts", "delete_posts",
                "edit_others_posts", "edit_others_posts",
                "delete_others_posts", "delete_others_posts");
        record TypeArgs(
                String singular,
                String plural,
                boolean mapMetaCap,
                Map<String, String> overrides,
                Optional<PostTypeCapabilities> constant) {}
        List<TypeArgs> typeArgs = List.of(
                new TypeArgs("post", "posts", true, Map.of(), Optional.of(PostTypeCapabilities.POST)),
                new TypeArgs("page", "pages", true, Map.of(), Optional.of(PostTypeCapabilities.PAGE)),
                new TypeArgs(
                        "post",
                        "posts",
                        true,
                        Map.of("create_posts", "upload_files"),
                        Optional.of(PostTypeCapabilities.ATTACHMENT)),
                new TypeArgs("block", "blocks", true, blockCaps, Optional.of(PostTypeCapabilities.BLOCK)),
                new TypeArgs("book", "books", true, Map.of(), Optional.of(BOOK)),
                new TypeArgs("book", "books", false, Map.of(), Optional.empty()),
                new TypeArgs("novel", "novels", false, Map.of(), Optional.of(NOVEL)),
                new TypeArgs("tale", "tales", false, Map.of("edit_page", "edit_tale_page"), Optional.of(TALE)),
                new TypeArgs("story", "stories", true, Map.of(), Optional.empty()),
                new TypeArgs(
                        "person",
                        "persons",
                        true,
                        Map.of("read", "read_person", "edit_posts", "write_people"),
                        Optional.empty()));
        for (int i = 0; i < typeArgs.size(); i++) {
            TypeArgs args = typeArgs.get(i);
            ObjectNode in = OracleJson.MAPPER
                    .createObjectNode()
                    .put("op", "post_type_caps")
                    .put("singular", args.singular())
                    .put("plural", args.plural())
                    .put("map_meta_cap", args.mapMetaCap());
            ObjectNode given = in.putObject("capabilities");
            args.overrides().forEach(given::put);
            others.add(new PhpOracle.Case("type-" + i, in));
        }
        Map<String, JsonNode> rest = oracle.run(DRIVER, others);
        run.compare(
                "roles",
                "role_table",
                rest.get("roles").get("roles").toString(),
                RoleTables.toJson(BuiltInRoles.all()).toString());
        for (int i = 0; i < typeArgs.size(); i++) {
            TypeArgs args = typeArgs.get(i);
            PostTypeCapabilities type = args.constant()
                    .orElseGet(() -> PostTypeCapabilities.of(
                            "t", args.singular(), args.plural(), args.mapMetaCap(), args.overrides()));
            JsonNode wordpress = rest.get("type-" + i).get("caps");
            Map<String, String> mine = capObject(type);
            for (String field : wordpressFields(wordpress, mine)) {
                run.compare("type-" + i, field, text(wordpress.get(field)), String.valueOf(mine.get(field)));
            }
        }

        System.out.println("security caps parity: " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    private void postCases() {
        List<String> postCaps = List.of(
                "edit_post",
                "delete_post",
                "read_post",
                "publish_post",
                "edit_page",
                "delete_page",
                "read_page",
                "edit_block",
                "read_book");
        List<String> statuses = new ArrayList<>(STATUSES.keySet());
        statuses.add("in-review");
        for (boolean multisite : new boolean[] {false, true}) {
            for (long checker : new long[] {0, 7}) {
                for (PostTypeCapabilities type : TYPES) {
                    for (String status : statuses) {
                        for (long author : new long[] {7, 9, 0}) {
                            for (Variant variant : variants(type, status, author)) {
                                for (String cap : postCaps) {
                                    FakeContext context =
                                            multisite ? FakeContext.network(checker) : FakeContext.single(checker);
                                    add(
                                            id(
                                                    kind(type, cap),
                                                    multisite,
                                                    checker,
                                                    type.name(),
                                                    status,
                                                    author,
                                                    variant.label(),
                                                    cap),
                                            cap,
                                            Optional.of(variant.facts()),
                                            context,
                                            variant);
                                }
                            }
                        }
                    }
                }
            }
        }
        List<Optional<PostTypeCapabilities>> special = List.of(
                Optional.of(PostTypeCapabilities.PAGE),
                Optional.of(PostTypeCapabilities.POST),
                Optional.of(NOVEL),
                Optional.of(TALE),
                Optional.empty());
        for (boolean multisite : new boolean[] {false, true}) {
            for (String option : List.of("page_on_front", "page_for_posts", "wp_page_for_privacy_policy")) {
                for (Optional<PostTypeCapabilities> type : special) {
                    for (String status : List.of("draft", "publish")) {
                        for (long author : new long[] {7, 9}) {
                            for (String cap : List.of(
                                    "edit_post",
                                    "delete_post",
                                    "read_post",
                                    "publish_post",
                                    "edit_page",
                                    "delete_page")) {
                                FakeContext context = (multisite ? FakeContext.network(7) : FakeContext.single(7))
                                        .siteOption(option, TextNode.valueOf("10"));
                                PostFacts page = PostFacts.of(
                                        PostId.of(10), type.orElse(PostTypeCapabilities.POST), status, author(author));
                                if (type.isEmpty()) {
                                    page = page.withUnregisteredType();
                                }
                                add(
                                        id(
                                                type.map(t -> kind(t, cap)).orElse("special"),
                                                multisite,
                                                option,
                                                type.map(PostTypeCapabilities::name)
                                                        .orElse("ghost"),
                                                status,
                                                author,
                                                cap),
                                        cap,
                                        Optional.of(page),
                                        context,
                                        new Variant("", page, Optional.empty(), 0));
                            }
                        }
                    }
                }
            }
            for (String cap : List.of("edit_post", "delete_post", "read_post", "publish_post", "edit_page")) {
                FakeContext context = multisite ? FakeContext.network(7) : FakeContext.single(7);
                add(id("none", multisite, cap), cap, Optional.empty(), context);
                add(id("missing", multisite, cap), cap, Optional.of(new MissingObject(99)), context);
                add(
                        id("ghost", multisite, cap),
                        cap,
                        Optional.of(PostFacts.of(PostId.of(10), PostTypeCapabilities.POST, "draft", author(7))
                                .withUnregisteredType()),
                        context);
            }
        }
    }

    /**
     * A page form checked on a type that does not map meta capabilities is either named by the type or not. Unnamed,
     * QuarkPress requires the empty name and WordPress an undefined property (null); the decision is compared too.
     */
    private static String kind(PostTypeCapabilities type, String cap) {
        if (type.mapMetaCap() || !cap.endsWith("_page")) {
            return "post";
        }
        return type.metaName(cap).isEmpty() ? "pageform-unnamed" : "pageform";
    }

    /** The shapes of one post: trashed with each recorded status; an attachment with each kind of parent. */
    private static List<Variant> variants(PostTypeCapabilities type, String status, long author) {
        List<Variant> variants = new ArrayList<>();
        PostId id = PostId.of(10);
        PostId parentId = PostId.of(11);
        Optional<UserId> by = author(author);
        if (status.equals("trash")) {
            for (String before : List.of("", "publish", "future", "draft", "pending", "private")) {
                PostFacts facts = PostFacts.of(id, type, status, by);
                if (!before.isEmpty()) {
                    facts = facts.withStatusBeforeTrash(before);
                }
                variants.add(new Variant("before=" + before, facts, Optional.empty(), 0));
            }
        } else if (type == PostTypeCapabilities.ATTACHMENT && status.equals("inherit")) {
            variants.add(new Variant(
                    "unattached", PostFacts.ofAttachment(id, status, by, Optional.empty()), Optional.empty(), 0));
            PostFacts self = PostFacts.of(id, PostTypeCapabilities.POST, "publish", author(7));
            variants.add(new Variant(
                    "self", PostFacts.ofAttachment(id, status, by, Optional.of(self)), Optional.empty(), 10));
            variants.add(new Variant(
                    "missing-parent", PostFacts.ofAttachment(id, status, by, Optional.empty()), Optional.empty(), 99));
            for (String parentStatus :
                    List.of("publish", "future", "draft", "pending", "private", "auto-draft", "in-review")) {
                PostFacts parent = PostFacts.of(parentId, PostTypeCapabilities.POST, parentStatus, author(7));
                variants.add(new Variant(
                        "parent=" + parentStatus,
                        PostFacts.ofAttachment(id, status, by, Optional.of(parent)),
                        Optional.of(parent),
                        11));
            }
            for (String before : List.of("", "publish", "future", "private", "draft", "in-review")) {
                PostFacts parent = PostFacts.of(parentId, PostTypeCapabilities.POST, "trash", author(7));
                if (!before.isEmpty()) {
                    parent = parent.withStatusBeforeTrash(before);
                }
                variants.add(new Variant(
                        "parent=trash/" + before,
                        PostFacts.ofAttachment(id, status, by, Optional.of(parent)),
                        Optional.of(parent),
                        11));
            }
        } else if (type == PostTypeCapabilities.ATTACHMENT) {
            variants.add(
                    new Variant("", PostFacts.ofAttachment(id, status, by, Optional.empty()), Optional.empty(), 0));
        } else {
            variants.add(new Variant("", PostFacts.of(id, type, status, by), Optional.empty(), 0));
        }
        return variants;
    }

    private void userCases() {
        List<String> userCaps = List.of(
                "edit_user",
                "edit_users",
                "promote_user",
                "add_users",
                "remove_user",
                "delete_user",
                "delete_users",
                "create_users",
                "create_app_password",
                "list_app_passwords");
        List<Optional<CapObject>> targets = List.of(Optional.empty(), target(7), target(5), target(9));
        for (boolean multisite : new boolean[] {false, true}) {
            for (int profile = 0; profile < 4; profile++) {
                for (String addNewUsers : List.of("", "1", "0")) {
                    for (int t = 0; t < targets.size(); t++) {
                        for (String cap : userCaps) {
                            if (!addNewUsers.isEmpty() && !cap.equals("create_users")) {
                                continue;
                            }
                            FakeContext context = checker(multisite, profile);
                            context.superAdmins.add(9L);
                            if (!addNewUsers.isEmpty()) {
                                context.networkOption("add_new_users", TextNode.valueOf(addNewUsers));
                            }
                            add(id("user", multisite, profile, addNewUsers, t, cap), cap, targets.get(t), context);
                        }
                    }
                }
            }
        }
    }

    private void termCases() {
        List<Optional<CapObject>> terms = new ArrayList<>();
        for (int i = 0; i < TAXONOMIES.size(); i++) {
            terms.add(Optional.of(TermFacts.of(TermId.of(3 + i), TAXONOMIES.get(i))));
        }
        terms.add(Optional.of(new TermFacts(TermId.of(8), Optional.empty())));
        terms.add(Optional.of(new MissingObject(98)));
        terms.add(Optional.empty());
        for (boolean multisite : new boolean[] {false, true}) {
            for (int defaults = 0; defaults < 3; defaults++) {
                for (int t = 0; t < terms.size(); t++) {
                    for (String cap : List.of("edit_term", "delete_term", "assign_term")) {
                        FakeContext context = multisite ? FakeContext.network(7).superAdmin() : FakeContext.single(7);
                        if (defaults == 1) {
                            context.siteOption("default_category", IntNode.valueOf(3));
                        } else if (defaults == 2) {
                            context.siteOption("default_term_genre", TextNode.valueOf("5"));
                        }
                        add(id("term", multisite, defaults, t, cap), cap, terms.get(t), context);
                    }
                }
            }
        }
    }

    private void otherCases() {
        List<String> names = List.of(
                "unfiltered_upload",
                "edit_css",
                "unfiltered_html",
                "edit_files",
                "edit_plugins",
                "edit_themes",
                "update_plugins",
                "delete_plugins",
                "install_plugins",
                "upload_plugins",
                "update_themes",
                "delete_themes",
                "install_themes",
                "upload_themes",
                "update_core",
                "install_languages",
                "update_languages",
                "activate_plugins",
                "deactivate_plugins",
                "activate_plugin",
                "deactivate_plugin",
                "resume_plugin",
                "resume_theme",
                "manage_links",
                "customize",
                "delete_site",
                "manage_post_tags",
                "edit_categories",
                "edit_post_tags",
                "delete_categories",
                "delete_post_tags",
                "assign_categories",
                "assign_post_tags",
                "create_sites",
                "delete_sites",
                "manage_network",
                "manage_sites",
                "manage_network_users",
                "manage_network_plugins",
                "manage_network_themes",
                "manage_network_options",
                "upgrade_network",
                "setup_network",
                "update_php",
                "update_https",
                "export_others_personal_data",
                "erase_others_personal_data",
                "manage_privacy_options",
                "edit_blocks",
                "edit_others_blocks",
                "publish_blocks",
                "read_private_blocks",
                "delete_blocks",
                "delete_private_blocks",
                "delete_published_blocks",
                "delete_others_blocks",
                "edit_private_blocks",
                "edit_published_blocks",
                "manage_site_options",
                "manage_options",
                "read",
                "exist",
                "do_not_allow",
                "frobnicate",
                "Edit_Posts",
                "editor",
                "level_8");
        for (boolean multisite : new boolean[] {false, true}) {
            for (int profile = 0; profile < 4; profile++) {
                for (int option = 0; option < 3; option++) {
                    for (String cap : names) {
                        FakeContext context = checker(multisite, profile);
                        if (option == 1) {
                            context.siteOption("link_manager_enabled", IntNode.valueOf(1));
                        } else if (option == 2) {
                            context.networkOption(
                                    "menu_items",
                                    OracleJson.MAPPER.createObjectNode().put("plugins", "1"));
                        }
                        add(id("other", multisite, profile, option, cap), cap, Optional.empty(), context);
                    }
                }
            }
        }
    }

    /** Checkers: 0 logged out, 1 a user, 2 a user who can manage network users, 3 a super admin. */
    private static FakeContext checker(boolean multisite, int profile) {
        FakeContext context =
                multisite ? FakeContext.network(profile == 0 ? 0 : 7) : FakeContext.single(profile == 0 ? 0 : 7);
        if (profile == 2) {
            context.holding("manage_network_users");
        } else if (profile == 3) {
            context.superAdmin();
        }
        return context;
    }

    private void add(String id, String capability, Optional<CapObject> object, FakeContext context) {
        add(id, capability, object, context, null);
    }

    private void add(String id, String capability, Optional<CapObject> object, FakeContext context, Variant variant) {
        cases.add(new PhpOracle.Case(id, input(capability, object, context, variant)));
        contexts.put(id, context);
        ours.put(id, () -> caps.map(capability, object, context));
    }

    private static ObjectNode input(
            String capability, Optional<CapObject> object, FakeContext context, Variant variant) {
        ObjectNode in =
                OracleJson.MAPPER.createObjectNode().put("op", "map_meta_cap").put("cap", capability);
        in.put("user", context.user().map(UserId::value).orElse(0L));
        in.put("multisite", context.multisite());
        ArrayNode superAdmins = in.putArray("super_admins");
        context.superAdmins.forEach(superAdmins::add);
        ArrayNode userCan = in.putArray("user_can");
        if (context.userCan("manage_network_users")) {
            userCan.add("manage_network_users");
        }
        ObjectNode options = in.putObject("options");
        context.siteOptions.forEach(options::set);
        ObjectNode network = in.putObject("network_options");
        context.networkOptions.forEach(network::set);
        ObjectNode metaCaps = in.putObject("meta_caps");
        metaCaps.put("edit_block", "edit_post").put("read_block", "read_post").put("delete_block", "delete_post");
        metaCaps.put("edit_book", "edit_post").put("read_book", "read_post").put("delete_book", "delete_post");
        ObjectNode types = in.putObject("post_types");
        for (PostTypeCapabilities type : TYPES) {
            ObjectNode entry = types.putObject(type.name());
            entry.put("map_meta_cap", type.mapMetaCap());
            ObjectNode cap = entry.putObject("cap");
            capObject(type).forEach((name, value) -> {
                if (value != null) {
                    cap.put(name, value);
                }
            });
        }
        ObjectNode statuses = in.putObject("statuses");
        STATUSES.forEach((name, visibility) -> statuses.putObject(name)
                .put("public", visibility == PostVisibility.PUBLIC)
                .put("private", visibility == PostVisibility.PRIVATE));
        ObjectNode taxonomies = in.putObject("taxonomies");
        for (TaxonomyCapabilities taxonomy : TAXONOMIES) {
            taxonomies
                    .putObject(taxonomy.name())
                    .put("manage_terms", taxonomy.manageTerms())
                    .put("edit_terms", taxonomy.editTerms())
                    .put("delete_terms", taxonomy.deleteTerms())
                    .put("assign_terms", taxonomy.assignTerms());
        }
        ArrayNode args = in.putArray("args");
        if (object.isPresent()) {
            switch (object.get()) {
                case PostFacts post -> {
                    args.add(post.id().value());
                    ArrayNode posts = in.putArray("posts");
                    posts.add(postNode(post, variant == null ? 0 : variant.postParent()));
                    if (variant != null && variant.parent().isPresent()) {
                        posts.add(postNode(variant.parent().get(), 0));
                    }
                }
                case TermFacts term -> {
                    args.add(term.id().value());
                    in.putObject("term")
                            .put("term_id", term.id().value())
                            .put(
                                    "taxonomy",
                                    term.taxonomy()
                                            .map(TaxonomyCapabilities::name)
                                            .orElse("ghost"));
                }
                case UserTarget user -> args.add(user.user().value());
                case MissingObject missing -> args.add(missing.id());
            }
        }
        return in;
    }

    private static ObjectNode postNode(PostFacts post, long parent) {
        return OracleJson.MAPPER
                .createObjectNode()
                .put("ID", post.id().value())
                .put("post_type", post.type().map(PostTypeCapabilities::name).orElse("ghost"))
                .put("post_status", post.status())
                .put("post_author", post.author().map(UserId::value).orElse(0L))
                .put("post_parent", parent)
                .put("trash_status", post.statusBeforeTrash().orElse(""));
    }

    /** A post type's cap object as WordPress names its entries. */
    private static Map<String, String> capObject(PostTypeCapabilities type) {
        Map<String, String> cap = new LinkedHashMap<>();
        cap.put("edit_post", type.editPost());
        cap.put("read_post", type.readPost());
        cap.put("delete_post", type.deletePost());
        cap.put("edit_posts", type.editPosts());
        cap.put("edit_others_posts", type.editOthersPosts());
        cap.put("delete_posts", type.deletePosts());
        cap.put("publish_posts", type.publishPosts());
        cap.put("read_private_posts", type.readPrivatePosts());
        cap.put("read", type.read());
        cap.put("delete_private_posts", type.deletePrivatePosts());
        cap.put("delete_published_posts", type.deletePublishedPosts());
        cap.put("delete_others_posts", type.deleteOthersPosts());
        cap.put("edit_private_posts", type.editPrivatePosts());
        cap.put("edit_published_posts", type.editPublishedPosts());
        cap.put("create_posts", type.createPosts());
        cap.put("edit_page", type.editPage());
        cap.put("read_page", type.readPage());
        cap.put("delete_page", type.deletePage());
        return cap;
    }

    private static List<String> wordpressFields(JsonNode wordpress, Map<String, String> mine) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(mine.keySet());
        wordpress.fieldNames().forEachRemaining(fields::add);
        return new ArrayList<>(fields);
    }

    /**
     * Whether a user is granted a check that requires these capabilities: refused when do_not_allow is required, always
     * granted to a network administrator in a network, otherwise only when every required name is a real name the user
     * holds (null and the empty name are held by no one).
     */
    private static boolean granted(Set<String> required, Set<String> held, boolean networkAdmin) {
        if (required.contains("do_not_allow")) {
            return false;
        }
        if (networkAdmin) {
            return true;
        }
        return required.stream().allMatch(name -> !name.isEmpty() && held.contains(name));
    }

    /** Compares granted or refused for users who hold nothing, the same-named capability, or every real name. */
    private void compareDecisions(ParityRun run, String id, JsonNode wordpress, Set<String> mine) {
        FakeContext context = contexts.get(id);
        boolean networkAdmin = context.multisite() && context.isSuperAdmin();
        Set<String> required = new LinkedHashSet<>();
        wordpress.get("caps").forEach(cap -> required.add(cap.isNull() ? "" : cap.asText()));
        String sameNamed = id.split("\\|")[id.split("\\|").length - 1];
        Set<String> everyName = new LinkedHashSet<>(required);
        everyName.addAll(mine);
        everyName.add(sameNamed);
        everyName.addAll(
                capObject(TYPES.stream()
                                .filter(t -> id.contains("|" + t.name() + "|"))
                                .findFirst()
                                .orElseThrow())
                        .values()
                        .stream()
                        .filter(java.util.Objects::nonNull)
                        .toList());
        Map<String, Set<String>> holders = new LinkedHashMap<>();
        holders.put("holding-nothing", Set.of());
        holders.put("holding-same-name", Set.of(sameNamed));
        holders.put("holding-every-name", everyName);
        holders.forEach((label, held) -> run.compare(
                id, "decision-" + label, granted(required, held, networkAdmin), granted(mine, held, networkAdmin)));
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? "null" : value.asText();
    }

    private static Optional<UserId> author(long id) {
        return id == 0 ? Optional.empty() : Optional.of(UserId.of(id));
    }

    private static Optional<CapObject> target(long id) {
        return Optional.of(new UserTarget(UserId.of(id)));
    }

    private static String wordpressCaps(JsonNode out) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        out.get("caps").forEach(cap -> required.add(cap.isNull() ? "null" : cap.asText()));
        return String.join(",", required);
    }

    private static String id(Object... parts) {
        StringBuilder id = new StringBuilder();
        for (Object part : parts) {
            if (id.length() > 0) {
                id.append('|');
            }
            id.append(part);
        }
        return id.toString();
    }
}
