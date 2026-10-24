package org.quarkpress.revisions.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionSnapshot;

class RevisionRulesTest {

    static final Instant AT = Instant.parse("2026-01-15T12:00:00Z");

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    static PostSnapshot post(String title, String content, String excerpt, Map<String, List<JsonNode>> meta) {
        return new PostSnapshot(
                PostId.of(7),
                SiteId.of(1),
                "post",
                "publish",
                "hello",
                title,
                content,
                excerpt,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(AT),
                AT,
                AT,
                "open",
                "open",
                "",
                false,
                "http://x/?p=7",
                meta,
                1);
    }

    static RevisionSnapshot revision(
            long id,
            String title,
            String content,
            String excerpt,
            Map<String, List<JsonNode>> meta,
            boolean autosave,
            String at) {
        return new RevisionSnapshot(
                RevisionId.of(id),
                SiteId.of(1),
                PostId.of(7),
                Optional.empty(),
                title,
                content,
                excerpt,
                meta,
                autosave,
                Instant.parse(at));
    }

    static RevisionSnapshot regular(long id, String at) {
        return revision(id, "t", "c", "e", Map.of(), false, at);
    }

    static RevisionSnapshot autosave(long id, String at) {
        return revision(id, "t", "c", "e", Map.of(), true, at);
    }

    @Test
    void keepFollowsTheConstantAndTheTypesSupport() {
        // the reference's table: WP_POST_REVISIONS true is -1, false and "abc" are 0, "2.9" is 2
        assertThat(RevisionRules.keep(-1, true)).isEqualTo(-1);
        assertThat(RevisionRules.keep(0, true)).isZero();
        assertThat(RevisionRules.keep(5, true)).isEqualTo(5);
        assertThat(RevisionRules.keep(2, true)).isEqualTo(2);
        assertThat(RevisionRules.keep(-1, false)).isZero();
        assertThat(RevisionRules.keep(5, false)).isZero();
        assertThat(RevisionRules.enabled(-1)).isTrue();
        assertThat(RevisionRules.enabled(-7)).isTrue();
        assertThat(RevisionRules.enabled(5)).isTrue();
        assertThat(RevisionRules.enabled(0)).isFalse();
    }

    @Test
    void whitespaceIsNormalizedAsWordPressNormalizesIt() {
        assertThat(RevisionRules.normalizeWhitespace("a  b")).isEqualTo("a b");
        assertThat(RevisionRules.normalizeWhitespace("a\r\nb")).isEqualTo("a\nb");
        assertThat(RevisionRules.normalizeWhitespace("a\rb")).isEqualTo("a\nb");
        assertThat(RevisionRules.normalizeWhitespace("a\n\n\nb")).isEqualTo("a\nb");
        assertThat(RevisionRules.normalizeWhitespace("a \t  b")).isEqualTo("a b");
        assertThat(RevisionRules.normalizeWhitespace("a\n b")).isEqualTo("a\n b");
        assertThat(RevisionRules.normalizeWhitespace("a \nb")).isEqualTo("a \nb");
        assertThat(RevisionRules.normalizeWhitespace("  x\t\n ")).isEqualTo("x");
        assertThat(RevisionRules.normalizeWhitespace(cp(0x0B) + "x" + cp(0))).isEqualTo("x");
        assertThat(RevisionRules.normalizeWhitespace("a" + cp(0xA0) + "b")).isEqualTo("a" + cp(0xA0) + "b");
        assertThat(RevisionRules.normalizeWhitespace("")).isEmpty();
    }

    @Test
    void whitespaceOnlyDifferencesDoNotCount() {
        Set<RevisionField> all = RevisionField.all();
        assertThat(RevisionRules.fieldsChanged(all, regular(8, "2026-01-15T12:00:00Z"), post("t", "c", "e", Map.of())))
                .isFalse();
        assertThat(RevisionRules.fieldsChanged(
                        all,
                        revision(8, "t", "a b", "e", Map.of(), false, "2026-01-15T12:00:00Z"),
                        post("t", "a  b", "e", Map.of())))
                .isFalse();
        assertThat(RevisionRules.fieldsChanged(
                        all,
                        revision(8, "t", "a\r\nb", "e", Map.of(), false, "2026-01-15T12:00:00Z"),
                        post("t", "a\nb", "e", Map.of())))
                .isFalse();
        assertThat(RevisionRules.fieldsChanged(
                        all,
                        revision(8, "t", "a\nb", "e", Map.of(), false, "2026-01-15T12:00:00Z"),
                        post("t", "a\n b", "e", Map.of())))
                .isTrue();
        assertThat(RevisionRules.fieldsChanged(
                        all,
                        revision(8, "t", "a b", "e", Map.of(), false, "2026-01-15T12:00:00Z"),
                        post("t", "a" + cp(0xA0) + "b", "e", Map.of())))
                .isTrue();
        // only the fields in the set are compared
        assertThat(RevisionRules.fieldsChanged(
                        EnumSet.of(RevisionField.TITLE),
                        regular(8, "2026-01-15T12:00:00Z"),
                        post("t", "changed", "e", Map.of())))
                .isFalse();
        assertThat(RevisionRules.fieldsChanged(
                        EnumSet.of(RevisionField.EXCERPT),
                        regular(8, "2026-01-15T12:00:00Z"),
                        post("t", "c", "changed", Map.of())))
                .isTrue();
        assertThat(RevisionRules.fieldsChanged(
                        EnumSet.noneOf(RevisionField.class),
                        regular(8, "2026-01-15T12:00:00Z"),
                        post("x", "y", "z", Map.of())))
                .isFalse();
    }

