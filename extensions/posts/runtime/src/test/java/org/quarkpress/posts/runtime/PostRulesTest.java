package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostTypes;

class PostRulesTest {

    static final PostTypes TYPES = new RegisteredPostTypes(BuiltInPostTypes.all());

    @Test
    void theRevisionTypeIsRefusedAndOtherUnregisteredTypesAreKept() {
        assertThatThrownBy(() -> PostRules.type("revision"))
                .isInstanceOfSatisfying(PostException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("invalid_post_type");
                    assertThat(refused.detail()).isEqualTo("revision");
                })
                .hasMessage("Invalid post type.");
        assertThatThrownBy(() -> PostRules.type("Revision"))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post_type"));
        assertThat(PostRules.type("book")).isEqualTo("book");
        assertThat(PostRules.type("revisions")).isEqualTo("revisions");
    }

    @Test
    void typesAndStatusesAreSanitizedKeysWithDefaults() {
        assertThat(PostRules.type("Page ")).isEqualTo("page");
        assertThat(PostRules.type("")).isEqualTo("post");
        assertThat(PostRules.type("0")).isEqualTo("post");
        assertThat(PostRules.status("Publish", "post")).isEqualTo("publish");
        assertThat(PostRules.status("", "post")).isEqualTo("draft");
        assertThat(PostRules.status("0", "post")).isEqualTo("draft");
        assertThat(PostRules.status("approved", "post")).isEqualTo("approved");
    }

    @Test
    void attachmentsKeepOnlyTheirFourStatuses() {
        assertThat(PostRules.status("publish", "attachment")).isEqualTo("inherit");
        assertThat(PostRules.status("private", "attachment")).isEqualTo("private");
        assertThat(PostRules.status("trash", "attachment")).isEqualTo("trash");
        assertThat(PostRules.status("", "attachment")).isEqualTo("inherit");
        assertThat(PostRules.status("0", "attachment")).isEqualTo("inherit");
    }

    @Test
    void onlyTypesSupportingTitleEditorAndExcerptAreRefusedWhenEmpty() {
        assertThat(PostRules.maybeEmpty("post", "", "", "", TYPES)).isTrue();
        assertThat(PostRules.maybeEmpty("post", "0", "0", "", TYPES)).isTrue();
        assertThat(PostRules.maybeEmpty("post", "x", "", "", TYPES)).isFalse();
        assertThat(PostRules.maybeEmpty("page", "", "", "", TYPES)).isFalse();
        assertThat(PostRules.maybeEmpty("attachment", "", "", "", TYPES)).isFalse();
        assertThat(PostRules.maybeEmpty("wp_block", "", "", "", TYPES)).isTrue();
        assertThat(PostRules.maybeEmpty("approved", "", "", "", TYPES)).isFalse();
    }

    @Test
    void commentAndPingDefaultsDependOnTheType() {
        assertThat(PostRules.defaultCommentStatus("post", TYPES, "open")).isEqualTo("open");
        assertThat(PostRules.defaultPingStatus("post", TYPES, "open")).isEqualTo("open");
        assertThat(PostRules.defaultCommentStatus("page", TYPES, "open")).isEqualTo("closed");
        assertThat(PostRules.defaultPingStatus("page", TYPES, "open")).isEqualTo("closed");
        assertThat(PostRules.defaultCommentStatus("attachment", TYPES, "open")).isEqualTo("open");
        assertThat(PostRules.defaultPingStatus("attachment", TYPES, "open")).isEqualTo("closed");
        assertThat(PostRules.defaultCommentStatus("wp_block", TYPES, "open")).isEqualTo("closed");
        assertThat(PostRules.defaultPingStatus("wp_block", TYPES, "open")).isEqualTo("closed");
    }

    @Test
    void anEmptyCommentStatusIsTheDefaultOnInsertAndClosedOnUpdate() {
        assertThat(PostRules.commentStatus("", false, "open")).isEqualTo("open");
        assertThat(PostRules.commentStatus("", true, "open")).isEqualTo("closed");
        assertThat(PostRules.commentStatus("0", true, "open")).isEqualTo("closed");
        assertThat(PostRules.pingStatus("", "open")).isEqualTo("open");
        assertThat(PostRules.commentStatus("Open", true, "open")).isEqualTo("open");
        assertThat(PostRules.commentStatus("yes", false, "open")).isEqualTo("yes");
    }

    @Test
    void privatePostsLoseTheirPassword() {
        assertThat(PostRules.password("secret", "private")).isEmpty();
        assertThat(PostRules.password("secret", "publish")).isEqualTo("secret");
        assertThat(PostRules.password(" spaced ", "draft")).isEqualTo(" spaced ");
    }

    @Test
    void theGuidIsThePlainPermalink() {
        assertThat(PostRules.guid("http://example.test", "post", 15)).isEqualTo("http://example.test/?p=15");
        assertThat(PostRules.guid("http://example.test", "page", 16)).isEqualTo("http://example.test/?page_id=16");
        assertThat(PostRules.guid("http://example.test", "attachment", 17))
                .isEqualTo("http://example.test/?attachment_id=17");
        assertThat(PostRules.guid("http://example.test", "wp_block", 18))
                .isEqualTo("http://example.test/?post_type=wp_block&p=18");
    }

    @Test
    void valuesLongerThanTheirColumnsFailAsWordPressDatabaseWritesDo() {
        assertThatThrownBy(() -> PostRules.requireStorable(
                        "a_type_name_longer_than_20", "draft", "", "", "", "open", "open", "", "g", false))
                .isInstanceOfSatisfying(PostException.class, e -> {
                    assertThat(e.code()).isEqualTo("db_insert_error");
                    assertThat(e.detail()).contains("type");
                });
        assertThatThrownBy(() -> PostRules.requireStorable(
                        "post", "draft", "a".repeat(201), "", "", "open", "open", "", "g", true))
                .isInstanceOfSatisfying(PostException.class, e -> {
                    assertThat(e.code()).isEqualTo("db_update_error");
                    assertThat(e.detail()).contains("slug");
                });
        PostRules.requireStorable("post", "draft", "%e6%97%a5".repeat(22), "", "", "open", "open", "", "g", false);
    }

    @Test
    void inputThatSanitizesToNothingTakesTheDefault() {
        for (String junk : new String[] {"!!", "  ", "<>", "@", "0!"}) {
            assertThat(PostRules.type(junk)).isEqualTo("post");
            assertThat(PostRules.status(junk, "post")).isEqualTo("draft");
            assertThat(PostRules.status(junk, "attachment")).isEqualTo("inherit");
        }
    }

    @Test
    void attachmentsKeepInheritAndAutoDraft() {
        assertThat(PostRules.status("inherit", "attachment")).isEqualTo("inherit");
        assertThat(PostRules.status("auto-draft", "attachment")).isEqualTo("auto-draft");
        assertThat(PostRules.status("draft", "attachment")).isEqualTo("inherit");
    }

    @Test
    void anyOneNonEmptyFieldMakesTheSaveNonEmpty() {
        assertThat(PostRules.maybeEmpty("post", "", "", "x", TYPES)).isFalse();
        assertThat(PostRules.maybeEmpty("post", "", "x", "", TYPES)).isFalse();
        assertThat(PostRules.maybeEmpty("post", "", "0", "0", TYPES)).isTrue();
    }

    @Test
    void junkStatusesTakeTheDefaults() {
        assertThat(PostRules.commentStatus("!!", false, "open")).isEqualTo("open");
        assertThat(PostRules.commentStatus("!!", true, "open")).isEqualTo("closed");
        assertThat(PostRules.pingStatus("!!", "closed")).isEqualTo("closed");
    }

    @Test
    void everyColumnLimitIsExactAndCountsCodePoints() {
        String astral20 = new String(Character.toChars(0x1F600)).repeat(20);
        String max = "a".repeat(20);
        String over = "a".repeat(21);
        PostRules.requireStorable(
                astral20, astral20, "", "", "", astral20, astral20, "p".repeat(255), "g".repeat(255), false);
        PostRules.requireStorable(max, max, "a".repeat(200), "", "", max, max, "", "", true);
        assertTooLong(over, "draft", "", "open", "open", "", "", "type");
        assertTooLong("post", over, "", "open", "open", "", "", "status");
        assertTooLong("post", "draft", "", over, "open", "", "", "comment_status");
        assertTooLong("post", "draft", "", "open", over, "", "", "ping_status");
        assertTooLong("post", "draft", "", "open", "open", "p".repeat(256), "", "password");
        assertTooLong("post", "draft", "", "open", "open", "", "g".repeat(256), "guid");
        assertTooLong("post", "draft", "a".repeat(201), "open", "open", "", "", "slug");
    }

    @Test
    void titlesAndExcerptsHoldTheBytesOfATextColumn() {
        String fits = "\u00e9".repeat(32_767) + "a";
        PostRules.requireStorable("post", "draft", "", fits, fits, "open", "open", "", "", false);
        for (String column : new String[] {"title", "excerpt"}) {
            String over = fits + "b";
            String title = column.equals("title") ? over : "";
            String excerpt = column.equals("excerpt") ? over : "";
            for (boolean update : new boolean[] {false, true}) {
                assertThatThrownBy(() -> PostRules.requireStorable(
                                "post", "draft", "", title, excerpt, "open", "open", "", "", update))
                        .isInstanceOfSatisfying(PostException.class, e -> {
                            assertThat(e.code()).isEqualTo(update ? "db_update_error" : "db_insert_error");
                            assertThat(e.detail()).isEqualTo("the " + column + " is longer than 65535 bytes");
                        });
            }
        }
        assertThat(PostRules.utf8Length("a\u00e9\u65e5" + new String(Character.toChars(0x1F600))))
                .isEqualTo(10);
    }

    private static void assertTooLong(
            String type,
            String status,
            String slug,
            String comment,
            String ping,
            String password,
            String guid,
            String column) {
        assertThatThrownBy(() ->
                        PostRules.requireStorable(type, status, slug, "", "", comment, ping, password, guid, false))
                .isInstanceOfSatisfying(PostException.class, e -> {
                    assertThat(e.code()).isEqualTo("db_insert_error");
                    assertThat(e.detail()).contains(column);
                });
    }

    @Test
    void theGuidIsCutToTheColumnInWholeCodePoints() {
        String home = "http://example.test";
        String longType = "t".repeat(20);
        assertThat(PostRules.guid(home, "post", 1).length()).isLessThan(255);
        String astralHome = home + new String(Character.toChars(0x1F600)).repeat(300);
        String guid = PostRules.guid(astralHome, "post", 1);
        assertThat(guid.codePointCount(0, guid.length())).isEqualTo(255);
        assertThat(Character.isLowSurrogate(guid.charAt(guid.length() - 1))).isTrue();
        assertThat(PostRules.guid(home, longType, 1)).startsWith(home + "/?post_type=" + longType);
    }
}
