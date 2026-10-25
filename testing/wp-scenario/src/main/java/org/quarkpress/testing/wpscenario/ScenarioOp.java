package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One operation of a scenario. Sites are referenced by creation order: 0 is the main site, k the k-th created. */
public sealed interface ScenarioOp {

    /** The operation's name in the scenario format. */
    String kind();

    /**
     * A site created as the network administrator creates one, with an explicit address; an empty title means "no
     * title" (WordPress then names the site {@code Site <id>}).
     */
    record CreateSite(String domain, String path, String title) implements ScenarioOp {
        public CreateSite {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(title, "title");
            ScenarioRules.requireDomain(domain);
            ScenarioRules.requireSitePath(path);
            ScenarioRules.requireTitle(title);
        }

        @Override
        public String kind() {
            return "create_site";
        }
    }

    /** A request's site, looked up from its host and raw path. */
    record Lookup(String host, String path) implements ScenarioOp {
        public Lookup {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(path, "path");
            ScenarioRules.requireHost(host);
            ScenarioRules.requireLookupPath(path);
        }

        @Override
        public String kind() {
            return "lookup";
        }
    }

    /** One status flag (or the public value, true meaning 1) of a created site. */
    record SetFlag(int site, Flag flag, boolean value) implements ScenarioOp {
        public SetFlag {
            Objects.requireNonNull(flag, "flag");
            if (site < 1) {
                throw new IllegalArgumentException("a scenario never changes the main site's flags: QuarkPress refuses"
                        + " it and WordPress's API does not");
            }
        }

        @Override
        public String kind() {
            return "set_flag";
        }
    }

    /** Default options of a site. */
    record ReadOptions(int site, List<String> names) implements ScenarioOp {
        public ReadOptions {
            names = List.copyOf(names);
            if (site < 0) {
                throw new IllegalArgumentException("a scenario site reference is 0 or more: " + site);
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("read_options names at least one option");
            }
            names.forEach(ScenarioRules::requireOption);
        }

        @Override
        public String kind() {
            return "read_options";
        }
    }

    /**
     * A user registering with {@code username} and, when not empty, given and family names (WordPress's first and last
     * name). On both sides a new user is a member of no site.
     */
    record CreateUser(String username, String givenName, String familyName) implements ScenarioOp {
        public CreateUser {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(givenName, "givenName");
            Objects.requireNonNull(familyName, "familyName");
            ScenarioRules.requireName(givenName);
            ScenarioRules.requireName(familyName);
            ScenarioRules.requireUsername(username, givenName.isEmpty() && familyName.isEmpty());
        }

        @Override
        public String kind() {
            return "create_user";
        }
    }

    /** A user added to a site with a role (empty: a member without role). */
    record AddUser(int user, int site, String role) implements ScenarioOp {
        public AddUser {
            Objects.requireNonNull(role, "role");
            ScenarioRules.requireKey(role, true);
        }

        @Override
        public String kind() {
            return "add_user";
        }
    }

    /** A user removed from a site. */
    record RemoveUser(int user, int site) implements ScenarioOp {
        @Override
        public String kind() {
            return "remove_user";
        }
    }

    /** A user's roles on a site replaced by one role (empty: none). */
    record SetRole(int user, int site, String role) implements ScenarioOp {
        public SetRole {
            Objects.requireNonNull(role, "role");
            ScenarioRules.requireKey(role, true);
        }

        @Override
        public String kind() {
            return "set_role";
        }
    }

    /** An individual capability of a user on a site (false: a denial). */
    record AddCap(int user, int site, String capability, boolean grant) implements ScenarioOp {
        public AddCap {
            Objects.requireNonNull(capability, "capability");
            ScenarioRules.requireKey(capability, false);
        }

        @Override
        public String kind() {
            return "add_cap";
        }
    }

    /** A user made a super admin, or no longer one. */
    record SuperAdmin(int user, boolean grant) implements ScenarioOp {
        @Override
        public String kind() {
            return "super_admin";
        }
    }

