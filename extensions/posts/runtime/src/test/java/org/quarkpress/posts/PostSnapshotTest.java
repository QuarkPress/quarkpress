package org.quarkpress.posts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.runtime.PostRules;

class PostSnapshotTest {

    static final Instant AT = Instant.parse("2026-01-15T12:00:00Z");
    static final Instant LATER = Instant.parse("2026-07-15T12:00:00Z");
    static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private static PostSnapshot snapshot(
            Optional<Instant> publishedAt, Instant modifiedAt, Map<String, List<JsonNode>> meta) {
        return new PostSnapshot(
                PostId.of(1),
                SiteId.of(1),
                "post",
                "draft",
                "",
                "t",
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                0,
                publishedAt,
                AT,
                modifiedAt,
                "open",
                "open",
                "",
                false,
                "g",
                meta,
                0);
    }

    private static PostSnapshot withMeta(Map<String, List<JsonNode>> meta) {
        return snapshot(Optional.of(AT), AT, meta);
    }

    @Test
    void theSnapshotIsIsolatedFromTheInputsAndTheOutputs() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("a", 1);
        List<JsonNode> values = new ArrayList<>(List.of(node));
        Map<String, List<JsonNode>> input = new HashMap<>();
        input.put("k", values);
        PostSnapshot post = withMeta(input);

        node.put("a", 2);
        values.add(TextNode.valueOf("extra"));
        input.put("other", List.of());
        assertThat(post.meta()).containsOnlyKeys("k");
        assertThat(post.meta("k")).hasSize(1);
        assertThat(post.meta("k").get(0).get("a").asInt()).isEqualTo(1);

