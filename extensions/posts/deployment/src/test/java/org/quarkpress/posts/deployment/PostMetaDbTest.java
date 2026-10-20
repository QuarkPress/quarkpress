package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostMeta;

class PostMetaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostMetaDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PostMeta meta;

    private PostId postBy(SiteId site, UserId author, String status) {
        PostId post = TestData.post(dataSource, site, "post", status, "", "Meta");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    @Test
    void valuesAreAddedReadAndKeptInOrderOnBothDatabases() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId post = postBy(site, editor, "publish");
        Identities.onSite(site, Identities.user(editor), () -> {
            assertThat(meta.add(post, "color", TextNode.valueOf("red"), false)).isTrue();
            assertThat(meta.add(post, "color", TextNode.valueOf("red"), true)).isFalse();
            assertThat(meta.add(
                            post,
                            "size",
                            JsonNodeFactory.instance.objectNode().put("z", 1).put("a", 2),
                            false))
                    .isTrue();
            return null;
        });
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value()))
                .containsExactly("{\"color\":[\"red\"],\"size\":[{\"z\":1,\"a\":2}]}");
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> meta.get(post, "color")))
                .containsExactly(TextNode.valueOf("red"));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.all(post))
                        .keySet())
                .containsExactly("color", "size");
    }

    @Test
    void updateAndDeleteFollowWordPress() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId post = postBy(site, editor, "draft");
        Identities.onSite(site, Identities.user(editor), () -> {
            assertThat(meta.update(post, "count", IntNode.valueOf(1))).isTrue();
            assertThat(meta.update(post, "count", IntNode.valueOf(1))).isFalse();
            assertThat(meta.add(post, "count", IntNode.valueOf(2), false)).isTrue();
            assertThat(meta.update(post, "count", IntNode.valueOf(3))).isTrue();
            assertThat(meta.get(post, "count")).containsExactly(IntNode.valueOf(3), IntNode.valueOf(3));
            assertThat(meta.delete(post, "count", IntNode.valueOf(3))).isTrue();
            assertThat(meta.get(post, "count")).isEmpty();
            assertThat(meta.add(post, "", IntNode.valueOf(1), false)).isFalse();
            assertThat(meta.add(post, "0", IntNode.valueOf(1), false)).isFalse();
            return null;
        });
    }

    @Test
    void writesNeedEditPostAndProtectedKeysTheSystem() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId author = TestData.memberOfMain(dataSource, "author");
        PostId editors = postBy(site, editor, "publish");
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(author), () -> meta.add(editors, "x", IntNode.valueOf(1), false)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post", site));
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.anonymous(), () -> meta.add(editors, "x", IntNode.valueOf(1), false)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("edit_post", site));
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(editor), () -> meta.add(editors, "_secret", IntNode.valueOf(1), false)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the meta key _secret is protected");
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(editor), () -> meta.update(editors, "_secret", IntNode.valueOf(1))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the meta key _secret is protected");
        assertThatThrownBy(
                        () -> Identities.onSite(site, Identities.user(editor), () -> meta.delete(editors, "_secret")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the meta key _secret is protected");
        assertThat(Identities.onSite(
                        site, Identities.user(editor), () -> meta.add(editors, "", IntNode.valueOf(1), false)))
                .isFalse();
        assertThat(lockRows(site)).isZero();
        Identities.onSite(site, Identities.system(), () -> {
            assertThat(meta.add(editors, "_secret", IntNode.valueOf(1), false)).isTrue();
            assertThat(meta.update(editors, "_secret", IntNode.valueOf(2))).isTrue();
            assertThat(meta.get(editors, "_secret")).containsExactly(IntNode.valueOf(2));
            assertThat(meta.delete(editors, "_secret", IntNode.valueOf(2))).isTrue();
            assertThat(meta.add(editors, "_secret", IntNode.valueOf(3), false)).isTrue();
            assertThat(meta.delete(editors, "_secret")).isTrue();
            assertThat(meta.all(editors)).isEmpty();
            return null;
        });
        assertThat(lockRows(site)).isEqualTo(1);
    }

    @Test
    void deleteOfAKeyThroughTheService() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId post = postBy(site, editor, "draft");
        Identities.onSite(site, Identities.user(editor), () -> {
            meta.add(post, "color", TextNode.valueOf("red"), false);
            meta.add(post, "color", TextNode.valueOf("blue"), false);
            assertThat(meta.delete(post, "color")).isTrue();
            assertThat(meta.delete(post, "color")).isFalse();
            assertThat(meta.get(post, "color")).isEmpty();
            return null;
        });
    }

    @Test
    void deleteWithAnEmptyNullOrFalseValueDeletesEveryValueAsWordPressDoes() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId post = postBy(site, editor, "draft");
        Identities.onSite(site, Identities.user(editor), () -> {
            for (JsonNode all : Arrays.asList(TextNode.valueOf(""), NullNode.instance, BooleanNode.FALSE, null)) {
                meta.add(post, "tag", TextNode.valueOf("a"), false);
                meta.add(post, "tag", TextNode.valueOf("b"), false);
                assertThat(meta.delete(post, "tag", all)).isTrue();
                assertThat(meta.get(post, "tag")).isEmpty();
                assertThat(meta.delete(post, "tag", all)).isFalse();
            }
            return null;
        });
    }

    private long lockRows(SiteId site) {
        return Jdbc.count(dataSource, "select count(*) from qp_post_lock where site_id = ?", site.value());
    }

    private static String lacks(String capability, SiteId site) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    @Test
    void anUnderscoreAfterNonPrintableCharactersStillProtectsTheKey() {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId post = postBy(site, editor, "publish");
        String key = new String(Character.toChars(0x200B)) + "_secret";
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(editor), () -> meta.add(post, key, IntNode.valueOf(1), false)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the meta key " + key + " is protected");
    }

    @Test
    void readingANonPublicPostNeedsReadPost() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        PostId privatePost = postBy(site, editor, "private");
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(subscriber), () -> meta.all(privatePost)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("read_post", site));
        PostId draft = postBy(site, editor, "draft");
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> meta.all(draft)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("read_post", site));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> meta.get(draft, "x")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("read_post", site));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.all(privatePost)))
                .isEmpty();
    }

    @Test
    void aPostOfAnotherSiteIsNotFound() throws Exception {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId elsewhere = postBy(other, editor, "publish");
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(editor), () -> meta.add(elsewhere, "x", IntNode.valueOf(1), false)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(editor), () -> meta.get(elsewhere, "x")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(editor), () -> meta.all(elsewhere)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
    }
}