    /** A network option: {@code add_new_users}, or {@code menu_plugins} (the plugins entry of menu_items). */
    record NetworkFlag(String name, boolean value) implements ScenarioOp {
        public NetworkFlag {
            Objects.requireNonNull(name, "name");
            ScenarioRules.requireNetworkFlag(name);
        }

        @Override
        public String kind() {
            return "network_flag";
        }
    }

    /** A site option that capability rules read: {@code link_manager_enabled}. */
    record SiteSetting(int site, String name, boolean value) implements ScenarioOp {
        public SiteSetting {
            Objects.requireNonNull(name, "name");
            ScenarioRules.requireSiteSetting(name);
        }

        @Override
        public String kind() {
            return "site_setting";
        }
    }

    /** A role added to a site's table, granting the listed capabilities. */
    record AddRole(int site, String role, String name, List<String> capabilities) implements ScenarioOp {
        public AddRole {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(name, "name");
            capabilities = List.copyOf(capabilities);
            ScenarioRules.requireKey(role, true);
            ScenarioRules.requireName(name);
            capabilities.forEach(capability -> ScenarioRules.requireKey(capability, false));
        }

        @Override
        public String kind() {
            return "add_role";
        }
    }

    /** A capability of a site's role set (false: a denial). */
    record RoleCap(int site, String role, String capability, boolean grant) implements ScenarioOp {
        public RoleCap {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(capability, "capability");
            ScenarioRules.requireKey(role, false);
            ScenarioRules.requireKey(capability, false);
        }

        @Override
        public String kind() {
            return "role_cap";
        }
    }

    /** A role removed from a site's table. */
    record RemoveRole(int site, String role) implements ScenarioOp {
        public RemoveRole {
            Objects.requireNonNull(role, "role");
            ScenarioRules.requireKey(role, false);
        }

        @Override
        public String kind() {
            return "remove_role";
        }
    }

    /**
     * Whether a user ({@code 0}: a logged-out visitor) has a capability on a site, checked against another user when
     * {@code target} is not 0.
     */
    record Can(int user, int site, String capability, int target) implements ScenarioOp {
        public Can {
            Objects.requireNonNull(capability, "capability");
            ScenarioRules.requireKey(capability, false);
            if (user < 0 || target < 0) {
                throw new IllegalArgumentException("a scenario user reference is 0 or more");
            }
        }

        @Override
        public String kind() {
            return "can";
        }
    }

    /**
     * An operation on posts of the main site, run as a user: {@code as} is a created user's index, or 0 for WordPress's
     * user 0, which QuarkPress plays with its system identity. Posts count from 1 in insertion order. A scenario
     * performs only what WordPress's REST layer would allow the acting user; the format does not check capabilities.
     */
    sealed interface PostOp extends ScenarioOp {}

    /** A main-site option the post rules read. */
    record SiteOption(String name, String value) implements PostOp {
        public SiteOption {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            ScenarioRules.requirePostOption(name);
            ScenarioRules.requirePostText(value);
        }

        @Override
        public String kind() {
            return "site_option";
        }
    }

    /** {@code page_on_front} or {@code page_for_posts} set to a post (0: none). */
    record FrontPage(String option, int post) implements PostOp {
        public FrontPage {
            Objects.requireNonNull(option, "option");
            if (!option.equals("page_on_front") && !option.equals("page_for_posts")) {
                throw new IllegalArgumentException("a scenario front page option is page_on_front or page_for_posts");
            }
            ScenarioRules.requireReference(post);
        }

        @Override
        public String kind() {
            return "front_page";
        }
    }

    record InsertPost(int as, PostFields fields) implements PostOp {
        public InsertPost {
            Objects.requireNonNull(fields, "fields");
            ScenarioRules.requireReference(as);
        }

        @Override
        public String kind() {
            return "insert_post";
        }
    }

    /**
     * An update of the given fields. {@code editDate} is WordPress's {@code edit_date} flag: without it, an update that
     * leaves a post a draft without a GMT date gives it the current date.
     */
    record UpdatePost(int as, int post, PostFields fields, boolean editDate) implements PostOp {
        public UpdatePost {
            Objects.requireNonNull(fields, "fields");
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "update_post";
        }
    }

