package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.MetaWrite;
import org.quarkpress.posts.PostType;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.RegisteredMeta;

class RegisteredMetaDbTest {

    /** A REST type with an editor, custom fields and revisions: it gets footnotes like a post. */
    @PostType(
            key = "book",
            publicly = true,
            restBase = "books",
            supports = {"title", "editor", "custom-fields", "revisions"})
    static class Book {}

    /** Keys of the book type: a rated integer, two protected keys (one with an allowing auth), a multi-valued tag. */
    @Singleton
    static class BookKeys implements MetaKeyProvider {

        static final List<String> authCalls = new CopyOnWriteArrayList<>();

        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(
                    MetaKeyDefinition.builder("book_rating")
                            .subtype("book")
                            .type("integer")
                            .single()
                            .showInRest()
                            .defaultValue(IntNode.valueOf(3))
                            .build(),
                    MetaKeyDefinition.builder("_book_code")
                            .subtype("book")
                            .single()
                            .showInRest()
                            .build(),
                    MetaKeyDefinition.builder("_book_lock")
                            .subtype("book")
                            .single()
                            .showInRest()
                            .auth((allowed, key, post, user, capability) -> {
                                authCalls.add(allowed + " " + key + " " + post.value() + " "
                                        + user.map(UserId::value).orElse(0L) + " " + capability);
                                return true;
                            })
                            .build(),
                    MetaKeyDefinition.builder("book_tag")
                            .subtype("book")
                            .showInRest()
                            .schema(JsonNodeFactory.instance
                                    .objectNode()
                                    .set(
                                            "items",
                                            JsonNodeFactory.instance
                                                    .objectNode()
                                                    .put("type", "string")))
                            .build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(RegisteredMetaDbTest.class, Book.class, BookKeys.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    RegisteredMeta meta;

    private static MetaKeyDefinition key(RegisteredMeta meta, String type, String key) {
        return meta.keys(type).stream()
                .filter(definition -> definition.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private PostId postBy(SiteId site, UserId author, String type, String status) {
        PostId post = TestData.post(dataSource, site, type, status, "", "Meta");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    private List<String> metaColumn(PostId post) {
        return Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value());
    }

    @Test
    void builtInKeysFollowTheTypesSupports() {
        assertThat(meta.restKeys("post")).extracting(MetaKeyDefinition::key).containsExactly("footnotes");
        assertThat(meta.restKeys("page")).extracting(MetaKeyDefinition::key).containsExactly("footnotes");
        assertThat(meta.restKeys("wp_block"))
                .extracting(MetaKeyDefinition::key)
                .containsExactly("wp_pattern_sync_status", "footnotes");
        assertThat(meta.restKeys("attachment")).isEmpty();
        assertThat(meta.revisionedKeys("post")).containsExactly("footnotes");
        MetaKeyDefinition sync = meta.restKeys("wp_block").get(0);
        assertThat(sync.schema().get("enum"))
                .containsExactly(TextNode.valueOf("partial"), TextNode.valueOf("unsynced"));
        assertThat(sync.emptyValue()).isEqualTo(TextNode.valueOf(""));
        assertThat(sync.single()).isTrue();
        assertThat(sync.revisionsEnabled()).isFalse();
        MetaKeyDefinition footnotes = meta.restKeys("post").get(0);
        assertThat(footnotes.schema().toString())
                .isEqualTo("{\"type\":\"string\",\"title\":\"\",\"description\":\"\"}");
        assertThat(footnotes.single()).isTrue();
        assertThat(footnotes.revisionsEnabled()).isTrue();
        assertThat(footnotes.isProtected()).isFalse();
        assertThat(footnotes.restKey()).isEqualTo("footnotes");
        assertThat(meta.keys("book"))
                .extracting(MetaKeyDefinition::key)
                .containsExactly("footnotes", "book_rating", "_book_code", "_book_lock", "book_tag");
    }

    @Test
    void theSyncStatusSanitizerIsTextFieldSanitizing() throws Exception {
        SiteId site = TestData.MAIN;
        UserId admin = TestData.memberOfMain(dataSource, "administrator");
        PostId block = postBy(site, admin, "wp_block", "publish");
        MetaKeyDefinition sync = key(meta, "wp_block", "wp_pattern_sync_status");
        Identities.onSite(site, Identities.user(admin), () -> {
            assertThat(meta.updateSingle(block, sync, TextNode.valueOf("  <b>un</b>synced\n ")))
                    .isEqualTo(MetaWrite.WRITTEN);
            assertThat(meta.stored(block, "wp_pattern_sync_status")).containsExactly(TextNode.valueOf("unsynced"));
            assertThat(meta.updateSingle(block, sync, TextNode.valueOf("par%41tial")))
                    .isEqualTo(MetaWrite.WRITTEN);
            assertThat(meta.stored(block, "wp_pattern_sync_status")).containsExactly(TextNode.valueOf("partial"));
            return null;
        });
    }

    @Test
    void anUnchangedValueNeedsNoPermission() throws Exception {
        SiteId site = TestData.MAIN;
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId post = postBy(site, editor, "post", "publish");
        MetaKeyDefinition footnotes = key(meta, "post", "footnotes");
        String stored = "[{\"id\":\"a\",\"content\":\"x\"}]";
        assertThat(Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> meta.updateSingle(post, footnotes, TextNode.valueOf(stored))))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(Identities.onSite(
                        site,
                        Identities.user(erin),
                        () -> meta.updateSingle(post, footnotes, TextNode.valueOf(stored))))
                .isEqualTo(MetaWrite.UNCHANGED);
        assertThat(Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> meta.updateSingle(post, footnotes, TextNode.valueOf(stored))))
                .isEqualTo(MetaWrite.UNCHANGED);
        String changed = "[{\"id\":\"b\",\"content\":\"y\"}]";
        assertThat(Identities.onSite(
                        site,
                        Identities.user(erin),
                        () -> meta.updateSingle(post, footnotes, TextNode.valueOf(changed))))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(site, Identities.user(erin), () -> meta.delete(post, footnotes)))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> meta.stored(post, "footnotes")))
                .containsExactly(TextNode.valueOf(stored));
        // the editor: a value equal after sanitizing is unchanged too, a change is written, a delete empties the key
        assertThat(Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> meta.updateSingle(post, footnotes, TextNode.valueOf(stored))))
                .isEqualTo(MetaWrite.UNCHANGED);
        assertThat(Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> meta.updateSingle(post, footnotes, TextNode.valueOf(changed))))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.delete(post, footnotes)))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.delete(post, footnotes)))
                .isEqualTo(MetaWrite.UNCHANGED);
        assertThat(metaColumn(post)).containsExactly("{}");
    }

    @Test
    void scalarsCompareAsPhpStringsWhenLookingForAnUnchangedValue() throws Exception {
        SiteId site = TestData.MAIN;
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId book = postBy(site, editor, "book", "publish");
        MetaKeyDefinition rating = key(meta, "book", "book_rating");
        assertThat(Identities.onSite(
                        site, Identities.user(editor), () -> meta.updateSingle(book, rating, IntNode.valueOf(4))))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(Identities.onSite(
                        site, Identities.user(erin), () -> meta.updateSingle(book, rating, TextNode.valueOf("4"))))
                .isEqualTo(MetaWrite.UNCHANGED);
        assertThat(Identities.onSite(
                        site, Identities.user(erin), () -> meta.updateSingle(book, rating, IntNode.valueOf(5))))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.stored(book, "book_rating")))
                .containsExactly(IntNode.valueOf(4));
    }

    @Test
    void aProtectedKeyNeedsAnAllowingAuthAndEditPostAlways() throws Exception {
        SiteId site = TestData.MAIN;
        UserId admin = TestData.memberOfMain(dataSource, "administrator");
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId own = postBy(site, author, "book", "draft");
        MetaKeyDefinition code = key(meta, "book", "_book_code");
        MetaKeyDefinition lock = key(meta, "book", "_book_lock");
        assertThat(code.isProtected()).isTrue();
        assertThat(Identities.onSite(
                        site, Identities.user(admin), () -> meta.updateSingle(own, code, TextNode.valueOf("c"))))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(site, Identities.user(admin), () -> meta.delete(own, code)))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(
                        site, Identities.user(admin), () -> meta.replace(own, code, List.of(TextNode.valueOf("c")))))
                .isEqualTo(MetaWrite.DENIED);
        BookKeys.authCalls.clear();
        assertThat(Identities.onSite(
                        site, Identities.user(author), () -> meta.updateSingle(own, lock, TextNode.valueOf("l"))))
                .isEqualTo(MetaWrite.WRITTEN);
        // asked before the lock and again on the locked row
        assertThat(BookKeys.authCalls)
                .containsOnly("false _book_lock " + own.value() + " " + author.value() + " edit_post_meta");
        assertThat(Identities.onSite(
                        site, Identities.user(erin), () -> meta.updateSingle(own, lock, TextNode.valueOf("m"))))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(site, Identities.user(erin), () -> meta.delete(own, lock)))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(BookKeys.authCalls.get(BookKeys.authCalls.size() - 1)).endsWith(" delete_post_meta");
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> meta.stored(own, "_book_lock")))
                .containsExactly(TextNode.valueOf("l"));
        assertThat(Identities.onSite(site, Identities.user(author), () -> meta.delete(own, lock)))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(metaColumn(own)).containsExactly("{}");
    }

    @Test
    void multiValuedKeysAreDiffedInRequestOrder() throws Exception {
        SiteId site = TestData.MAIN;
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId book = postBy(site, editor, "book", "publish");
        MetaKeyDefinition tag = key(meta, "book", "book_tag");
        assertThat(tag.single()).isFalse();
        List<JsonNode> ab = List.of(TextNode.valueOf("a"), TextNode.valueOf("b"));
        List<JsonNode> bc = List.of(TextNode.valueOf("b"), TextNode.valueOf("c"));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.replace(book, tag, ab)))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(metaColumn(book)).containsExactly("{\"book_tag\":[\"a\",\"b\"]}");
        assertThat(Identities.onSite(site, Identities.user(erin), () -> meta.replace(book, tag, ab)))
                .isEqualTo(MetaWrite.DENIED);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.replace(book, tag, bc)))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(metaColumn(book)).containsExactly("{\"book_tag\":[\"b\",\"c\"]}");
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.replace(book, tag, bc)))
                .isEqualTo(MetaWrite.UNCHANGED);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.replace(book, tag, List.of())))
                .isEqualTo(MetaWrite.WRITTEN);
        assertThat(metaColumn(book)).containsExactly("{}");
    }

    @Test
    void aProviderKeyIsRegisteredForItsSubtype() {
        assertThat(meta.restKeys("book")).extracting(MetaKeyDefinition::key).contains("book_rating");
        assertThat(meta.restKeys("post")).extracting(MetaKeyDefinition::key).doesNotContain("book_rating");
        MetaKeyDefinition rating = key(meta, "book", "book_rating");
        assertThat(rating.emptyValue()).isEqualTo(IntNode.valueOf(3));
        assertThat(rating.schema().get("type").asText()).isEqualTo("integer");
        assertThat(rating.schema().get("default")).isEqualTo(IntNode.valueOf(3));
        assertThat(meta.revisionedKeys("book")).containsExactly("footnotes");
    }
}