    @Test
    void revisionedMetaIsComparedAsListsOfValues() {
        List<JsonNode> one = List.of(TextNode.valueOf("[{\"id\":\"a\"}]"));
        assertThat(RevisionRules.metaChanged(Set.of("footnotes"), Map.of("footnotes", one), Map.of("footnotes", one)))
                .isFalse();
        assertThat(RevisionRules.metaChanged(Set.of("footnotes"), Map.of(), Map.of()))
                .isFalse();
        assertThat(RevisionRules.metaChanged(Set.of("footnotes"), Map.of(), Map.of("footnotes", one)))
                .isTrue();
        assertThat(RevisionRules.metaChanged(
                        Set.of("footnotes"),
                        Map.of("footnotes", one),
                        Map.of("footnotes", List.of(TextNode.valueOf("[]")))))
                .isTrue();
        assertThat(RevisionRules.metaChanged(
                        Set.of("footnotes"),
                        Map.of("footnotes", one),
                        Map.of("footnotes", List.of(one.get(0), one.get(0)))))
                .isTrue();
        // values compare by value: 1 and 1.0 are the same; keys outside the set are not compared
        assertThat(RevisionRules.metaChanged(
                        Set.of("n"),
                        Map.of("n", List.of(IntNode.valueOf(1))),
                        Map.of("n", List.of(DoubleNode.valueOf(1.0)))))
                .isFalse();
        assertThat(RevisionRules.metaChanged(Set.of("footnotes"), Map.of("color", one), Map.of()))
                .isFalse();
    }