    record PublishPost(int post) implements PostOp {
        public PublishPost {
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "publish_post";
        }
    }

    record TrashPost(int as, int post) implements PostOp {
        public TrashPost {
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "trash_post";
        }
    }

    record UntrashPost(int as, int post) implements PostOp {
        public UntrashPost {
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "untrash_post";
        }
    }

    record DeletePost(int as, int post, boolean force) implements PostOp {
        public DeletePost {
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "delete_post";
        }
    }

    record Stick(int post, boolean sticky) implements PostOp {
        public Stick {
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "stick";
        }
    }

    record QueryPosts(int as, QueryArgs args) implements PostOp {
        public QueryPosts {
            Objects.requireNonNull(args, "args");
            ScenarioRules.requireReference(as);
        }

        @Override
        public String kind() {
            return "query_posts";
        }
    }

    /** A page's path of slugs, as WordPress's {@code get_page_uri} builds it. */
    record PageUri(int post) implements PostOp {
        public PageUri {
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "page_uri";
        }
    }

    /** A post's record at this point of the scenario. */
    record ReadPost(int post) implements PostOp {
        public ReadPost {
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "read_post";
        }
    }

    /**
     * A post meta key set on a post by a user, written alike on both sides: the revisioned key {@code footnotes}, so
     * the meta rules of revisions are reachable, or {@code _wp_trash_meta_time} (Unix seconds), so a trashed post can
     * be made overdue for the trash purge.
     */
    record UpdatePostMeta(int as, int post, String key, String value) implements PostOp {
        public UpdatePostMeta {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
            ScenarioRules.requirePostMetaKey(key);
            if (key.equals("_wp_trash_meta_time")) {
                ScenarioRules.requireUnixSeconds(value);
            }
            ScenarioRules.requirePostText(value);
        }

        @Override
        public String kind() {
            return "update_post_meta";
        }
    }

    /**
     * The scheduling operations: what the scheduled jobs do when they run, invoked directly on the main site as cron
     * would (user 0), and a fixture that moves a post's dates. Purges answer with the inserted posts that survived.
     */
    sealed interface SchedulingOp extends PostOp {}

    /** The publication check of one scheduled post: published when its GMT date has passed, left alone otherwise. */
    record RunPublishCheck(int post) implements SchedulingOp {
        public RunPublishCheck {
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "run_publish_check";
        }
    }

    /** The trash purge: trashed posts whose trash time is older than the trash retention are deleted. */
    record RunTrashPurge() implements SchedulingOp {
        @Override
        public String kind() {
            return "run_trash_purge";
        }
    }

    /** The auto-draft purge: auto-drafts older than seven days are deleted. */
    record RunAutoDraftPurge() implements SchedulingOp {
        @Override
        public String kind() {
            return "run_auto_draft_purge";
        }
    }

    /**
     * A test fixture, not WordPress behaviour: a direct row write of the post's dates on both sides ({@code gmt}, a
     * {@code Y-m-d H:i:s} UTC text, and the site-local date it converts to), bypassing every rule an update applies.
     * It is the only way to make a scheduled post overdue when neither side's clock moves.
     */
    record ForceDate(int post, String gmt) implements SchedulingOp {
        public ForceDate {
            Objects.requireNonNull(gmt, "gmt");
            ScenarioRules.requirePost(post);
            ScenarioRules.requirePostDate(gmt);
        }

        @Override
        public String kind() {
            return "force_date";
        }
    }

    /**
     * Operations on revisions and autosaves, on the main site, naming revisions {@code r<k>} by creation order of
     * revision rows during the scenario (regular revisions and new autosaves; deleted ones still counted). A
     * reference beyond the rows created so far is {@code missing} on both sides, so the format does not bound it.
     */
    sealed interface RevisionOp extends PostOp {}