        assertThatThrownBy(() -> post.meta().put("x", List.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> post.meta("k").add(IntNode.valueOf(1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> post.meta().get("k").add(IntNode.valueOf(1)))
                .isInstanceOf(UnsupportedOperationException.class);

        ((ObjectNode) post.meta("k").get(0)).put("a", 3);
        ((ObjectNode) post.meta().get("k").get(0)).put("a", 4);
        assertThat(post.meta("k").get(0).get("a").asInt()).isEqualTo(1);
        assertThat(post.meta("missing")).isEmpty();
    }

    @Test
    void missingMetaValuesAreReportedByName() {
        Map<String, List<JsonNode>> nullList = new HashMap<>();
        nullList.put("k", null);
        assertThatThrownBy(() -> withMeta(nullList))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("meta values");
        List<JsonNode> nullElement = new ArrayList<>();
        nullElement.add(null);
        assertThatThrownBy(() -> withMeta(Map.of("k", nullElement)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("meta value");
    }

    @Test
    void localDatesUseTheZoneAndAFloatingDateFollowsTheModifiedTime() {
        PostSnapshot dated = snapshot(Optional.of(AT), LATER, Map.of());
        assertThat(dated.localDate(TORONTO)).isEqualTo(LocalDateTime.of(2026, 1, 15, 7, 0));
        assertThat(dated.localModified(TORONTO)).isEqualTo(LocalDateTime.of(2026, 7, 15, 8, 0));
        PostSnapshot floating = snapshot(Optional.empty(), LATER, Map.of());
        assertThat(floating.localDate(TORONTO)).isEqualTo(LocalDateTime.of(2026, 7, 15, 8, 0));
    }

    @Test
    void metaTextReadsScalarsAsPhpWouldWriteThem() {
        Map<String, List<JsonNode>> meta = new HashMap<>();
        meta.put("zero", List.of(IntNode.valueOf(0)));
        meta.put("int", List.of(IntNode.valueOf(42)));
        meta.put("true", List.of(BooleanNode.TRUE));
        meta.put("false", List.of(BooleanNode.FALSE));
        meta.put("whole", List.of(DoubleNode.valueOf(1.0)));
        meta.put("half", List.of(DoubleNode.valueOf(1.5)));
        meta.put("tenth", List.of(DoubleNode.valueOf(0.1 + 0.2)));
        meta.put("zeroDouble", List.of(DoubleNode.valueOf(0.0)));
        meta.put("negativeZero", List.of(DoubleNode.valueOf(-0.0)));
        meta.put("nan", List.of(DoubleNode.valueOf(Double.NaN)));
        meta.put("inf", List.of(DoubleNode.valueOf(Double.POSITIVE_INFINITY)));
        meta.put("negInf", List.of(DoubleNode.valueOf(Double.NEGATIVE_INFINITY)));
        meta.put("e14", List.of(DoubleNode.valueOf(1e14)));
        meta.put("e14minus", List.of(DoubleNode.valueOf(99999999999999.0)));
        meta.put("e14round", List.of(DoubleNode.valueOf(99999999999999.9)));
        meta.put("long15", List.of(DoubleNode.valueOf(123456789012345.0)));
        meta.put("e15", List.of(DoubleNode.valueOf(1e15)));
        meta.put("e20", List.of(DoubleNode.valueOf(1e20)));
        meta.put("negE15", List.of(DoubleNode.valueOf(-1e15)));
        meta.put("e4", List.of(DoubleNode.valueOf(1e-4)));
        meta.put("long4", List.of(DoubleNode.valueOf(1.2345678901234567e-4)));
        meta.put("e5", List.of(DoubleNode.valueOf(1e-5)));
        meta.put("e5b", List.of(DoubleNode.valueOf(2.5e-5)));
        meta.put("long5", List.of(DoubleNode.valueOf(1.2345678901234567e-5)));
        meta.put("tieDown", List.of(DoubleNode.valueOf(100000000000005.0)));
        meta.put("notTie", List.of(DoubleNode.valueOf(120000000000004.0)));
        meta.put("tieUp", List.of(DoubleNode.valueOf(100000000000095.0)));
        meta.put("text", List.of(TextNode.valueOf("x")));
        meta.put("object", List.of(JsonNodeFactory.instance.objectNode()));
        meta.put("none", List.of());
        PostSnapshot post = withMeta(meta);
        assertThat(post.metaText("zero")).contains("0");
        assertThat(post.metaText("int")).contains("42");
        assertThat(post.metaText("true")).contains("1");
        assertThat(post.metaText("false")).contains("");
        assertThat(post.metaText("whole")).contains("1");
        assertThat(post.metaText("half")).contains("1.5");
        assertThat(post.metaText("tenth")).contains("0.3");
        assertThat(post.metaText("zeroDouble")).contains("0");
        assertThat(post.metaText("negativeZero")).contains("-0");
        assertThat(post.metaText("nan")).contains("NAN");
        assertThat(post.metaText("inf")).contains("INF");
        assertThat(post.metaText("negInf")).contains("-INF");
        assertThat(post.metaText("e14")).contains("1.0E+14");
        assertThat(post.metaText("e14minus")).contains("99999999999999");
        assertThat(post.metaText("e14round")).contains("1.0E+14");
        assertThat(post.metaText("long15")).contains("1.2345678901234E+14");
        assertThat(post.metaText("e15")).contains("1.0E+15");
        assertThat(post.metaText("e20")).contains("1.0E+20");
        assertThat(post.metaText("negE15")).contains("-1.0E+15");
        assertThat(post.metaText("e4")).contains("0.0001");
        assertThat(post.metaText("long4")).contains("0.00012345678901235");
        assertThat(post.metaText("e5")).contains("1.0E-5");
        assertThat(post.metaText("e5b")).contains("2.5E-5");
        assertThat(post.metaText("long5")).contains("1.2345678901235E-5");
        assertThat(PostRules.phpEmpty(post.metaText("zeroDouble").orElseThrow()))
                .isTrue();
        assertThat(post.metaText("tieDown")).contains("1.0000000000000E+14");
        assertThat(post.metaText("notTie")).contains("1.2E+14");
        assertThat(post.metaText("tieUp")).contains("1.000000000001E+14");
        assertThat(post.metaText("text")).contains("x");
        assertThat(post.metaText("object")).isEmpty();
        assertThat(post.metaText("none")).isEmpty();
        assertThat(post.metaText("absent")).isEmpty();
    }
}
