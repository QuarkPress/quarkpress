package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.QueryVars;
import org.quarkword.front.runtime.FlagParser;
import org.quarkword.front.runtime.PagePaths;
import org.quarkword.front.runtime.RequestParser;
import org.quarkword.front.runtime.Structure;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostTypes;
import org.quarkword.taxonomy.Taxonomies;

/** The queried object a {@code pagename} path resolves to over stored pages, attachments and posts. */
class QueriedObjectDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(QueriedObjectDbTest.class);

    private static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    static SiteId site;
    static UserId alice;
    static PostId about;
    static PostId team;
    static PostId blog;
    static PostId logoAttachment;
    static PostId logoPost;
    static PostId banner;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    PagePaths pages;

    @Inject
    PostTypes postTypes;

    @Inject
    Taxonomies taxonomies;

    @BeforeEach
    void cast() {
        if (site == null) {
            site = TestData.site(dataSource);
            alice = TestData.profile(dataSource, "alice", "alice");
            TestData.member(dataSource, TestData.MAIN, alice, "[\"author\"]");
            TestData.member(dataSource, site, alice, "[\"author\"]");
            about = post("page", "publish", "about", null);
            team = post("page", "publish", "team", about);
            blog = post("page", "publish", "blog", null);
            logoAttachment = post("attachment", "inherit", "logo", null);
            logoPost = post("post", "publish", "logo", null);
            banner = post("attachment", "inherit", "banner", null);
        }
    }

    private PostId post(String type, String status, String slug, PostId parent) {
        return TestData.post(dataSource, site, type, status, slug, alice, parent, "2025-11-20T10:00:00Z", false);
    }

    private FlagParser.Parsed parse(String pagename, SiteOptions options, String structure) throws Exception {
        QueryVars vars = QueryVars.of(Map.of("pagename", pagename));
        RequestParser.Registries registries = new RequestParser.Registries(postTypes.all(), taxonomies.all());
        return Identities.onSite(
                site,
                Identities.anonymous(),
                () -> FlagParser.parse(vars, options, registries, pages, Structure.of(structure, "", "")));
    }

    private static SiteOptions options(Map<String, String> values) {
        return new SiteOptions() {
            @Override
            public Optional<JsonNode> get(String name) {
                return Optional.ofNullable(values.get(name)).map(TextNode::valueOf);
            }

            @Override
            public String string(String name, String fallback) {
                return values.getOrDefault(name, fallback);
            }

            @Override
            public long number(String name, long fallback) {
                return Optional.ofNullable(values.get(name))
                        .map(Long::parseLong)
                        .orElse(fallback);
            }

            @Override
            public boolean flag(String name, boolean fallback) {
                String value = values.get(name);
                return value == null ? fallback : !(value.isEmpty() || value.equals("0"));
            }
        };
    }

    private static final SiteOptions POSTS_ON_FRONT = options(Map.of("show_on_front", "posts"));

    @Test
    void aPagePathResolvesToThePage() throws Exception {
        FlagParser.Parsed parsed = parse("about/team", POSTS_ON_FRONT, DEFAULT);
        assertThat(parsed.flags()).isEqualTo(FrontFlags.none().withPage(true).withSingular(true));
        assertThat(parsed.queriedFromPath()).containsInstanceOf(QueriedObject.Post.class);
        assertThat(parsed.queriedFromPath().map(QueriedObject::id)).contains(team.value());
        PostSnapshot page = ((QueriedObject.Post) parsed.queriedFromPath().orElseThrow()).post();
        assertThat(page.slug()).isEqualTo("team");
        assertThat(page.parent()).contains(about);
        assertThat(parse("team", POSTS_ON_FRONT, DEFAULT).queriedFromPath()).isEmpty();
    }

    @Test
    void thePostsPageIsResolvedAsSuch() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("show_on_front", "page");
        values.put("page_on_front", "0");
        values.put("page_for_posts", Long.toString(blog.value()));
        FlagParser.Parsed parsed = parse("blog", options(values), DEFAULT);
        assertThat(parsed.flags()).isEqualTo(FrontFlags.none().withHome(true).withPostsPage(true));
        assertThat(parsed.queriedFromPath()).containsInstanceOf(QueriedObject.PostsPage.class);
        assertThat(parsed.queriedFromPath().map(QueriedObject::id)).contains(blog.value());
        // with the posts on the front it is an ordinary page
        FlagParser.Parsed page = parse("blog", POSTS_ON_FRONT, DEFAULT);
        assertThat(page.flags()).isEqualTo(FrontFlags.none().withPage(true).withSingular(true));
        assertThat(page.queriedFromPath()).containsInstanceOf(QueriedObject.Post.class);
    }

    @Test
    void anAttachmentPathGivesWayToAPostOfTheSameSlugUnderAPostnameFirstStructure() throws Exception {
        FlagParser.Parsed dated = parse("logo", POSTS_ON_FRONT, DEFAULT);
        assertThat(dated.flags()).isEqualTo(FrontFlags.none().withPage(true).withSingular(true));
        assertThat(dated.queriedFromPath().map(QueriedObject::id)).contains(logoAttachment.value());
        FlagParser.Parsed postname = parse("logo", POSTS_ON_FRONT, "/%postname%/");
        assertThat(postname.flags())
                .isEqualTo(FrontFlags.none().withSingle(true).withSingular(true));
        assertThat(postname.queriedFromPath().map(QueriedObject::id)).contains(logoPost.value());
        assertThat(parse("about/team", POSTS_ON_FRONT, "/%postname%/")
                        .queriedFromPath()
                        .map(QueriedObject::id))
                .contains(team.value());
        // no post shares the path: the second lookup finds the attachment itself, and the request is single
        FlagParser.Parsed attachmentOnly = parse("banner", POSTS_ON_FRONT, "/%postname%/");
        assertThat(attachmentOnly.flags())
                .isEqualTo(FrontFlags.none().withSingle(true).withSingular(true));
        assertThat(attachmentOnly.queriedFromPath().map(QueriedObject::id)).contains(banner.value());
        assertThat(parse("banner", POSTS_ON_FRONT, DEFAULT).flags())
                .isEqualTo(FrontFlags.none().withPage(true).withSingular(true));
    }
}