    /** An autosave through the REST autosaves endpoint, with the given fields and revisioned meta. */
    record Autosave(int as, int post, AutosaveFields fields, Optional<Map<String, String>> meta) implements RevisionOp {
        public Autosave {
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(meta, "meta");
            ScenarioRules.requireAutosaveUser(as);
            ScenarioRules.requirePost(post);
            meta = meta.map(Map::copyOf);
            meta.ifPresent(values -> values.forEach((key, value) -> {
                ScenarioRules.requireRevisionMetaKey(key);
                ScenarioRules.requirePostText(value);
            }));
        }

        @Override
        public String kind() {
            return "autosave";
        }
    }

    /** A restore from a revision as the revisions screen does it; no fields restores all three. */
    record Restore(int as, String revision, List<String> fields) implements RevisionOp {
        public Restore {
            Objects.requireNonNull(revision, "revision");
            fields = List.copyOf(fields);
            ScenarioRules.requireAutosaveUser(as);
            ScenarioRules.requireRevisionRef(revision);
            for (int i = 0; i < fields.size(); i++) {
                ScenarioRules.requireRevisionField(fields.get(i));
                if (fields.indexOf(fields.get(i)) != i) {
                    throw new IllegalArgumentException("a scenario restores a field once: " + fields.get(i));
                }
            }
        }

        @Override
        public String kind() {
            return "restore";
        }
    }

    /** A revision deleted, as the system. */
    record DeleteRevision(String revision) implements RevisionOp {
        public DeleteRevision {
            Objects.requireNonNull(revision, "revision");
            ScenarioRules.requireRevisionRef(revision);
        }

        @Override
        public String kind() {
            return "delete_revision";
        }
    }

    /** A post's revisions listed; user 0 reads without a check. */
    record ListRevisions(int as, int post, RevisionListArgs args) implements RevisionOp {
        public ListRevisions {
            Objects.requireNonNull(args, "args");
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "revisions";
        }
    }

    /** A post's autosave by a user (0: any). */
    record AutosaveOf(int as, int post, int user) implements RevisionOp {
        public AutosaveOf {
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
            ScenarioRules.requireReference(user);
        }

        @Override
        public String kind() {
            return "autosave_of";
        }
    }

    /** A post's latest revision and its number of revisions. */
    record CountRevisions(int as, int post) implements RevisionOp {
        public CountRevisions {
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
        }

        @Override
        public String kind() {
            return "revision_count";
        }
    }

    /**
     * An operation on the main site's terms (a post operation: it names users and posts the same way). Terms are named
     * {@code t<k>}, the k-th term created on the site during the scenario (by insert_term or by a name in
     * set_post_terms), or {@code default}, the taxonomy's default term; {@code none} is no parent. A scenario performs
     * only what WordPress's REST layer would allow the acting user.
     */
    sealed interface TermOp extends PostOp {}

    record InsertTerm(int as, String taxonomy, TermFields fields) implements TermOp {
        public InsertTerm {
            Objects.requireNonNull(taxonomy, "taxonomy");
            Objects.requireNonNull(fields, "fields");
            ScenarioRules.requireReference(as);
            ScenarioRules.requireTermTaxonomy(taxonomy);
            if (fields.name().isEmpty()) {
                throw new IllegalArgumentException("insert_term gives a name");
            }
            ScenarioRules.requireTermParent(taxonomy, fields.parent());
        }

        @Override
        public String kind() {
            return "insert_term";
        }
    }

    record UpdateTerm(int as, String term, String taxonomy, TermFields fields) implements TermOp {
        public UpdateTerm {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(taxonomy, "taxonomy");
            Objects.requireNonNull(fields, "fields");
            ScenarioRules.requireReference(as);
            ScenarioRules.requireTermRef(term, false);
            ScenarioRules.requireTermTaxonomy(taxonomy);
            ScenarioRules.requireDefaultIn(taxonomy, term);
            ScenarioRules.requireTermParent(taxonomy, fields.parent());
            if (term.equals("default")
                    && taxonomy.equals("genre")
                    && fields.name().isPresent()
                    && fields.slug().isPresent()) {
                throw new IllegalArgumentException("a scenario does not change both the name and the slug of genre's"
                        + " default term: WordPress registers genre on every request and would then make a new"
                        + " default term, which the driver, registering it once per run, does not");
            }
        }

