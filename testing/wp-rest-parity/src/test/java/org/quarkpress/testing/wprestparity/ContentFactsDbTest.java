package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * Records what WordPress answers for every item of the posts reference's Part G ({@code g01}-{@code g22}) and the
 * revisions reference's Part F ({@code f01}-{@code f20}), the {@code post_format} registration ({@code x-post-format}),
 * the {@code book} type ({@code x-book-type}) and the OPTIONS bodies of the content routes, asserting only the six
 * items the references state firmly. Every item creates its own fixtures (names unique to the item), deletes what it
 * created and restores any site state it changes. Count-sensitive items run on a subdirectory site of their own; the
 * lock, preview and new-post items run on the main site, whose {@code wp-admin} is served.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
class ContentFactsDbTest {
    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;

    /** The item the running test records under; null when it records nothing. */
    String item;

    /** A login session: the two cookies as one {@code Cookie} header value, the REST nonce and the session token. */
    record Session(String user, String cookie, String nonce, String token) {}

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        site = WordPressRestSite.start(
                WordPressTree.ensure(System.getProperty("wp.version")), database, System.getProperty("wp.php", "php"));
        client = new RestClient(site.root(), RestClient.applicationPasswords(site));
        // Harness rule: the main site holds the custom type book (qp-parity-mu.php's switch) for the whole run.
        site.option(BOOK_SWITCH, TextNode.valueOf("1"));
    }

    /** The per-site option that makes qp-parity-mu.php register {@code book}. */
    static final String BOOK_SWITCH = "qp_parity_book_type";

    @AfterAll
    static void stop() {
        try {
            VerificationFacts.save();
        } finally {
            if (site != null) {
                site.close();
            }
            if (database != null) {
                database.close();
            }
        }
    }

    @BeforeEach
    void name(TestInfo info) {
        String method = info.getTestMethod().orElseThrow().getName();
        if (method.startsWith("xPostFormat")) {
            item = "x-post-format";
        } else if (method.startsWith("xBookType")) {
            item = "x-book-type";
        } else if ((method.startsWith("g") || method.startsWith("f")) && Character.isDigit(method.charAt(1))) {
            item = method.substring(0, 3);
        } else {
            item = null;
        }
    }

    // ---- Part F: revisions, autosaves, locks, previews --------------------------------------------------------

    @Test
    void f01RevisionsPagingWithoutPerPage() throws Exception { // RV6 rules 1, 3
        Long post = null;
        try {
            post = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"f01 post\",\"status\":\"publish\",\"content\":\"f01 v1\"}");
            revise(post, 3, "f01 v");
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?offset=1"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?offset=99"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?page=2"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?per_page=2"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?per_page=2&page=3"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?per_page=2&offset=1"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f02RevisionLookupsAre404() throws Exception { // RV2
        Exchange missing = send(RestCall.get("alice", "/wp-json/wp/v2/posts/99999/revisions"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().get("code").asText()).isEqualTo("rest_post_invalid_parent");
        assertThat(missing.json().get("message").asText()).isEqualTo("Invalid post parent ID.");
        Long post = null;
        Long other = null;
        Long page = null;
        try {
            post = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"f02 post\",\"status\":\"publish\",\"content\":\"f02 v1\"}");
            revise(post, 2, "f02 v");
            other = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"f02 other\",\"status\":\"publish\",\"content\":\"f02 o1\"}");
            revise(other, 1, "f02 o");
            page = createPost("alice", "/wp/v2/pages", "{\"title\":\"f02 page\",\"status\":\"publish\"}");
            long otherRevision = firstRevisionId(other);
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/99999/revisions"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + page + "/revisions"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions/" + post));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions/" + otherRevision));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/abc/revisions"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?per_page=1&page=2&offset=0"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?per_page=1&offset=-1"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?offset=-1"));
            send(RestCall.get("dave", "/wp-json/wp/v2/posts/" + post + "/revisions"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + post + "/revisions"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + post + "/revisions?per_page=0"));
        } finally {
            deletePostIfCreated("/wp/v2/pages", page);
            deletePostIfCreated("/wp/v2/posts", other);
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f03RevisionOrdering() throws Exception { // RV5 rule 2
        Long post = null;
        try {
            post = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"f03 post\",\"status\":\"publish\",\"content\":\"f03 v1\"}");
            revise(post, 2, "f03 v");
            pause();
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves",
                    "{\"content\":\"f03 bob autosave\"}"));
            String base = "/wp-json/wp/v2/posts/" + post + "/revisions";
            send(RestCall.get("alice", base + "?_fields=id,slug,date,author"));
            send(RestCall.get("alice", base + "?order=asc&_fields=id,slug,date"));
            send(RestCall.get("alice", base + "?orderby=include_slugs&order=desc&_fields=id,slug,date"));
            send(RestCall.get("alice", base + "?orderby=slug&_fields=id,slug,date"));
            send(RestCall.get("alice", base + "?orderby=slug&order=asc&_fields=id,slug,date"));
            send(RestCall.get("alice", base + "?orderby=id&order=asc&_fields=id,slug,date"));
            List<Long> ids =
                    ids(send(RestCall.get("alice", base + "?_fields=id")).json());
            if (ids.size() >= 2) {
                send(RestCall.get(
                        "alice",
                        base + "?orderby=include&include=" + ids.get(1) + "," + ids.get(0) + "&order=asc&_fields=id"));
            }
            send(RestCall.get("alice", base + "?orderby=include&_fields=id"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f04RevisionSearch() throws Exception { // RV5 rule 3
        Long post = null;
        try {
            post = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"f04 post\",\"status\":\"publish\",\"content\":\"f04 apple\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"content\":\"f04 banana\"}"));
            pause();
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"content\":\"f04 cherry banana\"}"));
            pause();
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + post,
                    "{\"title\":\"f04 banana title\",\"content\":\"f04 date\"}"));
            String base = "/wp-json/wp/v2/posts/" + post + "/revisions";
            send(RestCall.get("alice", base + "?search=banana&_fields=id,title,content"));
            send(RestCall.get("alice", base + "?search=banana&orderby=relevance&_fields=id,title,content"));
            send(RestCall.get("alice", base + "?search=%22cherry+banana%22&_fields=id,content"));
            send(RestCall.get("alice", base + "?search=banana+-cherry&_fields=id,content"));
            send(RestCall.get("alice", base + "?orderby=relevance"));
            send(RestCall.get("alice", base + "?search=&orderby=relevance"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f05PostWithoutRevisions() throws Exception { // RV6 rule 6, RV8
        Long bare = null;
        Long revised = null;
        try {
            bare = insert("/", "alice", "post", "publish", "f05 bare", fields());
            revised = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"f05 revised\",\"status\":\"publish\",\"content\":\"f05 v1\"}");
            revise(revised, 2, "f05 v");
            long revision = firstRevisionId(revised);
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + bare + "/revisions?page=9"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + bare + "/revisions"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + bare + "?_fields=id,_links.version-history"));
            try {
                option("/", "qp_parity_no_revisions", TextNode.valueOf("1"));
                note(
                        "qp_parity_no_revisions",
                        "harness rule: wp_revisions_to_keep answers 0 (stand-in for WP_POST_REVISIONS=0)");
                send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + revised + "/revisions?page=9"));
                send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + revised + "/revisions"));
                send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + revised + "/revisions?orderby=relevance"));
                send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + revised + "/revisions/" + revision));
                send(RestCall.get(
                        "alice",
                        "/wp-json/wp/v2/posts/" + revised
                                + "?_fields=id,_links.version-history,_links.predecessor-version"));
                send(RestCall.json(
                        "alice", "PUT", "/wp-json/wp/v2/posts/" + revised, "{\"content\":\"f05 while disabled\"}"));
            } finally {
                option("/", "qp_parity_no_revisions", TextNode.valueOf("0"));
            }
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + revised + "/revisions?_fields=id,content"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", revised);
            deletePostIfCreated("/wp/v2/posts", bare);
        }
    }

    @Test
    void f06RevisionFieldsPerType() throws Exception { // RV10 rules 1-2
        Long page = null;
        Long block = null;
        Long book = null;
        try {
            page = createPost(
                    "alice", "/wp/v2/pages", "{\"title\":\"f06 page\",\"status\":\"publish\",\"content\":\"f06 p1\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + page, "{\"content\":\"f06 p2\"}"));
            block = createPost(
                    "alice",
                    "/wp/v2/blocks",
                    "{\"title\":\"f06 block\",\"status\":\"publish\",\"content\":\"f06 b1\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/blocks/" + block, "{\"content\":\"f06 b2\"}"));
            book = createPost(
                    "alice", "/wp/v2/books", "{\"title\":\"f06 book\",\"status\":\"publish\",\"content\":\"f06 k1\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/books/" + book, "{\"content\":\"f06 k2\"}"));
            Exchange pages = send(RestCall.get("alice", "/wp-json/wp/v2/pages/" + page + "/revisions"));
            Exchange blocks = send(RestCall.get("alice", "/wp-json/wp/v2/blocks/" + block + "/revisions"));
            Exchange books = send(RestCall.get("alice", "/wp-json/wp/v2/books/" + book + "/revisions"));
            noteKeys("page revision keys", pages.json());
            noteKeys("wp_block revision keys", blocks.json());
            noteKeys("book revision keys", books.json());
            send(RestCall.get("alice", "/wp-json/wp/v2/pages/" + page + "/revisions?context=edit"));
            send(RestCall.get("alice", "/wp-json/wp/v2/books/" + book + "/revisions?context=edit"));
            note(
                    "meta [] case",
                    "unobservable with this install: every revision-capable REST type here (post, page,"
                            + " wp_block, and book, registered at init:10) gets footnotes registered at init:20; no type"
                            + " without registered meta exists");
        } finally {
            deletePostIfCreated("/wp/v2/books", book);
            deletePostIfCreated("/wp/v2/blocks", block);
            deletePostIfCreated("/wp/v2/pages", page);
        }
    }

    @Test
    void f07RevisionEmbedContext() throws Exception { // RV10
        Long post = null;
        try {
            post = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"f07 post\",\"status\":\"publish\",\"content\":\"f07 v1\",\"excerpt\":\"f07 excerpt\"}");
            revise(post, 1, "f07 v");
            long revision = firstRevisionId(post);
            Exchange embed = send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision + "?context=embed"));
            noteKeys("embed keys", embed.json());
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision + "?context=edit"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?context=embed"));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts/" + post + "?_embed=version-history&_fields=id,_links,_embedded"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f08RevisionDeleteIsAlwaysRefused() throws Exception { // RV12
        Long post = null;
        try {
            post = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"f08 post\",\"status\":\"publish\",\"content\":\"f08 v1\"}");
            revise(post, 1, "f08 v");
            long revision = firstRevisionId(post);
            Exchange delete = send(RestCall.json(
                    "alice",
                    "DELETE",
                    "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision + "?force=true",
                    "{}"));
            assertThat(delete.status()).isEqualTo(403);
            assertThat(delete.json().get("message").asText())
                    .isEqualTo("Sorry, you are not allowed to delete this revision.");
            send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision, "{}"));
            send(RestCall.json(
                    "dave", "DELETE", "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision + "?force=true", "{}"));
            send(RestCall.json(
                    "anonymous",
                    "DELETE",
                    "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision + "?force=true",
                    "{}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision + "?_fields=id"));
            send(new RestCall(
                    "alice",
                    "OPTIONS",
                    "/wp-json/wp/v2/posts/" + post + "/revisions/" + revision,
                    Map.of(),
                    Optional.empty(),
                    Optional.empty()));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f09AutosaveWithRefusedFields() throws Exception { // RV17 rule 3
        Long draft = null;
        try {
            draft = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f09 draft\",\"status\":\"draft\",\"content\":\"f09 original\"}");
            for (String as : List.of("carol", "bob")) {
                send(RestCall.json(
                        as,
                        "POST",
                        "/wp-json/wp/v2/posts/" + draft + "/autosaves",
                        "{\"content\":\"f09 " + as + " author\",\"author\":99999}"));
                send(RestCall.get(
                        "alice", "/wp-json/wp/v2/posts/" + draft + "?context=edit&_fields=id,content,author,modified"));
                send(RestCall.json(
                        as,
                        "POST",
                        "/wp-json/wp/v2/posts/" + draft + "/autosaves",
                        "{\"content\":\"f09 " + as + " sticky\",\"password\":\"x\",\"sticky\":true}"));
                send(RestCall.get(
                        "alice",
                        "/wp-json/wp/v2/posts/" + draft + "?context=edit&_fields=id,content,password,sticky,modified"));
                send(RestCall.get(
                        "alice",
                        "/wp-json/wp/v2/posts/" + draft + "/autosaves?context=edit&_fields=id,author,content"));
            }
            send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts/" + draft + "/autosaves",
                    "{\"content\":\"f09 carol bob-author\",\"author\":" + site.userId("bob") + "}"));
            send(RestCall.json(
                    "carol", "POST", "/wp-json/wp/v2/posts/" + draft + "/autosaves", "{\"status\":\"nope\"}"));
            send(RestCall.json("carol", "POST", "/wp-json/wp/v2/posts/0/autosaves", "{\"content\":\"x\"}"));
            send(RestCall.json("carol", "POST", "/wp-json/wp/v2/posts/99999/autosaves", "{\"content\":\"x\"}"));
            send(RestCall.json("dave", "POST", "/wp-json/wp/v2/posts/" + draft + "/autosaves", "{\"content\":\"x\"}"));
            send(RestCall.json(
                    "anonymous", "POST", "/wp-json/wp/v2/posts/" + draft + "/autosaves", "{\"content\":\"x\"}"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", draft);
        }
    }

    @Test
    void f10AutosaveOfIdenticalTextAndFootnotesRow() throws Exception { // RV20 rule 1
        Long post = null;
        try {
            post = insert("/", "carol", "post", "publish", "f10 post", fields().put("post_content", "f10 text"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "?context=edit&_fields=id,content,meta"));
            noteMeta("footnotes before", "/", post, "footnotes");
            String body =
                    "{\"title\":\"f10 post\",\"content\":\"f10 text\",\"excerpt\":\"\",\"meta\":{\"footnotes\":\"\"}}";
            for (String as : List.of("carol", "bob")) {
                send(RestCall.json(as, "POST", "/wp-json/wp/v2/posts/" + post + "/autosaves", body));
                pause();
                send(RestCall.json(as, "POST", "/wp-json/wp/v2/posts/" + post + "/autosaves", body));
            }
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves?context=edit&_fields=id,author,date,modified"));
            setMeta("/", post, "footnotes", TextNode.valueOf(""));
            noteMeta("footnotes after set_meta", "/", post, "footnotes");
            for (String as : List.of("carol", "bob")) {
                pause();
                send(RestCall.json(as, "POST", "/wp-json/wp/v2/posts/" + post + "/autosaves", body));
                pause();
                send(RestCall.json(as, "POST", "/wp-json/wp/v2/posts/" + post + "/autosaves", body));
            }
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves?context=edit&_fields=id,author,date,modified"));
            pause();
            send(RestCall.json(
                    "carol", "POST", "/wp-json/wp/v2/posts/" + post + "/autosaves", "{\"content\":\"f10 text\"}"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves?context=edit&_fields=id,author,date,modified"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f11AutosaveWithViewContext() throws Exception { // RV21 rule 2
        Long post = null;
        try {
            post = createPost(
                    "carol", "/wp/v2/posts", "{\"title\":\"f11 post\",\"status\":\"publish\",\"content\":\"f11 v1\"}");
            send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves?context=view",
                    "{\"content\":\"f11 carol autosave\"}"));
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves?context=view",
                    "{\"content\":\"f11 bob autosave\"}"));
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves?context=embed&_fields=id,preview_link,title",
                    "{\"content\":\"f11 bob embed\"}"));
            send(RestCall.get("carol", "/wp-json/wp/v2/posts/" + post + "/autosaves"));
            send(RestCall.get("carol", "/wp-json/wp/v2/posts/" + post + "/autosaves?context=edit"));
            send(new RestCall(
                    "carol",
                    "HEAD",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves",
                    Map.of(),
                    Optional.empty(),
                    Optional.empty()));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void f12OwnDraftAutosaveOnAChildPage() throws Exception { // RV19 rule 4
        Long parent = null;
        Long child = null;
        try {
            parent = createPost("alice", "/wp/v2/pages", "{\"title\":\"f12 parent\",\"status\":\"publish\"}");
            // bob (editor) owns the draft: an author has no edit_pages, so carol cannot create pages.
            child = createPost(
                    "bob",
                    "/wp/v2/pages",
                    "{\"title\":\"f12 child\",\"status\":\"draft\",\"parent\":" + parent + ",\"content\":\"f12 c1\"}");
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/pages/" + child + "/autosaves",
                    "{\"content\":\"f12 bob own draft\"}"));
            send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/pages/" + child + "/autosaves",
                    "{\"content\":\"f12 alice autosave\"}"));
            send(RestCall.get(
                    "bob",
                    "/wp-json/wp/v2/pages/" + child
                            + "?context=edit&_fields=id,parent,content,modified,date,date_gmt"));
        } finally {
            deletePostIfCreated("/wp/v2/pages", child);
            deletePostIfCreated("/wp/v2/pages", parent);
        }
    }

    @Test
    void f13BlockAutosavePreviewLink() throws Exception { // RV23 rule 1
        Long block = null;
        try {
            block = createPost(
                    "alice",
                    "/wp/v2/blocks",
                    "{\"title\":\"f13 block\",\"status\":\"publish\",\"content\":\"f13 b1\"}");
            send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/blocks/" + block + "/autosaves",
                    "{\"content\":\"f13 alice autosave\"}"));
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/blocks/" + block + "/autosaves",
                    "{\"content\":\"f13 bob autosave\"}"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/blocks/" + block + "/autosaves?context=edit&_fields=id,author,preview_link"));
        } finally {
            deletePostIfCreated("/wp/v2/blocks", block);
        }
    }

    @Test
    void f13ContributorCannotPublish() throws Exception { // RP20.3
        List<Long> stray = new ArrayList<>();
        try {
            Exchange publish = send(
                    RestCall.json("dave", "POST", "/wp-json/wp/v2/posts", "{\"title\":\"x\",\"status\":\"publish\"}"));
            stray(publish, stray);
            assertThat(publish.status()).isEqualTo(403);
            assertThat(publish.json().get("message").asText())
                    .isEqualTo("Sorry, you are not allowed to publish posts in this post type.");
        } finally {
            for (long id : stray) {
                deletePost("/wp/v2/posts", id);
            }
        }
    }

    @Test
    void f14LockLifecycle() throws Exception { // RV27-RV30
        Long draft = null;
        try {
            draft = createPost(
                    "carol", "/wp/v2/posts", "{\"title\":\"f14 draft\",\"status\":\"draft\",\"content\":\"f14 v1\"}");
            Session carol = login("carol");
            Session bob = login("bob");
            noteMeta("lock 0 before", "/", draft, "_edit_lock");
            send(asSession(carol, RestCall.get("carol", "/wp-admin/post.php?post=" + draft + "&action=edit")));
            noteMeta("lock 1 after carol opened", "/", draft, "_edit_lock");
            Exchange bobPage =
                    send(asSession(bob, RestCall.get("bob", "/wp-admin/post.php?post=" + draft + "&action=edit")));
            note("bob page postLock", excerptAround(bobPage.text(), "\"postLock\"", 400));
            noteMeta("lock 2 after bob opened", "/", draft, "_edit_lock");
            send(asSession(bob, heartbeat(bob, draft)));
            noteMeta("lock 3 after bob heartbeat", "/", draft, "_edit_lock");
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/posts/" + draft + "/autosaves",
                    "{\"content\":\"f14 bob autosave\"}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + draft + "?context=edit&_fields=id,content,modified"));
            send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts/" + draft + "/autosaves",
                    "{\"content\":\"f14 carol autosave\"}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + draft + "?context=edit&_fields=id,content,modified"));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts/" + draft + "/autosaves?context=edit&_fields=id,author,content"));
            send(asSession(carol, heartbeat(carol, draft)));
            List<String> lock = meta("/", draft, "_edit_lock");
            noteMeta("lock 4 after carol heartbeat", "/", draft, "_edit_lock");
            String current = lock.isEmpty() ? "" : lock.get(0);
            note("release sent at", (int) (System.currentTimeMillis() / 1000));
            send(asSession(carol, removeLock(carol, draft, current)));
            noteMeta("lock 5 after carol release", "/", draft, "_edit_lock");
            send(asSession(carol, removeLock(carol, draft, "1000:" + site.userId("carol"))));
            noteMeta("lock 6 after stale release", "/", draft, "_edit_lock");
            send(asSession(bob, removeLock(bob, draft, current)));
            noteMeta("lock 7 after bob release of carol's value", "/", draft, "_edit_lock");
            send(asSession(bob, heartbeat(bob, draft)));
            noteMeta("lock 8 after bob heartbeat on the released lock", "/", draft, "_edit_lock");
            send(asSession(carol, heartbeat(carol, draft)));
            noteMeta("lock 9 after carol heartbeat while bob holds", "/", draft, "_edit_lock");
            send(asSession(carol, heartbeat(carol, 0)));
            send(asSession(carol, removeLock(carol, draft, current).withHeader("X-Probe", "no-nonce")));
            noteMeta("_edit_last", "/", draft, "_edit_last");
        } finally {
            deletePostIfCreated("/wp/v2/posts", draft);
        }
    }

    @Test
    void f15AutosaveItemIgnoresItsId() throws Exception { // RV15
        Long pending = null;
        try {
            pending = createPost(
                    "dave",
                    "/wp/v2/posts",
                    "{\"title\":\"f15 pending\",\"status\":\"pending\",\"content\":\"f15 v1\"}");
            Exchange daves = send(RestCall.json(
                    "dave",
                    "POST",
                    "/wp-json/wp/v2/posts/" + pending + "/autosaves",
                    "{\"content\":\"f15 dave autosave\"}"));
            pause();
            Exchange alices = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts/" + pending + "/autosaves",
                    "{\"content\":\"f15 alice autosave\"}"));
            Exchange item = send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + pending + "/autosaves/123456"));
            assertThat(item.status()).isEqualTo(200);
            assertThat(item.json().get("id").longValue())
                    .isEqualTo(alices.json().get("id").longValue());
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + pending + "/autosaves/"
                            + daves.json().get("id").longValue()));
            send(RestCall.get(
                    "dave",
                    "/wp-json/wp/v2/posts/" + pending + "/autosaves/"
                            + daves.json().get("id").longValue()));
            send(RestCall.get("erin", "/wp-json/wp/v2/posts/" + pending + "/autosaves/1"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/99999/autosaves/1"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", pending);
        }
        Long bare = null;
        try {
            bare = insert("/", "alice", "post", "publish", "f15 no autosave", fields());
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + bare + "/autosaves/1"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + bare + "/autosaves"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", bare);
        }
    }

    @Test
    void f15TakeoverWithABadNonce() throws Exception { // RV28 rule 4
        Long draft = null;
        try {
            draft = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f15 takeover\",\"status\":\"draft\",\"content\":\"f15 t1\"}");
            Session carol = login("carol");
            Session bob = login("bob");
            send(asSession(carol, RestCall.get("carol", "/wp-admin/post.php?post=" + draft + "&action=edit")));
            noteMeta("lock after carol opened", "/", draft, "_edit_lock");
            send(asSession(
                    bob,
                    RestCall.get(
                            "bob",
                            "/wp-admin/post.php?get-post-lock=1&post=" + draft
                                    + "&action=edit&_wpnonce=0123456789&lockKey=true")));
            noteMeta("lock after bad-nonce takeover", "/", draft, "_edit_lock");
            send(asSession(
                    bob,
                    RestCall.get(
                                    "bob",
                                    "/wp-admin/post.php?get-post-lock=1&post=" + draft
                                            + "&action=edit&_wpnonce=0123456789&lockKey=true")
                            .withHeader("Accept", "application/json")));
            String nonce = nonce(bob, "lock-post_" + draft);
            send(asSession(
                    bob,
                    RestCall.get(
                            "bob",
                            "/wp-admin/post.php?get-post-lock=1&post=" + draft + "&action=edit&_wpnonce=" + nonce
                                    + "&lockKey=true")));
            noteMeta("lock after valid takeover", "/", draft, "_edit_lock");
            send(asSession(carol, heartbeat(carol, draft)));
            noteMeta("lock after carol heartbeat post-takeover", "/", draft, "_edit_lock");
            note(
                    "override_post_lock",
                    "unobservable over HTTP: the block editor's take-over modal is client-side code;"
                            + " the server emits only postLock settings, which do not carry the filter's result");
        } finally {
            deletePostIfCreated("/wp/v2/posts", draft);
        }
    }

    @Test
    void f16FrontEndPreviewGates() throws Exception { // RV34, RV35
        Long draft = null;
        Long published = null;
        try {
            draft = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f16 draft\",\"status\":\"draft\",\"content\":\"f16 draft text\"}");
            published = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f16 published\",\"status\":\"publish\",\"content\":\"f16 published text\"}");
            Session carol = login("carol");
            Session dave = login("dave");
            send(RestCall.get("anonymous", "/?p=" + draft + "&preview=true"));
            send(asSession(dave, RestCall.get("dave", "/?p=" + draft + "&preview=true")));
            Exchange shown = send(asSession(carol, RestCall.get("carol", "/?p=" + draft + "&preview=true")));
            note("carol draft preview time tags", timeTags(shown.text()));
            note("carol draft preview sent at", (int) (System.currentTimeMillis() / 1000));
            send(asSession(carol, RestCall.get("carol", "/?p=" + draft)));
            send(RestCall.get(
                    "anonymous", "/?p=" + published + "&preview_id=" + published + "&preview_nonce=0123456789"));
            send(RestCall.get(
                            "anonymous", "/?p=" + published + "&preview_id=" + published + "&preview_nonce=0123456789")
                    .withHeader("Accept", "application/json"));
            send(asSession(
                    carol,
                    RestCall.get(
                            "carol",
                            "/?p=" + published + "&preview_id=" + published
                                    + "&preview_nonce=0123456789&preview=true")));
            send(RestCall.get(
                    "anonymous",
                    "/wp-json/wp/v2/posts/" + published + "?preview_id=" + published + "&preview_nonce=0123456789"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + published + "?preview_id=" + published + "&preview_nonce=0123456789"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + published + "?preview_id=" + published));
            String valid = nonce(carol, "post_preview_" + published);
            send(asSession(
                    carol,
                    RestCall.get(
                            "carol",
                            "/?p=" + published + "&preview_id=" + published + "&preview_nonce=" + valid
                                    + "&preview=true")));
            send(RestCall.get(
                    "anonymous",
                    "/?p=" + published + "&preview_id=" + published + "&preview_nonce=" + valid + "&preview=true"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", published);
            deletePostIfCreated("/wp/v2/posts", draft);
        }
    }

    @Test
    void f17PreviewShowsTheNewestAutosaveOfAnyUser() throws Exception { // RV36 rule 1
        Long published = null;
        try {
            published = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f17 published\",\"status\":\"publish\",\"content\":\"f17 stored text\"}");
            Session carol = login("carol");
            send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts/" + published + "/autosaves",
                    "{\"content\":\"f17 carol autosave text\"}"));
            pause();
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/posts/" + published + "/autosaves",
                    "{\"content\":\"f17 bob autosave text\",\"title\":\"f17 bob title\"}"));
            String valid = nonce(carol, "post_preview_" + published);
            Exchange preview = send(asSession(
                    carol,
                    RestCall.get(
                            "carol",
                            "/?p=" + published + "&preview_id=" + published + "&preview_nonce=" + valid
                                    + "&preview=true")));
            String text = preview.text();
            note("shows bob text", text.contains("f17 bob autosave text") ? "yes" : "no");
            note("shows carol text", text.contains("f17 carol autosave text") ? "yes" : "no");
            note("shows stored text", text.contains("f17 stored text") ? "yes" : "no");
            note("shows bob title", text.contains("f17 bob title") ? "yes" : "no");
            Exchange plain = send(asSession(carol, RestCall.get("carol", "/?p=" + published + "&preview=true")));
            note("without nonce shows stored text", plain.text().contains("f17 stored text") ? "yes" : "no");
        } finally {
            deletePostIfCreated("/wp/v2/posts", published);
        }
    }

    @Test
    void f18NonceAfterLogoutAndLogin() throws Exception { // RV38 rule 2
        Long published = null;
        try {
            published = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f18 published\",\"status\":\"publish\",\"content\":\"f18 stored text\"}");
            send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/posts/" + published + "/autosaves",
                    "{\"content\":\"f18 bob autosave text\"}"));
            Session first = login("carol");
            String oldNonce = nonce(first, "post_preview_" + published);
            String target =
                    "/?p=" + published + "&preview_id=" + published + "&preview_nonce=" + oldNonce + "&preview=true";
            send(asSession(first, RestCall.get("carol", target)));
            ObjectNode logout = args().put("user", "carol").put("session", first.token());
            note("logout", site.operation("logout", logout).toString());
            Session second = login("carol");
            send(asSession(first, RestCall.get("carol", target)));
            send(asSession(second, RestCall.get("carol", target)));
            String newNonce = nonce(second, "post_preview_" + published);
            note("new nonce equals old", newNonce.equals(oldNonce) ? "yes" : "no");
            send(asSession(
                    second,
                    RestCall.get(
                            "carol",
                            "/?p=" + published + "&preview_id=" + published + "&preview_nonce=" + newNonce
                                    + "&preview=true")));
            Session bob = login("bob");
            send(asSession(
                    bob,
                    RestCall.get(
                            "bob",
                            "/?p=" + published + "&preview_id=" + published + "&preview_nonce=" + newNonce
                                    + "&preview=true")));
        } finally {
            deletePostIfCreated("/wp/v2/posts", published);
        }
    }

    @Test
    void f19PublishedPreviewWithoutNonceInBothModes() throws Exception { // RV35 rule 4
        Long published = null;
        try {
            published = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f19 published\",\"status\":\"publish\",\"content\":\"f19 stored text\"}");
            Session carol = login("carol");
            send(RestCall.get("anonymous", "/?p=" + published + "&preview=true"));
            send(asSession(carol, RestCall.get("carol", "/?p=" + published + "&preview=true")));
            send(RestCall.get("anonymous", "/?p=" + published));
            try {
                option("/", "permalink_structure", TextNode.valueOf(""));
                send(RestCall.get("anonymous", "/?p=" + published + "&preview=true"));
                send(asSession(carol, RestCall.get("carol", "/?p=" + published + "&preview=true")));
                send(RestCall.get("anonymous", "/?p=" + published));
            } finally {
                option("/", "permalink_structure", TextNode.valueOf("/%postname%/"));
            }
        } finally {
            deletePostIfCreated("/wp/v2/posts", published);
        }
    }

    @Test
    void f20ClassicPreviewDieStatus() throws Exception { // RV39 rule 1
        Long draft = null;
        Long published = null;
        try {
            draft = createPost(
                    "carol", "/wp/v2/posts", "{\"title\":\"f20 draft\",\"status\":\"draft\",\"content\":\"f20 d1\"}");
            published = createPost(
                    "carol",
                    "/wp/v2/posts",
                    "{\"title\":\"f20 published\",\"status\":\"publish\",\"content\":\"f20 p1\"}");
            Session carol = login("carol");
            Session dave = login("dave");
            send(asSession(dave, classicPreview(dave, published, "f20 dave form")));
            send(asSession(
                    dave, classicPreview(dave, published, "f20 dave form").withHeader("Accept", "application/json")));
            send(asSession(carol, classicPreview(carol, 99999, "f20 missing")));
            send(asSession(carol, classicPreview(carol, draft, "f20 carol draft form")));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts/" + draft + "?context=edit&_fields=id,title,content,status"));
            send(asSession(carol, classicPreview(carol, published, "f20 carol published form")));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + published
                            + "/autosaves?context=edit&_fields=id,author,content,preview_link"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", published);
            deletePostIfCreated("/wp/v2/posts", draft);
        }
    }

    // ---- Part G: posts controller, meta, term filters, search, auto-draft -------------------------------------

    @Test
    void g01FloatingDraftDatesPerZone() throws Exception { // RP5.2-RP5.3
        JsonNode settings = send(RestCall.get("alice", "/wp-json/wp/v2/settings?_fields=timezone"))
                .json();
        JsonNode index = send(RestCall.get("anonymous", "/wp-json/?_fields=gmt_offset,timezone_string"))
                .json();
        Long summer = null;
        Long now = null;
        Long later = null;
        try {
            option("/", "timezone_string", TextNode.valueOf("Europe/Paris"));
            summer = insert(
                    "/", "alice", "post", "draft", "g01 summer", fields().put("post_date", "2026-07-15 12:00:00"));
            now = createPost("alice", "/wp/v2/posts", "{\"title\":\"g01 now\",\"status\":\"draft\"}");
            send(RestCall.get("anonymous", "/wp-json/?_fields=gmt_offset,timezone_string"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + summer
                            + "?context=edit&_fields=id,date,date_gmt,modified,modified_gmt,status"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + now
                            + "?context=edit&_fields=id,date,date_gmt,modified,modified_gmt,status"));
            option("/", "timezone_string", TextNode.valueOf(""));
            option("/", "gmt_offset", IntNode.valueOf(2));
            later = createPost("alice", "/wp/v2/posts", "{\"title\":\"g01 offset\",\"status\":\"draft\"}");
            send(RestCall.get("anonymous", "/wp-json/?_fields=gmt_offset,timezone_string"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + summer
                            + "?context=edit&_fields=id,date,date_gmt,modified,modified_gmt,status"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + now
                            + "?context=edit&_fields=id,date,date_gmt,modified,modified_gmt,status"));
            send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts/" + later
                            + "?context=edit&_fields=id,date,date_gmt,modified,modified_gmt,status"));
        } finally {
            option("/", "timezone_string", settings.path("timezone"));
            option("/", "gmt_offset", index.path("gmt_offset"));
            deletePostIfCreated("/wp/v2/posts", later);
            deletePostIfCreated("/wp/v2/posts", now);
            deletePostIfCreated("/wp/v2/posts", summer);
        }
        assertThat(send(RestCall.get("anonymous", "/wp-json/?_fields=gmt_offset,timezone_string"))
                        .json())
                .as("zone restored")
                .isEqualTo(index);
    }

    @Test
    void g02LinksInPlainAndPrettyMode() throws Exception { // RP5.6, RP5.12
        List<String[]> created = new ArrayList<>();
        try {
            long parent = createPost("alice", "/wp/v2/pages", "{\"title\":\"g02 parent\",\"status\":\"publish\"}");
            created.add(new String[] {"/wp/v2/pages", String.valueOf(parent)});
            String[][] fixtures = {
                {"/wp/v2/posts", "{\"title\":\"g02 post draft\",\"status\":\"draft\"}"},
                {"/wp/v2/posts", "{\"title\":\"g02 post published\",\"status\":\"publish\"}"},
                {"/wp/v2/posts", "{\"title\":\"g02 Title Differs\",\"slug\":\"g02-other-slug\",\"status\":\"publish\"}"
                },
                {"/wp/v2/pages", "{\"title\":\"g02 page draft\",\"status\":\"draft\"}"},
                {"/wp/v2/pages", "{\"title\":\"g02 page published\",\"status\":\"publish\"}"},
                {"/wp/v2/pages", "{\"title\":\"g02 child draft\",\"status\":\"draft\",\"parent\":" + parent + "}"},
                {"/wp/v2/pages", "{\"title\":\"g02 child published\",\"status\":\"publish\",\"parent\":" + parent + "}"
                },
                {"/wp/v2/books", "{\"title\":\"g02 book draft\",\"status\":\"draft\"}"},
                {"/wp/v2/books", "{\"title\":\"g02 book published\",\"status\":\"publish\"}"},
                {"/wp/v2/blocks", "{\"title\":\"g02 block draft\",\"status\":\"draft\",\"content\":\"g02 b\"}"},
                {"/wp/v2/blocks", "{\"title\":\"g02 block published\",\"status\":\"publish\",\"content\":\"g02 b\"}"},
            };
            for (String[] fixture : fixtures) {
                created.add(new String[] {fixture[0], String.valueOf(createPost("alice", fixture[0], fixture[1]))});
            }
            String fields =
                    "context=edit&_fields=id,type,status,slug,link,permalink_template,generated_slug,guid,title,parent";
            for (String[] entry : created) {
                send(RestCall.get("alice", "/wp-json" + entry[0] + "/" + entry[1] + "?" + fields));
            }
            try {
                option("/", "permalink_structure", TextNode.valueOf(""));
                for (String[] entry : created) {
                    send(RestCall.get("alice", "/index.php?rest_route=" + entry[0] + "/" + entry[1] + "&" + fields));
                }
            } finally {
                option("/", "permalink_structure", TextNode.valueOf("/%postname%/"));
            }
        } finally {
            for (int i = created.size() - 1; i >= 0; i--) {
                deletePost(created.get(i)[0], Long.parseLong(created.get(i)[1]));
            }
        }
    }

    @Test
    void g03TemplateValues() throws Exception { // RP5.10, RP25
        Long page = null;
        Long post = null;
        try {
            page = createPost("alice", "/wp/v2/pages", "{\"title\":\"g03 page\",\"status\":\"publish\"}");
            post = createPost("alice", "/wp/v2/posts", "{\"title\":\"g03 post\",\"status\":\"publish\"}");
            setMeta("/", page, "_wp_page_template", TextNode.valueOf("default"));
            send(RestCall.get("alice", "/wp-json/wp/v2/pages/" + page + "?context=edit&_fields=id,template"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"template\":\"nope\"}"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + page, "{\"template\":\"nope\"}"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + page, "{\"template\":\"page-no-title\"}"));
            noteMeta("page template meta after page-no-title", "/", page, "_wp_page_template");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + page, "{\"template\":\"\"}"));
            noteMeta("page template meta after empty", "/", page, "_wp_page_template");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"template\":\"page-no-title\"}"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"template\":\"default\"}"));
            send(RestCall.json(
                    "alice", "POST", "/wp-json/wp/v2/pages", "{\"title\":\"g03 created\",\"template\":\"nope\"}"));
            setMeta("/", page, "_wp_page_template", TextNode.valueOf("gone-template"));
            send(RestCall.get("alice", "/wp-json/wp/v2/pages/" + page + "?context=edit&_fields=id,template"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + page, "{\"template\":\"gone-template\"}"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
            deletePostIfCreated("/wp/v2/pages", page);
        }
    }

    @Test
    void g04PasswordProtection() throws Exception { // RP6
        Long open = null;
        Long locked = null;
        try {
            open = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"g04 open\",\"status\":\"publish\",\"content\":\"g04 open text\"}");
            locked = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"g04 locked\",\"status\":\"publish\",\"content\":\"g04 secret text\",\"password\":\"s3\"}");
            String fields = "&_fields=id,title,content,excerpt,password,class_list";
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + open + "?password=x" + fields));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + open + "?password=x" + fields));
            send(RestCall.get(
                    "anonymous", "/wp-json/wp/v2/posts/" + locked + "?_fields=id,title,content,excerpt,class_list"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + locked + "?password=s3" + fields));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + locked + "?password=no" + fields));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + locked + "?password=" + fields));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts/" + locked + "?_fields=id,title,content,excerpt,class_list"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + locked + "?context=edit" + fields));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + locked + "?context=edit&password=no" + fields));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts?password=s3&include=" + locked + "," + open + fields));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts?include=" + locked + "," + open + fields));
            send(RestCall.get(
                    "anonymous", "/wp-json/wp/v2/posts?search=secret&include=" + locked + "," + open + "&_fields=id"));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts?search=secret&include=" + locked + "," + open + "&_fields=id"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", locked);
            deletePostIfCreated("/wp/v2/posts", open);
        }
    }

    @Test
    void g05StatusParameter() throws Exception { // RP9, RP14
        String prefix = createSite("g05");
        List<Long> posts = new ArrayList<>();
        try {
            for (int i = 1; i <= 2; i++) {
                posts.add(createPost(
                        "carol",
                        prefix,
                        "/wp/v2/posts",
                        "{\"title\":\"g05 carol draft " + i + "\",\"status\":\"draft\"}"));
            }
            for (int i = 1; i <= 3; i++) {
                posts.add(createPost(
                        "dave",
                        prefix,
                        "/wp/v2/posts",
                        "{\"title\":\"g05 dave draft " + i + "\",\"status\":\"draft\"}"));
            }
            posts.add(createPost(
                    "carol", prefix, "/wp/v2/posts", "{\"title\":\"g05 carol private\",\"status\":\"private\"}"));
            posts.add(createPost(
                    "alice", prefix, "/wp/v2/posts", "{\"title\":\"g05 alice private\",\"status\":\"private\"}"));
            posts.add(createPost(
                    "alice", prefix, "/wp/v2/posts", "{\"title\":\"g05 alice published\",\"status\":\"publish\"}"));
            String fields = "&_fields=id,title,status,author";
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?status=draft" + fields));
            send(RestCall.get("erin", prefix + "/wp-json/wp/v2/posts?status=draft" + fields));
            send(RestCall.get("erin", prefix + "/wp-json/wp/v2/posts?status=private" + fields));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=draft" + fields));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=draft&per_page=1" + fields));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=draft&per_page=1&page=2" + fields));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=private" + fields));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=private&context=edit" + fields));
            send(RestCall.get("dave", prefix + "/wp-json/wp/v2/posts?status=private" + fields));
            send(RestCall.get("dave", prefix + "/wp-json/wp/v2/posts?status=draft&context=edit" + fields));
            send(RestCall.get("bob", prefix + "/wp-json/wp/v2/posts?status=Draft,private" + fields));
            send(RestCall.get("bob", prefix + "/wp-json/wp/v2/posts?status=bogus" + fields));
            send(RestCall.get("bob", prefix + "/wp-json/wp/v2/posts?status=any" + fields));
            send(RestCall.get("alice", prefix + "/wp-json/wp/v2/posts" + "?_fields=id,status"));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=draft&per_page=5&page=2" + fields));
        } finally {
            for (long post : posts) {
                deletePost(prefix, "/wp/v2/posts", post);
            }
        }
    }

    @Test
    void g06StickyOrdering() throws Exception { // RP11.3, RP50
        String prefix = createSite("g06");
        List<Long> posts = new ArrayList<>();
        Long category = null;
        try {
            category = createTerm(prefix, "categories", "{\"name\":\"g06 cat\"}");
            for (int i = 1; i <= 4; i++) {
                String cat = i == 1 ? ",\"categories\":[" + category + "]" : "";
                posts.add(createPost(
                        "alice",
                        prefix,
                        "/wp/v2/posts",
                        "{\"title\":\"g06 plain " + i + "\",\"status\":\"publish\"" + cat + "}"));
                pause();
            }
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g06 sticky a\",\"status\":\"publish\",\"sticky\":true}"));
            pause();
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g06 sticky b in cat\",\"status\":\"publish\",\"sticky\":true,\"categories\":["
                            + category + "]}"));
            pause();
            posts.add(createPost(
                    "alice", prefix, "/wp/v2/posts", "{\"title\":\"g06 plain newest\",\"status\":\"publish\"}"));
            String fields = "&_fields=id,title,sticky";
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=2" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2" + fields));
            send(RestCall.get(
                    "anonymous", prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&page=2" + fields));
            send(RestCall.get(
                    "anonymous",
                    prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&categories=" + category + fields));
            send(RestCall.get(
                    "anonymous",
                    prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&categories_exclude=" + category
                            + fields));
            send(RestCall.get(
                    "anonymous", prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&search=g06" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?sticky=true" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?sticky=false" + fields));
            send(RestCall.get(
                    "anonymous", prefix + "/wp-json/wp/v2/posts?sticky=true&include=" + posts.get(0) + fields));
            send(RestCall.get(
                    "anonymous",
                    prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&include=" + posts.get(0) + ","
                            + posts.get(1) + fields));
            send(RestCall.get(
                    "anonymous",
                    prefix + "/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&orderby=title&order=asc" + fields));
        } finally {
            for (long post : posts) {
                deletePost(prefix, "/wp/v2/posts", post);
            }
            if (category != null) {
                send(RestCall.json(
                        "alice", "DELETE", prefix + "/wp-json/wp/v2/categories/" + category + "?force=true", "{}"));
            }
        }
    }

    @Test
    void g07PagingPastTheEnd() throws Exception { // RP14
        String prefix = createSite("g07");
        List<Long> posts = new ArrayList<>();
        try {
            for (int i = 1; i <= 3; i++) {
                posts.add(createPost(
                        "alice", prefix, "/wp/v2/posts", "{\"title\":\"g07 post " + i + "\",\"status\":\"publish\"}"));
            }
            String fields = "&_fields=id";
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=2&page=2" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=2&page=3" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?search=g07nothing&page=2" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?search=g07nothing" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=1&offset=1" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=1&offset=1&page=2" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=1&offset=2&page=3" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=1&offset=3" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?search=" + fields));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/posts?per_page=2&page=0" + fields));
            send(RestCall.get("carol", prefix + "/wp-json/wp/v2/posts?status=draft&page=2" + fields));
        } finally {
            for (long post : posts) {
                deletePost(prefix, "/wp/v2/posts", post);
            }
        }
    }

    @Test
    void g08ItemLinkHeaders() throws Exception { // RP15.3
        Long post = null;
        Long page = null;
        Long block = null;
        try {
            post = createPost("alice", "/wp/v2/posts", "{\"title\":\"g08 post\",\"status\":\"publish\"}");
            page = createPost("alice", "/wp/v2/pages", "{\"title\":\"g08 page\",\"status\":\"publish\"}");
            block = createPost(
                    "alice", "/wp/v2/blocks", "{\"title\":\"g08 block\",\"status\":\"publish\",\"content\":\"g08\"}");
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + post + "?_fields=id"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "?_fields=id"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/pages/" + page + "?_fields=id"));
            send(RestCall.get("alice", "/wp-json/wp/v2/blocks/" + block + "?_fields=id"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts?include=" + post + "&_fields=id"));
            send(new RestCall(
                    "anonymous", "HEAD", "/wp-json/wp/v2/posts/" + post, Map.of(), Optional.empty(), Optional.empty()));
        } finally {
            deletePostIfCreated("/wp/v2/blocks", block);
            deletePostIfCreated("/wp/v2/pages", page);
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void g09AuthorAndStickyOnCreate() throws Exception { // RP17, RP19.7
        List<Long> posts = new ArrayList<>();
        List<Long> pages = new ArrayList<>();
        ObjectNode outsider = args().put("login", "g09outsider");
        try {
            Exchange sticky =
                    send(RestCall.json("carol", "POST", "/wp-json/wp/v2/pages", "{\"title\":\"g09\",\"sticky\":true}"));
            assertThat(sticky.status()).isEqualTo(403);
            assertThat(sticky.json().get("code").asText()).isEqualTo("rest_cannot_assign_sticky");
            stray(sticky, pages);
            Exchange zero = send(RestCall.json(
                    "carol", "POST", "/wp-json/wp/v2/posts", "{\"title\":\"g09 author zero\",\"author\":0}"));
            stray(zero, posts);
            Exchange other = send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g09 author bob\",\"author\":" + site.userId("bob") + "}"));
            stray(other, posts);
            Exchange self = send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g09 author self\",\"author\":" + site.userId("carol") + "}"));
            stray(self, posts);
            long outsiderId = site.operation("outsider", outsider).get("id").longValue();
            Exchange network = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g09 author outsider\",\"author\":" + outsiderId + "}"));
            stray(network, posts);
            Exchange unknown = send(RestCall.json(
                    "alice", "POST", "/wp-json/wp/v2/posts", "{\"title\":\"g09 author unknown\",\"author\":99999}"));
            stray(unknown, posts);
            Exchange adminSticky = send(RestCall.json(
                    "alice", "POST", "/wp-json/wp/v2/pages", "{\"title\":\"g09 admin page sticky\",\"sticky\":true}"));
            stray(adminSticky, pages);
            Exchange withId = send(
                    RestCall.json("alice", "POST", "/wp-json/wp/v2/posts", "{\"title\":\"g09 with id\",\"id\":5}"));
            stray(withId, posts);
            Exchange erin = send(RestCall.json("erin", "POST", "/wp-json/wp/v2/posts", "{\"title\":\"g09 erin\"}"));
            stray(erin, posts);
            Exchange stickyPost = send(RestCall.json(
                    "carol", "POST", "/wp-json/wp/v2/posts", "{\"title\":\"g09 carol sticky post\",\"sticky\":true}"));
            stray(stickyPost, posts);
            Exchange password = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g09 sticky password\",\"sticky\":true,\"password\":\"x\"}"));
            stray(password, posts);
        } finally {
            for (long post : posts) {
                deletePost("/wp/v2/posts", post);
            }
            for (long page : pages) {
                deletePost("/wp/v2/pages", page);
            }
            site.operation("delete_user", outsider);
        }
    }

    @Test
    void g10SlugUniqueness() throws Exception { // RP22.2
        List<Long> posts = new ArrayList<>();
        List<Long> pages = new ArrayList<>();
        try {
            posts.add(createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"g10 taken\",\"slug\":\"g10-taken\",\"status\":\"publish\"}"));
            Exchange draft = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g10 draft\",\"slug\":\"g10-taken\",\"status\":\"draft\"}"));
            stray(draft, posts);
            Exchange pending = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g10 pending\",\"slug\":\"g10-taken\",\"status\":\"pending\"}"));
            stray(pending, posts);
            Exchange published = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g10 published\",\"slug\":\"g10-taken\",\"status\":\"publish\"}"));
            stray(published, posts);
            long parent = createPost(
                    "alice",
                    "/wp/v2/pages",
                    "{\"title\":\"g10 parent\",\"slug\":\"g10-parent\",\"status\":\"publish\"}");
            pages.add(parent);
            long top = createPost(
                    "alice", "/wp/v2/pages", "{\"title\":\"g10 top\",\"slug\":\"g10-top\",\"status\":\"publish\"}");
            pages.add(top);
            long childA = createPost(
                    "alice",
                    "/wp/v2/pages",
                    "{\"title\":\"g10 child a\",\"slug\":\"g10-child-a\",\"status\":\"publish\",\"parent\":" + parent
                            + "}");
            pages.add(childA);
            long childB = createPost(
                    "alice",
                    "/wp/v2/pages",
                    "{\"title\":\"g10 child b\",\"slug\":\"g10-child-b\",\"status\":\"publish\",\"parent\":" + parent
                            + "}");
            pages.add(childB);
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + childB, "{\"slug\":\"g10-child-a\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/pages/" + childB,
                    "{\"slug\":\"g10-child-a\",\"parent\":" + parent + "}"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + childB, "{\"slug\":\"g10-top\"}"));
            send(RestCall.json(
                    "alice", "PUT", "/wp-json/wp/v2/pages/" + childB, "{\"slug\":\"g10-top\",\"status\":\"draft\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/pages/" + childB,
                    "{\"slug\":\"g10-child-a\",\"status\":\"draft\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/pages/" + childB,
                    "{\"slug\":\"g10-child-a\",\"status\":\"draft\",\"parent\":" + parent + "}"));
            Exchange childDraft = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/pages",
                    "{\"title\":\"g10 child draft\",\"slug\":\"g10-child-a\",\"status\":\"draft\",\"parent\":" + parent
                            + "}"));
            stray(childDraft, pages);
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/pages?parent=" + parent + "&status=any&_fields=id,slug,status,parent"));
        } finally {
            for (int i = pages.size() - 1; i >= 0; i--) {
                deletePost("/wp/v2/pages", pages.get(i));
            }
            for (long post : posts) {
                deletePost("/wp/v2/posts", post);
            }
        }
    }

    @Test
    void g11FeaturedMedia() throws Exception { // RP24
        Long attachment = null;
        Long post = null;
        Long page = null;
        try {
            attachment = insert("/", "alice", "attachment", "inherit", "g11 image", fields());
            page = createPost("alice", "/wp/v2/pages", "{\"title\":\"g11 page\",\"status\":\"publish\"}");
            post = insert("/", "alice", "post", "publish", "g11 post", fields().put("thumbnail", attachment));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/media/" + attachment + "?_fields=id,mime_type,media_type,source_url"));
            String fields = "?_fields=id,featured_media,_links.wp:featuredmedia";
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"featured_media\":999999}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            send(RestCall.json(
                    "alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"featured_media\":" + page + "}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            noteMeta("_thumbnail_id after page id", "/", post, "_thumbnail_id");
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + post + fields,
                    "{\"featured_media\":" + attachment + "}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"featured_media\":0}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            noteMeta("_thumbnail_id after 0", "/", post, "_thumbnail_id");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"featured_media\":null}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + post + fields,
                    "{\"featured_media\":-" + attachment + "}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            send(RestCall.json(
                    "carol",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g11 carol\",\"featured_media\":" + attachment
                            + ",\"_fields\":\"id,featured_media\"}"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
            deletePostIfCreated("/wp/v2/pages", page);
            deletePostIfCreated("/wp/v2/media", attachment);
            Exchange carols =
                    send(RestCall.get("alice", "/wp-json/wp/v2/posts?status=draft&search=g11+carol&_fields=id"));
            for (long id : ids(carols.json())) {
                deletePost("/wp/v2/posts", id);
            }
        }
    }

    @Test
    void g12DatesOnWrite() throws Exception { // RP21
        String prefix = createSite("g12");
        Long draft = null;
        Long published = null;
        try {
            option("/g12/", "timezone_string", TextNode.valueOf("Europe/Paris"));
            draft = createPost("alice", prefix, "/wp/v2/posts", "{\"title\":\"g12 draft\",\"status\":\"draft\"}");
            String fields = "?context=edit&_fields=id,date,date_gmt,modified,modified_gmt,status";
            Exchange floating = send(RestCall.get("alice", prefix + "/wp-json/wp/v2/posts/" + draft + fields));
            pause();
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"" + floating.json().get("date").asText() + "\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"2026-03-01T09:00:00+05:00\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"2026-03-01T09:00:00+05:00\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date_gmt\":\"2026-03-02T10:00:00\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"2026-03-03T10:00:00\",\"date_gmt\":\"2026-03-04T10:00:00\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"2026-02-30T10:00:00\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"2026-01-32T10:00:00\"}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date\":\"2026-03-05T10:00:00.250Z\"}"));
            send(RestCall.json("alice", "PUT", prefix + "/wp-json/wp/v2/posts/" + draft + fields, "{\"date\":null}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + draft + fields,
                    "{\"date_gmt\":\"2026-07-01T10:00:00+02:00\"}"));
            published = createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g12 published\",\"status\":\"publish\",\"date\":\"2026-01-10T08:00:00\"}");
            send(RestCall.get("alice", prefix + "/wp-json/wp/v2/posts/" + published + fields));
            send(RestCall.json(
                    "alice", "PUT", prefix + "/wp-json/wp/v2/posts/" + published + fields, "{\"date\":null}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    prefix + "/wp-json/wp/v2/posts/" + published + fields,
                    "{\"date\":\"2030-01-01T10:00:00\"}"));
            send(RestCall.json(
                    "alice", "PUT", prefix + "/wp-json/wp/v2/posts/" + published + fields, "{\"status\":\"publish\"}"));
            send(RestCall.json(
                    "alice", "PUT", prefix + "/wp-json/wp/v2/posts/" + published + fields, "{\"date_gmt\":null}"));
            Exchange scheduled = send(RestCall.json(
                    "alice",
                    "POST",
                    prefix + "/wp-json/wp/v2/posts" + fields,
                    "{\"title\":\"g12 scheduled\",\"status\":\"publish\",\"date_gmt\":\"2030-01-15T12:30:00\"}"));
            if (scheduled.status() == 201) {
                deletePost(prefix, "/wp/v2/posts", scheduled.json().get("id").longValue());
            }
            Exchange dated = send(RestCall.json(
                    "carol",
                    "POST",
                    prefix + "/wp-json/wp/v2/posts" + fields,
                    "{\"title\":\"g12 carol dated draft\",\"status\":\"draft\",\"date\":\"2026-02-01T10:00:00\"}"));
            if (dated.status() == 201) {
                long id = dated.json().get("id").longValue();
                send(RestCall.json(
                        "carol",
                        "PUT",
                        prefix + "/wp-json/wp/v2/posts/" + id + fields,
                        "{\"title\":\"g12 carol dated draft 2\"}"));
                deletePost(prefix, "/wp/v2/posts", id);
            }
        } finally {
            deletePostIfCreated(prefix, "/wp/v2/posts", published);
            deletePostIfCreated(prefix, "/wp/v2/posts", draft);
        }
    }

    @Test
    void g13MetaWrites() throws Exception { // RP30, RP41, RP44
        Long post = null;
        Long daves = null;
        Long block = null;
        Long published = null;
        try {
            post = createPost("alice", "/wp/v2/posts", "{\"title\":\"g13 post\",\"status\":\"draft\"}");
            String fields = "?_fields=id,title,meta,modified";
            send(RestCall.json(
                    "alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"meta\":{\"footnotes\":5}}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + post + fields,
                    "{\"meta\":{\"qp_unknown\":\"x\",\"_thumbnail_id\":\"7\"}}"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"meta\":\"x\"}"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"meta\":null}"));
            send(
                    RestCall.json(
                            "alice",
                            "PUT",
                            "/wp-json/wp/v2/posts/" + post + fields,
                            "{\"meta\":{\"footnotes\":\"[{\\\"id\\\":\\\"a\\\",\\\"content\\\":\\\"<script>x</script>/y\\\"}]\"}}"));
            noteMeta("alice footnotes stored", "/", post, "footnotes");
            daves = createPost("dave", "/wp/v2/posts", "{\"title\":\"g13 dave\",\"status\":\"draft\"}");
            send(
                    RestCall.json(
                            "dave",
                            "PUT",
                            "/wp-json/wp/v2/posts/" + daves + fields,
                            "{\"meta\":{\"footnotes\":\"[{\\\"id\\\":\\\"a\\\",\\\"content\\\":\\\"<script>x</script>/y\\\"}]\"}}"));
            noteMeta("dave footnotes stored", "/", daves, "footnotes");
            send(RestCall.json(
                    "dave",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + daves + fields,
                    "{\"meta\":{\"footnotes\":\"not json\"}}"));
            noteMeta("dave footnotes stored after not json", "/", daves, "footnotes");
            send(RestCall.json(
                    "dave", "PUT", "/wp-json/wp/v2/posts/" + daves + fields, "{\"meta\":{\"footnotes\":null}}"));
            noteMeta("dave footnotes stored after null", "/", daves, "footnotes");
            block = createPost(
                    "alice", "/wp/v2/blocks", "{\"title\":\"g13 block\",\"status\":\"publish\",\"content\":\"g13 b\"}");
            String blockFields = "?_fields=id,meta,wp_pattern_sync_status";
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/blocks/" + block + blockFields,
                    "{\"meta\":{\"wp_pattern_sync_status\":\"\"}}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/blocks/" + block + blockFields,
                    "{\"meta\":{\"wp_pattern_sync_status\":\"unsynced\"}}"));
            noteMeta("block sync status stored", "/", block, "wp_pattern_sync_status");
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/blocks/" + block + blockFields,
                    "{\"meta\":{\"wp_pattern_sync_status\":\"bogus\",\"footnotes\":\"[]\"}}"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/blocks/" + block + blockFields,
                    "{\"meta\":{\"wp_pattern_sync_status\":null}}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/blocks/" + block + blockFields));
            setMeta("/", block, "wp_pattern_sync_status", TextNode.valueOf("bogus"));
            send(RestCall.get("alice", "/wp-json/wp/v2/blocks/" + block + blockFields));
            published = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"g13 published\",\"status\":\"publish\",\"content\":\"g13 v1\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + published, "{\"content\":\"g13 v2\"}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + published + "/revisions?_fields=id"));
            send(RestCall.json(
                    "alice",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + published + fields,
                    "{\"title\":\"g13 changed\",\"content\":\"g13 v3\",\"meta\":{\"footnotes\":5}}"));
            send(RestCall.get(
                    "alice", "/wp-json/wp/v2/posts/" + published + "?context=edit&_fields=id,title,content,modified"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + published + "/revisions?_fields=id,content"));
            send(RestCall.get("erin", "/wp-json/wp/v2/posts/" + published + "?_fields=id,meta"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", published);
            deletePostIfCreated("/wp/v2/blocks", block);
            deletePostIfCreated("/wp/v2/posts", daves);
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void g14CreateWithAMetaError() throws Exception { // RP30.2
        List<Long> stray = new ArrayList<>();
        try {
            send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts",
                    "{\"title\":\"g14 created\",\"status\":\"draft\",\"meta\":{\"footnotes\":5}}"));
            Exchange found = send(RestCall.get(
                    "alice",
                    "/wp-json/wp/v2/posts?status=draft&search=g14+created&_fields=id,title,status,meta,categories"));
            stray.addAll(ids(found.json()));
            note("post exists after the 400", found.json().size() > 0 ? "yes" : "no");
            send(
                    RestCall.json(
                            "alice",
                            "POST",
                            "/wp-json/wp/v2/posts",
                            "{\"title\":\"g14 created too\",\"status\":\"draft\",\"categories\":[999999],\"meta\":{\"footnotes\":5}}"));
            Exchange foundToo = send(
                    RestCall.get(
                            "alice",
                            "/wp-json/wp/v2/posts?status=draft&search=g14+created+too&_fields=id,title,status,meta,categories"));
            stray.addAll(ids(foundToo.json()));
        } finally {
            for (long id : stray) {
                deletePost("/wp/v2/posts", id);
            }
        }
    }

    @Test
    void g15DeleteAndTrash() throws Exception { // RP31
        Long post = null;
        Long noTrash = null;
        try {
            post = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"g15 post\",\"slug\":\"g15-slug\",\"status\":\"publish\"}");
            send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + post, "{}"));
            noteMeta("_wp_trash_meta_status", "/", post, "_wp_trash_meta_status");
            send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + post, "{}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "?context=edit&_fields=id,status,slug"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + post + "?_fields=id,status,slug"));
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"status\":\"draft\"}"));
            noteMeta("_wp_trash_meta_status after untrash", "/", post, "_wp_trash_meta_status");
            noteMeta("_wp_desired_post_slug after untrash", "/", post, "_wp_desired_post_slug");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"status\":\"trash\"}"));
            send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + post + "?force=true", "{}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post));
            post = null;
            noTrash = createPost("alice", "/wp/v2/posts", "{\"title\":\"g15 no trash\",\"status\":\"publish\"}");
            try {
                option("/", "qp_parity_no_trash", TextNode.valueOf("1"));
                note(
                        "qp_parity_no_trash",
                        "harness rule: rest_{type}_trashable answers false (stand-in for EMPTY_TRASH_DAYS=0)");
                send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + noTrash, "{}"));
                send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + noTrash + "?force=false", "{}"));
                send(RestCall.json("dave", "DELETE", "/wp-json/wp/v2/posts/" + noTrash, "{}"));
            } finally {
                option("/", "qp_parity_no_trash", TextNode.valueOf("0"));
            }
            send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + noTrash + "?force=true", "{}"));
            noTrash = null;
        } finally {
            deletePostIfCreated("/wp/v2/posts", noTrash);
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void g15aMissingPostIs404() throws Exception { // RP15.1
        Exchange missing = send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/999999"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().get("message").asText()).isEqualTo("Invalid post ID.");
        assertThat(missing.header("Allow")).isEmpty();
        send(RestCall.get("alice", "/wp-json/wp/v2/posts/999999"));
        send(RestCall.get("alice", "/wp-json/wp/v2/posts/0"));
        send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/999999", "{\"title\":\"x\"}"));
        send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/999999", "{}"));
    }

    @Test
    void g16TermFilters() throws Exception { // RP46-RP49
        String prefix = createSite("g16");
        List<Long> posts = new ArrayList<>();
        List<Long> categories = new ArrayList<>();
        Long tag = null;
        try {
            long a = createTerm(prefix, "categories", "{\"name\":\"g16 a\"}");
            categories.add(a);
            long a1 = createTerm(prefix, "categories", "{\"name\":\"g16 a1\",\"parent\":" + a + "}");
            categories.add(a1);
            long b = createTerm(prefix, "categories", "{\"name\":\"g16 b\"}");
            categories.add(b);
            tag = createTerm(prefix, "tags", "{\"name\":\"g16 t\"}");
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g16 in a\",\"status\":\"publish\",\"categories\":[" + a + "]}"));
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g16 in a1\",\"status\":\"publish\",\"categories\":[" + a1 + "]}"));
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g16 in b tagged\",\"status\":\"publish\",\"categories\":[" + b + "],\"tags\":[" + tag
                            + "]}"));
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g16 aside\",\"status\":\"publish\",\"format\":\"aside\"}"));
            posts.add(createPost(
                    "alice",
                    prefix,
                    "/wp/v2/posts",
                    "{\"title\":\"g16 aside in a\",\"status\":\"publish\",\"format\":\"aside\",\"categories\":[" + a
                            + "]}"));
            posts.add(createPost("alice", prefix, "/wp/v2/posts", "{\"title\":\"g16 plain\",\"status\":\"publish\"}"));
            String fields = "&_fields=id,title,categories,tags,format";
            String base = prefix + "/wp-json/wp/v2/posts?per_page=20";
            send(RestCall.get("anonymous", base + "&categories=" + fields));
            send(RestCall.get("anonymous", base + "&categories=999" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + a + ",999" + fields));
            send(RestCall.get("anonymous", base + "&categories=abc" + fields));
            send(RestCall.get("anonymous", base + "&categories[terms]=" + tag + "&categories[bogus]=1" + fields));
            send(RestCall.get("anonymous", base + "&tags[terms]=" + tag + ",999&tags[operator]=AND" + fields));
            send(RestCall.get("anonymous", base + "&tags[terms]=" + tag + "&tags[operator]=AND" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + a + fields));
            send(RestCall.get(
                    "anonymous", base + "&categories[terms]=" + a + "&categories[include_children]=1" + fields));
            send(RestCall.get(
                    "anonymous", base + "&categories[terms]=" + a + "&categories[include_children]=true" + fields));
            send(RestCall.get(
                    "anonymous",
                    base + "&categories[terms]=" + a + "," + b
                            + "&categories[operator]=AND&categories[include_children]=1" + fields));
            send(RestCall.get("anonymous", base + "&categories_exclude=999" + fields));
            send(RestCall.get(
                    "anonymous",
                    base + "&categories_exclude[terms]=" + a + "&categories_exclude[include_children]=1" + fields));
            send(RestCall.get(
                    "anonymous",
                    base + "&categories_exclude[terms]=" + a + "&categories_exclude[operator]=AND" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + b + "&format=aside&tax_relation=OR" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + b + "&format=aside" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + a + "&format=aside" + fields));
            send(RestCall.get("anonymous", base + "&format=standard" + fields));
            send(RestCall.get("anonymous", base + "&format=aside,standard" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + b + "&tags=" + tag + "&tax_relation=OR" + fields));
            send(RestCall.get("anonymous", base + "&categories=" + a + "&tags=" + tag + fields));
            send(RestCall.get("anonymous", base + "&tax_relation=XOR" + fields));
            send(RestCall.get("anonymous", base + "&categories[terms]=a&categories=" + fields));
            send(RestCall.get("anonymous", base + "&categories=&categories[terms]=a" + fields));
            send(RestCall.get("anonymous", base + "&tags[terms]=" + tag + "&tags[include_children]=1" + fields));
            send(RestCall.get("anonymous", base + "&categories[]=" + a + "&categories[]=" + b + fields));
            send(RestCall.get("anonymous", base + "&categories=" + a + "%20" + b + fields));
            send(RestCall.get(
                    "anonymous",
                    base + "&categories[terms]=" + a1 + "&categories[include_children]=0&categories[operator]=OR"
                            + fields));
            send(RestCall.get("anonymous", base + "&categories=0," + a + fields));
        } finally {
            for (long post : posts) {
                deletePost(prefix, "/wp/v2/posts", post);
            }
            if (tag != null) {
                send(RestCall.json("alice", "DELETE", prefix + "/wp-json/wp/v2/tags/" + tag + "?force=true", "{}"));
            }
            for (int i = categories.size() - 1; i >= 0; i--) {
                send(RestCall.json(
                        "alice",
                        "DELETE",
                        prefix + "/wp-json/wp/v2/categories/" + categories.get(i) + "?force=true",
                        "{}"));
            }
        }
    }

    @Test
    void g17TermLinksOfATypeWithoutRestTaxonomies() throws Exception { // RP32.10
        Long book = null;
        Long plainBook = null;
        try {
            plainBook = createPost(
                    "alice", "/wp/v2/books", "{\"title\":\"g17 book without taxonomies\",\"status\":\"publish\"}");
            send(RestCall.get("alice", "/wp-json/wp/v2/books/" + plainBook + "?_fields=id,_links,class_list"));
            try {
                option("/", "qp_parity_book_formats", TextNode.valueOf("1"));
                note(
                        "qp_parity_book_formats",
                        "harness rule: post_format (not in REST) attached to book through register_taxonomy_for_object_type");
                JsonNode attached = site.operation("taxonomy_args", args().put("name", "post_format"));
                VerificationFacts.note(
                        item, "post_format object_type while attached", attached.at("/args/object_type"));
                book = insert("/", "alice", "book", "publish", "g17 book", fields().put("format", "aside"));
                send(RestCall.get("alice", "/wp-json/wp/v2/books/" + book + "?_fields=id,_links,class_list"));
                send(RestCall.get("alice", "/wp-json/wp/v2/books/" + book + "?context=edit"));
                send(RestCall.get("alice", "/wp-json/wp/v2/types/book?context=edit&_fields=taxonomies,supports"));
                send(new RestCall(
                        "alice", "OPTIONS", "/wp-json/wp/v2/books", Map.of(), Optional.empty(), Optional.empty()));
                send(RestCall.get("anonymous", "/wp-json/wp/v2/pages?per_page=1&_fields=id,_links.wp:term"));
            } finally {
                option("/", "qp_parity_book_formats", TextNode.valueOf("0"));
            }
        } finally {
            deletePostIfCreated("/wp/v2/books", book);
            deletePostIfCreated("/wp/v2/books", plainBook);
        }
    }

    @Test
    void g18BlockAttributeRoundTrip() throws Exception { // RP35.2
        Long post = null;
        try {
            String content =
                    "<!-- wp:paragraph {\\\"className\\\":\\\"a<b--c&d\\\\\\\"e>f\\\",\\\"anchor\\\":\\\"x--y\\\"} --><p class=\\\"a<b--c\\\">g18 <b>text</b> -- here</p><!-- /wp:paragraph -->"
                            + "<!-- wp:template-part {\\\"slug\\\":\\\"header\\\"} /-->";
            post = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"g18 post\",\"status\":\"publish\",\"content\":\"" + content + "\"}");
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "?context=edit&_fields=id,content"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + post + "?_fields=id,content"));
            send(
                    RestCall.json(
                            "alice",
                            "PUT",
                            "/wp-json/wp/v2/posts/" + post + "?_fields=id,content",
                            "{\"content\":\"<!-- wp:paragraph {\\\"className\\\":\\\"u003c\\\"} --><p>g18 two</p><!-- /wp:paragraph -->\"}"));
            send(RestCall.json(
                    "bob",
                    "PUT",
                    "/wp-json/wp/v2/posts/" + post + "?_fields=id,content",
                    "{\"content\":\"" + content + "\"}"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    @Test
    void g19BlocksVisibility() throws Exception { // RP36.2
        Long block = null;
        try {
            block = createPost(
                    "alice", "/wp/v2/blocks", "{\"title\":\"g19 block\",\"status\":\"publish\",\"content\":\"g19 b\"}");
            send(RestCall.get("anonymous", "/wp-json/wp/v2/blocks?_fields=id"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/blocks/" + block));
            send(RestCall.get("erin", "/wp-json/wp/v2/blocks?_fields=id"));
            send(RestCall.get("erin", "/wp-json/wp/v2/blocks/" + block));
            send(RestCall.get(
                    "dave", "/wp-json/wp/v2/blocks/" + block + "?_fields=id,title,content,wp_pattern_sync_status"));
            send(RestCall.get("dave", "/wp-json/wp/v2/blocks?_fields=id"));
        } finally {
            deletePostIfCreated("/wp/v2/blocks", block);
        }
    }

    @Test
    void g20Search() throws Exception { // RP51-RP55
        String prefix = createSite("g20");
        List<String[]> created = new ArrayList<>();
        Long category = null;
        try {
            // The category is made in pretty mode: under php -S a subdirectory site has no /wp-json/ in plain mode.
            category = createTerm(prefix, "categories", "{\"name\":\"g20cat\"}");
            option("/g20/", "permalink_structure", TextNode.valueOf(""));
            String[][] fixtures = {
                {"/wp/v2/posts", "{\"title\":\"g20 alpha\",\"status\":\"publish\",\"content\":\"g20 alpha text\"}"},
                {"/wp/v2/pages", "{\"title\":\"g20 beta\",\"status\":\"publish\"}"},
                {"/wp/v2/posts", "{\"title\":\"g20 aside\",\"status\":\"publish\",\"format\":\"aside\"}"},
                {
                    "/wp/v2/posts",
                    "{\"title\":\"g20 locked\",\"status\":\"publish\",\"content\":\"g20 secretword\",\"password\":\"s3\"}"
                },
                {"/wp/v2/books", "{\"title\":\"g20 gamma\",\"status\":\"publish\"}"},
                {"/wp/v2/posts", "{\"title\":\"g20 draft delta\",\"status\":\"draft\"}"},
                {"/wp/v2/blocks", "{\"title\":\"g20 block\",\"status\":\"publish\",\"content\":\"g20 b\"}"},
            };
            for (String[] fixture : fixtures) {
                Exchange made = send(
                        RestCall.json("alice", "POST", prefix + "/index.php?rest_route=" + fixture[0], fixture[1]));
                assertThat(made.status())
                        .as("fixture %s: %s", fixture[1], made.text())
                        .isEqualTo(201);
                created.add(new String[] {
                    fixture[0], String.valueOf(made.json().get("id").longValue())
                });
            }
            String search = prefix + "/index.php?rest_route=/wp/v2/search";
            send(RestCall.get("anonymous", search + "&search=g20"));
            send(RestCall.get("anonymous", search + "&search=g20&type=term&subtype=page"));
            send(RestCall.get("anonymous", search + "&search=g20&type=term"));
            send(RestCall.get("anonymous", search + "&search=g20cat&type=term"));
            send(RestCall.get("anonymous", search + "&search=g20&context=edit"));
            send(RestCall.get("anonymous", search + "&search=g20nothing&page=2"));
            send(RestCall.get("anonymous", search + "&search=g20&per_page=1&page=2"));
            send(RestCall.get("anonymous", search + "&search=g20&per_page=1&page=9"));
            send(RestCall.get("anonymous", search + "&type=post-format"));
            send(RestCall.get("anonymous", search + "&search=aside&type=post-format"));
            send(RestCall.get("anonymous", search + "&search=gallery&type=post-format"));
            send(RestCall.get("anonymous", search + "&type=post-format&subtype=page&include=1"));
            send(RestCall.get("anonymous", search + "&search=secretword"));
            send(RestCall.get("alice", search + "&search=secretword"));
            send(RestCall.get("anonymous", search + "&type=post&subtype=book"));
            send(RestCall.get("anonymous", search + "&search=g20&type=post&subtype=category"));
            send(RestCall.get("anonymous", search + "&search=g20&type=post&subtype=page,book"));
            send(RestCall.get("anonymous", search + "&search=g20&type=bogus"));
            send(RestCall.get("anonymous", search + "&search=g20&subtype=bogus"));
            send(RestCall.get("anonymous", search + "&search=g20&type=post&_embed"));
            send(RestCall.get("anonymous", search + "&search=g20&type=term&_fields=id,title,type,subtype"));
            send(RestCall.get("alice", search + "&search=g20&type=post"));
            send(new RestCall(
                    "anonymous", "HEAD", search + "&search=g20", Map.of(), Optional.empty(), Optional.empty()));
            option("/g20/", "permalink_structure", TextNode.valueOf("/%postname%/"));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/search?type=post-format"));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/search?search=g20&type=term"));
            send(RestCall.get("anonymous", prefix + "/wp-json/wp/v2/search?search=g20&per_page=2"));
        } finally {
            option("/g20/", "permalink_structure", TextNode.valueOf("/%postname%/"));
            for (int i = created.size() - 1; i >= 0; i--) {
                deletePost(prefix, created.get(i)[0], Long.parseLong(created.get(i)[1]));
            }
            if (category != null) {
                send(RestCall.json(
                        "alice", "DELETE", prefix + "/wp-json/wp/v2/categories/" + category + "?force=true", "{}"));
            }
        }
    }

    @Test
    void g21NewPostAutoDraft() throws Exception { // RP57-RP58
        Long autoDraft = null;
        Long pageDraft = null;
        try {
            option("/", "default_post_format", TextNode.valueOf("aside"));
            Session carol = login("carol");
            long before = latestPost("/", "post", "auto-draft");
            Exchange page = send(asSession(carol, RestCall.get("carol", "/wp-admin/post-new.php")));
            note("post-new status", page.status());
            long id = latestPost("/", "post", "auto-draft");
            assertThat(id).as("post-new.php created an auto-draft").isGreaterThan(before);
            note("auto-draft id", (int) id);
            note("auto-draft is new", id > before ? "yes" : "no");
            note(
                    "page names post_ID",
                    page.text().contains("name=\"post_ID\" value=\"" + id + "\"")
                                    || page.text().contains("'post_ID' value='" + id + "'")
                            ? "yes"
                            : "no");
            note("page initializeEditor", excerptAround(page.text(), "initializeEditor", 300));
            if (id > before) {
                autoDraft = id;
                noteMeta("_edit_lock", "/", id, "_edit_lock");
                noteMeta("_edit_last", "/", id, "_edit_last");
                noteMeta("_wp_page_template", "/", id, "_wp_page_template");
                send(RestCall.get("carol", "/wp-json/wp/v2/posts/" + id + "?context=edit"));
                send(
                        RestCall.get(
                                "alice",
                                "/wp-json/wp/v2/posts/" + id
                                        + "?context=edit&_fields=id,status,title,date,date_gmt,modified,modified_gmt,slug,guid,link,comment_status,ping_status,categories,format,meta,generated_slug,permalink_template"));
                send(RestCall.get("bob", "/wp-json/wp/v2/posts/" + id));
                send(RestCall.get("anonymous", "/wp-json/wp/v2/posts/" + id));
                send(RestCall.get("dave", "/wp-json/wp/v2/posts/" + id));
                send(RestCall.get("carol", "/wp-json/wp/v2/posts?status=auto-draft&_fields=id,status"));
                send(RestCall.get("carol", "/wp-json/wp/v2/posts/" + id + "/autosaves?context=edit"));
                send(RestCall.json(
                        "carol",
                        "PUT",
                        "/wp-json/wp/v2/posts/" + id + "?_fields=id,status,title,date,date_gmt,categories,format,slug",
                        "{\"status\":\"draft\",\"title\":\"\"}"));
                send(RestCall.json(
                        "carol",
                        "PUT",
                        "/wp-json/wp/v2/posts/" + id + "?_fields=id,status,title,date,date_gmt,categories,format,slug",
                        "{\"status\":\"draft\",\"title\":\"\",\"content\":\"g21 first save\"}"));
                send(
                        RestCall.get(
                                "carol",
                                "/wp-json/wp/v2/posts/" + id
                                        + "?context=edit&_fields=id,status,title,date,date_gmt,modified,modified_gmt,slug,categories,format,generated_slug"));
            }
            long pageBefore = latestPost("/", "page", "auto-draft");
            send(asSession(carol, RestCall.get("carol", "/wp-admin/post-new.php?post_type=page")));
            // An author has no edit_pages; the page auto-draft is recorded for an editor.
            Session bob = login("bob");
            send(asSession(bob, RestCall.get("bob", "/wp-admin/post-new.php?post_type=page")));
            long pageId = latestPost("/", "page", "auto-draft");
            if (pageId > pageBefore) {
                pageDraft = pageId;
                send(
                        RestCall.get(
                                "alice",
                                "/wp-json/wp/v2/pages/" + pageId
                                        + "?context=edit&_fields=id,status,title,date,date_gmt,comment_status,ping_status,template,slug,guid,link,generated_slug,permalink_template"));
            }
            Session dave = login("dave");
            send(asSession(dave, RestCall.get("dave", "/wp-admin/post-new.php?post_type=book")));
            Session erin = login("erin");
            send(asSession(erin, RestCall.get("erin", "/wp-admin/post-new.php")));
            send(RestCall.get("anonymous", "/wp-admin/post-new.php"));
        } finally {
            option("/", "default_post_format", TextNode.valueOf("0"));
            deletePostIfCreated("/wp/v2/pages", pageDraft);
            deletePostIfCreated("/wp/v2/posts", autoDraft);
        }
    }

    @Test
    void g22EmptyUpdateOfAPublishedPost() throws Exception { // RP29.3
        Long post = null;
        try {
            post = createPost(
                    "alice", "/wp/v2/posts", "{\"title\":\"g22 post\",\"status\":\"publish\",\"content\":\"g22 v1\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"content\":\"g22 v2\"}"));
            String fields =
                    "?context=edit&_fields=id,modified,modified_gmt,date,_links.version-history,_links.predecessor-version";
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            pause();
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + fields));
            pause();
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/posts/" + post + fields, "{\"id\":" + post + "}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?_fields=id,date"));
        } finally {
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    // ---- extra items ------------------------------------------------------------------------------------------

    @Test
    void xPostFormatRegistration() throws Exception {
        JsonNode taxonomy = site.operation("taxonomy_args", args().put("name", "post_format"));
        VerificationFacts.note(item, "taxonomy_args", taxonomy.get("args"));
        send(RestCall.get("alice", "/wp-json/wp/v2/taxonomies/post_format?context=edit"));
        send(RestCall.get("alice", "/wp-json/wp/v2/taxonomies?context=edit&type=post&_fields=post_format"));
        send(RestCall.get("alice", "/wp-json/wp/v2/types/post?context=edit&_fields=taxonomies,supports"));
    }

    @Test
    void xBookTypeRegistration() throws Exception {
        JsonNode type = site.operation("type_args", args().put("name", "book"));
        VerificationFacts.note(item, "type_args", type.get("args"));
        send(RestCall.get("alice", "/wp-json/wp/v2/types/book?context=edit"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/types/book"));
        send(RestCall.get("alice", "/wp-json/wp/v2/types?context=edit&_fields=book"));
    }

    @Test
    void optionsOfTheContentRoutesAreSaved() throws Exception {
        Long post = null;
        Long page = null;
        Long book = null;
        try {
            post = createPost(
                    "alice",
                    "/wp/v2/posts",
                    "{\"title\":\"options post\",\"status\":\"publish\",\"content\":\"options v1\"}");
            revise(post, 1, "options v");
            send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/posts/" + post + "/autosaves",
                    "{\"content\":\"options autosave\"}"));
            page = createPost(
                    "alice",
                    "/wp/v2/pages",
                    "{\"title\":\"options page\",\"status\":\"publish\",\"content\":\"options p1\"}");
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/pages/" + page, "{\"content\":\"options p2\"}"));
            book = createPost("alice", "/wp/v2/books", "{\"title\":\"options book\",\"status\":\"publish\"}");
            long revision = firstRevisionId(post);
            Map<String, String> routes = new java.util.LinkedHashMap<>();
            routes.put("/wp/v2/posts", "/wp/v2/posts");
            routes.put("/wp/v2/posts/" + post, "/wp/v2/posts/{id}");
            routes.put("/wp/v2/pages", "/wp/v2/pages");
            routes.put("/wp/v2/pages/" + page, "/wp/v2/pages/{id}");
            routes.put("/wp/v2/books", "/wp/v2/books");
            routes.put("/wp/v2/books/" + book, "/wp/v2/books/{id}");
            routes.put("/wp/v2/posts/" + post + "/revisions", "/wp/v2/posts/{id}/revisions");
            routes.put("/wp/v2/posts/" + post + "/revisions/" + revision, "/wp/v2/posts/{id}/revisions/{rid}");
            routes.put("/wp/v2/posts/" + post + "/autosaves", "/wp/v2/posts/{id}/autosaves");
            routes.put("/wp/v2/posts/" + post + "/autosaves/" + revision, "/wp/v2/posts/{id}/autosaves/{rid}");
            routes.put("/wp/v2/pages/" + page + "/revisions", "/wp/v2/pages/{id}/revisions");
            routes.put("/wp/v2/search", "/wp/v2/search");
            for (Map.Entry<String, String> route : routes.entrySet()) {
                Exchange options = client.send(new RestCall(
                        "alice", "OPTIONS", "/wp-json" + route.getKey(), Map.of(), Optional.empty(), Optional.empty()));
                assertThat(options.status()).as("OPTIONS %s", route.getKey()).isEqualTo(200);
                VerificationFacts.saveOptions(route.getValue(), options);
            }
        } finally {
            deletePostIfCreated("/wp/v2/books", book);
            deletePostIfCreated("/wp/v2/pages", page);
            deletePostIfCreated("/wp/v2/posts", post);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Sends the call and records the exchange under the running item, if any. */
    Exchange send(RestCall call) throws Exception {
        Exchange exchange = client.send(call);
        if (item != null) {
            VerificationFacts.record(item, exchange);
        }
        return exchange;
    }

    void note(String key, String value) {
        VerificationFacts.note(item, key, TextNode.valueOf(value));
    }

    void note(String key, int value) {
        VerificationFacts.note(item, key, IntNode.valueOf(value));
    }

    void noteKeys(String key, JsonNode body) {
        ArrayNode keys = OracleJson.MAPPER.createArrayNode();
        JsonNode first = body.isArray() && body.size() > 0 ? body.get(0) : body;
        first.fieldNames().forEachRemaining(keys::add);
        VerificationFacts.note(item, key, keys);
    }

    void noteMeta(String key, String sitePath, long post, String metaKey) throws Exception {
        ArrayNode values = OracleJson.MAPPER.createArrayNode();
        meta(sitePath, post, metaKey).forEach(values::add);
        VerificationFacts.note(item, key, values);
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static ObjectNode fields() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode entry : list) {
                if (entry.path("id").isIntegralNumber()) {
                    ids.add(entry.get("id").longValue());
                }
            }
        }
        return ids;
    }

    static void pause() throws InterruptedException {
        Thread.sleep(1100);
    }

    static String excerptAround(String text, String marker, int length) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return "(absent)";
        }
        return text.substring(at, Math.min(text.length(), at + length));
    }

    static String timeTags(String html) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int at = html.indexOf("<time", from);
            if (at < 0 || out.length() > 2000) {
                break;
            }
            int end = html.indexOf("</time>", at);
            if (end < 0) {
                break;
            }
            out.append(html, at, end + 7).append('\n');
            from = end + 7;
        }
        return out.isEmpty() ? "(none)" : out.toString();
    }

    /** Creates a subdirectory site {@code /<id>/} with the five users and answers its URL prefix {@code /<id>}. */
    String createSite(String id) throws Exception {
        site.operation("create_site", args().put("path", "/" + id + "/").put("title", id));
        // Harness rule: the fixture sites hold the custom type book (qp-parity-mu.php's switch).
        option("/" + id + "/", BOOK_SWITCH, TextNode.valueOf("1"));
        return "/" + id;
    }

    void option(String sitePath, String name, JsonNode value) throws Exception {
        ObjectNode input = args().put("name", name);
        input.set("value", value);
        if (!sitePath.equals("/")) {
            input.put("site", sitePath);
        }
        site.operation("option", input);
    }

    long insert(String sitePath, String as, String type, String status, String title, ObjectNode fields)
            throws Exception {
        ObjectNode input = args().put("site", sitePath)
                .put("as", as)
                .put("type", type)
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return site.operation("post", input).get("id").longValue();
    }

    List<String> meta(String sitePath, long post, String key) throws Exception {
        JsonNode values = site.operation(
                        "meta", args().put("site", sitePath).put("post", post).put("key", key))
                .get("values");
        List<String> out = new ArrayList<>();
        for (JsonNode value : values) {
            out.add(value.isTextual() ? value.textValue() : value.toString());
        }
        return out;
    }

    void setMeta(String sitePath, long post, String key, JsonNode value) throws Exception {
        ObjectNode input = args().put("site", sitePath).put("post", post).put("key", key);
        input.set("value", value);
        site.operation("set_meta", input);
    }

    long latestPost(String sitePath, String type, String status) throws Exception {
        return site.operation(
                        "latest_post",
                        args().put("site", sitePath).put("type", type).put("status", status))
                .get("id")
                .longValue();
    }

    Session login(String user) throws Exception {
        JsonNode answer = site.operation("cookie_session", args().put("user", user));
        StringBuilder cookie = new StringBuilder();
        for (JsonNode entry : answer.get("cookies")) {
            if (!cookie.isEmpty()) {
                cookie.append("; ");
            }
            cookie.append(entry.get("name").asText())
                    .append('=')
                    .append(entry.get("value").asText());
        }
        return new Session(
                user,
                cookie.toString(),
                answer.get("rest_nonce").asText(),
                answer.get("session").asText());
    }

    String nonce(Session session, String action) throws Exception {
        return site.operation(
                        "nonce",
                        args().put("user", session.user())
                                .put("session", session.token())
                                .put("action", action))
                .get("nonce")
                .asText();
    }

    static RestCall asSession(Session session, RestCall call) {
        return call.withHeader("Cookie", session.cookie()).withHeader("X-WP-Nonce", session.nonce());
    }

    static RestCall form(String as, String target, String body) {
        return new RestCall(
                as,
                "POST",
                target,
                Map.of(),
                Optional.of("application/x-www-form-urlencoded"),
                Optional.of(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    RestCall heartbeat(Session session, long post) throws Exception {
        String nonce = nonce(session, "heartbeat-nonce");
        return form(
                session.user(),
                "/wp-admin/admin-ajax.php",
                "action=heartbeat&_nonce=" + nonce + "&" + enc("data[wp-refresh-post-lock][post_id]") + "=" + post + "&"
                        + enc("data[wp-refresh-post-lock][lock]") + "=&interval=10&screen_id=post&has_focus=true");
    }

    RestCall removeLock(Session session, long post, String activeLock) throws Exception {
        String nonce = nonce(session, "update-post_" + post);
        return form(
                session.user(),
                "/wp-admin/admin-ajax.php",
                "action=wp-remove-post-lock&_wpnonce=" + nonce + "&post_ID=" + post + "&active_post_lock="
                        + enc(activeLock));
    }

    RestCall classicPreview(Session session, long post, String title) throws Exception {
        String nonce = nonce(session, "update-post_" + post);
        return form(
                session.user(),
                "/wp-admin/post.php",
                "wp-preview=dopreview&post_ID=" + post + "&_wpnonce=" + nonce + "&post_type=post&post_author="
                        + site.userId(session.user()) + "&post_title=" + enc(title) + "&content="
                        + enc(title + " content")
                        + "&excerpt=&post_status=draft&original_post_status=draft");
    }

    /** Creates a post of the route as the user; the id of the new post. */
    long createPost(String as, String base, String json) throws Exception {
        return createPost(as, "", base, json);
    }

    long createPost(String as, String prefix, String base, String json) throws Exception {
        Exchange created = send(RestCall.json(as, "POST", prefix + "/wp-json" + base, json));
        assertThat(created.status())
                .as("fixture %s %s: %s", base, json, created.text())
                .isEqualTo(201);
        return created.json().get("id").longValue();
    }

    long createTerm(String prefix, String base, String json) throws Exception {
        Exchange created = send(RestCall.json("alice", "POST", prefix + "/wp-json/wp/v2/" + base, json));
        assertThat(created.status())
                .as("fixture %s %s: %s", base, json, created.text())
                .isEqualTo(201);
        return created.json().get("id").longValue();
    }

    /** Updates the post's content {@code count} times (one revision each), as alice. */
    void revise(long post, int count, String contentPrefix) throws Exception {
        for (int i = 2; i <= count + 1; i++) {
            pause();
            send(RestCall.json(
                    "alice", "PUT", "/wp-json/wp/v2/posts/" + post, "{\"content\":\"" + contentPrefix + i + "\"}"));
        }
    }

    /** The newest revision id of the post (the first of the default list). */
    long firstRevisionId(long post) throws Exception {
        JsonNode list = send(RestCall.get("alice", "/wp-json/wp/v2/posts/" + post + "/revisions?_fields=id"))
                .json();
        List<Long> ids = ids(list);
        if (ids.isEmpty()) {
            throw new AssertionError("post " + post + " has no revision: " + list);
        }
        return ids.get(0);
    }

    void deletePost(String base, long id) throws Exception {
        deletePost("", base, id);
    }

    void deletePost(String prefix, String base, long id) throws Exception {
        send(RestCall.json("alice", "DELETE", prefix + "/wp-json" + base + "/" + id + "?force=true", "{}"));
    }

    void deletePostIfCreated(String base, Long id) throws Exception {
        if (id != null) {
            deletePost(base, id);
        }
    }

    void deletePostIfCreated(String prefix, String base, Long id) throws Exception {
        if (id != null) {
            deletePost(prefix, base, id);
        }
    }

    /** Remembers a post that a refusal-probing request created anyway, so the item deletes it. */
    static void stray(Exchange exchange, List<Long> stray) {
        if (exchange.status() == 201) {
            stray.add(exchange.json().get("id").longValue());
        }
    }
}