    @Test
    void anAutosaveDiffersWhenAGivenFieldOrRevisionedMetaDiffersFromThePost() {
        PostSnapshot body = post("T", "Body", "E", Map.of("footnotes", List.of(TextNode.valueOf("[1]"))));
        Set<RevisionField> all = RevisionField.all();
        Set<String> keys = Set.of("footnotes");
        assertThat(RevisionRules.autosaveDiffers(
                        all, keys, AutosaveChanges.builder().content("Body").build(), body))
                .isFalse();
        assertThat(RevisionRules.autosaveDiffers(
                        all, keys, AutosaveChanges.builder().content("Body ").build(), body))
                .isFalse();
        assertThat(RevisionRules.autosaveDiffers(
                        all, keys, AutosaveChanges.builder().content("Body 2").build(), body))
                .isTrue();
        // only given fields are compared: a title alone is compared on the title
        assertThat(RevisionRules.autosaveDiffers(
                        all, keys, AutosaveChanges.builder().title("T").build(), post("T", "other", "E", Map.of())))
                .isFalse();
        assertThat(RevisionRules.autosaveDiffers(
                        all, keys, AutosaveChanges.builder().build(), body))
                .isFalse();
        // a field the filter removed is not compared even when given
        assertThat(RevisionRules.autosaveDiffers(
                        EnumSet.of(RevisionField.TITLE),
                        keys,
                        AutosaveChanges.builder().content("Body 2").build(),
                        body))
                .isFalse();
        // given meta: every revisioned key is compared, the raw first value against the given one ("" when not given;
        // a key the post does not store always differs)
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        keys,
                        AutosaveChanges.builder()
                                .content("Body")
                                .meta("footnotes", TextNode.valueOf("[1]"))
                                .build(),
                        body))
                .isFalse();
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        keys,
                        AutosaveChanges.builder()
                                .content("Body")
                                .meta("footnotes", TextNode.valueOf("[2]"))
                                .build(),
                        body))
                .isTrue();
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        keys,
                        AutosaveChanges.builder()
                                .content("Body")
                                .meta("other", TextNode.valueOf("x"))
                                .build(),
                        body))
                .isTrue();
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        keys,
                        AutosaveChanges.builder()
                                .meta("footnotes", TextNode.valueOf(""))
                                .build(),
                        post("T", "Body", "E", Map.of())))
                .isTrue();
        // only the first stored value counts
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        keys,
                        AutosaveChanges.builder()
                                .meta("footnotes", TextNode.valueOf("[1]"))
                                .build(),
                        post(
                                "T",
                                "Body",
                                "E",
                                Map.of("footnotes", List.of(TextNode.valueOf("[1]"), TextNode.valueOf("[2]"))))))
                .isFalse();
        // a revisioned key the given meta omits counts as "": equal to a stored "", different from anything else
        Set<String> two = Set.of("footnotes", "color");
        AutosaveChanges footnotesOnly = AutosaveChanges.builder()
                .meta("footnotes", TextNode.valueOf("[1]"))
                .build();
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        two,
                        footnotesOnly,
                        post(
                                "T",
                                "Body",
                                "E",
                                Map.of(
                                        "footnotes",
                                        List.of(TextNode.valueOf("[1]")),
                                        "color",
                                        List.of(TextNode.valueOf(""))))))
                .isFalse();
        assertThat(RevisionRules.autosaveDiffers(
                        all,
                        two,
                        footnotesOnly,
                        post(
                                "T",
                                "Body",
                                "E",
                                Map.of(
                                        "footnotes",
                                        List.of(TextNode.valueOf("[1]")),
                                        "color",
                                        List.of(TextNode.valueOf("z"))))))
                .isTrue();
    }

    @Test
    void trimmingKeepsTheNewestAndSkipsAutosavesWithoutReplacement() {
        // keep 2: 8, 9, then 10 saved: 8 goes
        assertThat(RevisionRules.trimCandidates(
                        List.of(
                                regular(8, "2026-01-15T12:00:00Z"),
                                regular(9, "2026-01-15T12:01:00Z"),
                                regular(10, "2026-01-15T12:02:00Z")),
                        2))
                .containsExactly(RevisionId.of(8));
        // 8 regular, 9 autosave, 10 regular, 11 saved: candidates 8 and 9; 9 is skipped, not replaced by 10
        assertThat(RevisionRules.trimCandidates(
                        List.of(
                                regular(8, "2026-01-15T12:00:00Z"), autosave(9, "2026-01-15T12:01:00Z"),
                                regular(10, "2026-01-15T12:02:00Z"), regular(11, "2026-01-15T12:03:00Z")),
                        2))
                .containsExactly(RevisionId.of(8));
        // 8, 9, 10 regular, 11 autosave (newest), 12 saved, keep 2: 8, 9 and 10 all go
        assertThat(RevisionRules.trimCandidates(
                        List.of(
                                regular(8, "2026-01-15T12:00:00Z"),
                                regular(9, "2026-01-15T12:01:00Z"),
                                regular(10, "2026-01-15T12:02:00Z"),
                                autosave(11, "2026-01-15T12:03:00Z"),
                                regular(12, "2026-01-15T12:04:00Z")),
                        2))
                .containsExactly(RevisionId.of(8), RevisionId.of(9), RevisionId.of(10));
        // keep 1: only the newest survives; unlimited or nothing beyond the limit: nothing goes
        assertThat(RevisionRules.trimCandidates(
                        List.of(regular(8, "2026-01-15T12:00:00Z"), regular(9, "2026-01-15T12:01:00Z")), 1))
                .containsExactly(RevisionId.of(8));
        assertThat(RevisionRules.trimCandidates(
                        List.of(regular(8, "2026-01-15T12:00:00Z"), regular(9, "2026-01-15T12:01:00Z")), -1))
                .isEmpty();
        assertThat(RevisionRules.trimCandidates(
                        List.of(regular(8, "2026-01-15T12:00:00Z"), regular(9, "2026-01-15T12:01:00Z")), 2))
                .isEmpty();
        assertThat(RevisionRules.trimCandidates(List.of(), 1)).isEmpty();
    }

    @Test
    void autosaveChangesCopyTheirMetaAndRefuseNulls() {
        AutosaveChanges changes = AutosaveChanges.builder()
                .meta("footnotes", TextNode.valueOf("[1]"))
                .build();
        assertThat(changes.meta()).isPresent();
        assertThat(changes.meta().get()).containsEntry("footnotes", TextNode.valueOf("[1]"));
        assertThat(AutosaveChanges.builder().build().meta()).isEmpty();
        assertThat(AutosaveChanges.builder().title("x").build().field(RevisionField.TITLE))
                .contains("x");
        assertThat(AutosaveChanges.builder().title("x").build().field(RevisionField.CONTENT))
                .isEmpty();
        assertThatThrownBy(() -> AutosaveChanges.builder().meta("k", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value");
        assertThatThrownBy(() -> AutosaveChanges.builder().meta(null, TextNode.valueOf("v")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key");
    }

    @Test
    void autosaveChangesDoNotShareTheirMetaValues() {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("a", 1);
        AutosaveChanges.Builder builder = AutosaveChanges.builder().meta("k", value);
        value.put("a", 2);
        AutosaveChanges changes = builder.build();
        assertThat(changes.meta().get().get("k").get("a").asInt()).isEqualTo(1);
        ObjectNode other = JsonNodeFactory.instance.objectNode().put("a", 1);
        AutosaveChanges direct = new AutosaveChanges(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Map.of("k", other)));
        other.put("a", 2);
        assertThat(direct.meta().get().get("k").get("a").asInt()).isEqualTo(1);
    }

    @Test
    void anEmptyGivenMetaIsNotGivenMeta() {
        AutosaveChanges direct =
                new AutosaveChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Map.of()));
        assertThat(direct.meta()).isEmpty();
        assertThat(RevisionRules.autosaveDiffers(
                        RevisionField.all(),
                        Set.of("footnotes"),
                        direct,
                        post("T", "Body", "E", Map.of("footnotes", List.of(TextNode.valueOf("[1]"))))))
                .isFalse();
    }
}