        @Override
        public String kind() {
            return "update_term";
        }
    }

    record DeleteTerm(int as, String term, String taxonomy) implements TermOp {
        public DeleteTerm {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(taxonomy, "taxonomy");
            ScenarioRules.requireReference(as);
            ScenarioRules.requireTermRef(term, false);
            ScenarioRules.requireTermTaxonomy(taxonomy);
            ScenarioRules.requireDefaultIn(taxonomy, term);
        }

        @Override
        public String kind() {
            return "delete_term";
        }
    }

    record SetPostTerms(int as, int post, String taxonomy, List<TermItem> terms, boolean append) implements TermOp {
        public SetPostTerms {
            Objects.requireNonNull(taxonomy, "taxonomy");
            terms = List.copyOf(terms);
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
            ScenarioRules.requireTermTaxonomy(taxonomy);
            terms.forEach(item -> item.term().ifPresent(ref -> ScenarioRules.requireDefaultIn(taxonomy, ref)));
        }

        @Override
        public String kind() {
            return "set_post_terms";
        }
    }

    record RemovePostTerms(int as, int post, String taxonomy, List<TermItem> terms) implements TermOp {
        public RemovePostTerms {
            Objects.requireNonNull(taxonomy, "taxonomy");
            terms = List.copyOf(terms);
            ScenarioRules.requireReference(as);
            ScenarioRules.requirePost(post);
            ScenarioRules.requireTermTaxonomy(taxonomy);
            terms.forEach(item -> item.term().ifPresent(ref -> ScenarioRules.requireDefaultIn(taxonomy, ref)));
        }

        @Override
        public String kind() {
            return "remove_post_terms";
        }
    }

    /** A post's terms of a taxonomy, in WordPress's order (by name). */
    record PostTerms(int post, String taxonomy) implements TermOp {
        public PostTerms {
            Objects.requireNonNull(taxonomy, "taxonomy");
            ScenarioRules.requirePost(post);
            ScenarioRules.requireTermTaxonomy(taxonomy);
        }

        @Override
        public String kind() {
            return "post_terms";
        }
    }

    record QueryTerms(int as, TermQueryArgs args) implements TermOp {
        public QueryTerms {
            Objects.requireNonNull(args, "args");
            ScenarioRules.requireReference(as);
        }

        @Override
        public String kind() {
            return "query_terms";
        }
    }

    /**
     * A term by reference; an empty taxonomy looks it up in every taxonomy ({@code default} then names the default
     * category).
     */
    record GetTerm(String term, String taxonomy) implements TermOp {
        public GetTerm {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(taxonomy, "taxonomy");
            ScenarioRules.requireTermRef(term, false);
            if (!taxonomy.isEmpty()) {
                ScenarioRules.requireTermTaxonomy(taxonomy);
                ScenarioRules.requireDefaultIn(taxonomy, term);
            }
        }

        @Override
        public String kind() {
            return "get_term";
        }
    }

    /** WordPress's term_exists for text, optionally under a parent ({@code none}: the top level). */
    record TermExists(String taxonomy, String value, Optional<String> parent) implements TermOp {
        public TermExists {
            Objects.requireNonNull(taxonomy, "taxonomy");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(parent, "parent");
            ScenarioRules.requireTermTaxonomy(taxonomy);
            ScenarioRules.requireTermName(value);
            parent.ifPresent(ref -> ScenarioRules.requireTermRef(ref, true));
            parent.ifPresent(ref -> ScenarioRules.requireDefaultIn(taxonomy, ref));
        }

        @Override
        public String kind() {
            return "term_exists";
        }
    }

    /** The site fields a scenario may set. */
    enum Flag {
        ARCHIVED,
        MATURE,
        SPAM,
        DELETED,
        PUBLIC;

        /** The column name in WordPress's sites table. */
        public String wordpressName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Flag named(String name) {
            for (Flag flag : values()) {
                if (flag.wordpressName().equals(name)) {
                    return flag;
                }
            }
            throw new IllegalArgumentException("unknown site flag '" + name + "'");
        }
    }
}
