package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    void recordsCopyTheirCollections() {
        List<WxrMeta> meta = new ArrayList<>(List.of(new WxrMeta("k", "v")));
        Map<String, String> raw = new LinkedHashMap<>(Map.of("wp:term_id", "2"));
        WxrCategory category = new WxrCategory(2L, "news", "", "News", null, meta, raw);
        meta.clear();
        raw.clear();
        assertThat(category.termMeta()).containsExactly(new WxrMeta("k", "v"));
        assertThat(category.rawFields()).containsEntry("wp:term_id", "2");
        assertThatThrownBy(() -> category.termMeta().add(new WxrMeta("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shortConstructorsHaveNoRawFieldsAndNullListsBecomeEmpty() {
        assertThat(new WxrAuthor(1L, "admin", "a@x", "A", "", "").rawFields()).isEmpty();
        assertThat(new WxrTag(5L, "php", "php", null, null).termMeta()).isEmpty();
        assertThat(WxrItem.builder().build().comments()).isEmpty();
    }

    @Test
    void itemTermTaxonomyNormalizesLegacyDomains() {
        // 1.0 tag references use domain "tag"; the importer maps it to post_tag. A bare <category> is a category.
        assertThat(new WxrItemTerm("tag", "misc", "misc").taxonomy()).isEqualTo("post_tag");
        assertThat(new WxrItemTerm(null, null, "Local").taxonomy()).isEqualTo("category");
        assertThat(new WxrItemTerm("product_cat", "shoes", "Shoes").taxonomy()).isEqualTo("product_cat");
    }

    @Test
    void builderRoundTripsEveryField() {
        WxrComment comment = WxrComment.builder()
                .id(100L)
                .author("Ann")
                .authorEmail("ann@example.test")
                .authorUrl("https://ann.test/")
                .authorIp("127.0.0.1")
                .date("2026-01-04 09:00:00")
                .dateGmt("2026-01-04 14:00:00")
                .content("Nice post")
                .approved("1")
                .type("comment")
                .parent(0L)
                .userId(0L)
                .commentMeta(List.of(new WxrMeta("rating", "5")))
                .rawFields(Map.of("wp:comment_id", "100"))
                .build();
        WxrItem item = WxrItem.builder()
                .title("Hello")
                .link("https://example.test/hello/")
                .pubDate("Sun, 04 Jan 2026 14:00:00 +0000")
                .creator("admin")
                .guid("https://example.test/?p=10")
                .guidIsPermaLink(false)
                .description("d")
                .content("<p>c</p>")
                .excerpt("e")
                .postId(10L)
                .postDate("2026-01-04 09:00:00")
                .postDateGmt("2026-01-04 14:00:00")
                .postModified("2026-01-05 09:00:00")
                .postModifiedGmt("2026-01-05 14:00:00")
                .commentStatus("open")
                .pingStatus("closed")
                .postName("hello")
                .status("publish")
                .postParent(3L)
                .menuOrder(4L)
                .postType("attachment")
                .postPassword("pw")
                .isSticky(1L)
                .attachmentUrl("https://example.test/a.png")
                .terms(List.of(new WxrItemTerm("category", "news", "News")))
                .postMeta(List.of(new WxrMeta("colors", "a:0:{}")))
                .comments(List.of(comment))
                .rawFields(Map.of("wp:post_id", "10"))
                .build();
        // every component is set, so a field the builder or toBuilder forgot cannot pass unnoticed
        for (Record r : List.<Record>of(item, comment)) {
            for (java.lang.reflect.RecordComponent c : r.getClass().getRecordComponents()) {
                try {
                    Object value = c.getAccessor().invoke(r);
                    assertThat(value).as(c.getName()).isNotNull();
                    if (value instanceof java.util.Collection<?> list) {
                        assertThat(list).as(c.getName()).isNotEmpty();
                    } else if (value instanceof Map<?, ?> map) {
                        assertThat(map).as(c.getName()).isNotEmpty();
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        }
        assertThat(item.toBuilder().build()).isEqualTo(item);
        assertThat(comment.toBuilder().build()).isEqualTo(comment);
        WxrItem retitled = item.toBuilder().title("other").build();
        assertThat(retitled.title()).isEqualTo("other");
        assertThat(retitled.toBuilder().title("Hello").build())
                .as("only the title changed")
                .isEqualTo(item);
        assertThat(item.comments().get(0).commentMeta()).containsExactly(new WxrMeta("rating", "5"));
    }

    @Test
    void integersAcceptOnlyWholeNumbers() {
        assertThat(Integers.parse("10")).isEqualTo(10L);
        assertThat(Integers.parse(" 7\n")).isEqualTo(7L);
        assertThat(Integers.parse("-3")).isEqualTo(-3L);
        assertThat(Integers.parse("12abc")).isNull();
        assertThat(Integers.parse("")).isNull();
        assertThat(Integers.parse("99999999999999999999")).isNull();
        assertThat(Integers.parse(null)).isNull();
    }

    @Test
    void namespacePrefixesFollowTheSixWordPressUris() {
        // http and https, versions 1.0 to 1.2.
        assertThat(WxrNames.knownPrefix("http://wordpress.org/export/1.2/")).isEqualTo("wp");
        assertThat(WxrNames.knownPrefix("https://wordpress.org/export/1.0/")).isEqualTo("wp");
        assertThat(WxrNames.knownPrefix("http://wordpress.org/export/1.1/excerpt/"))
                .isEqualTo("excerpt");
        assertThat(WxrNames.knownPrefix("http://purl.org/rss/1.0/modules/content/"))
                .isEqualTo("content");
        assertThat(WxrNames.knownPrefix("http://purl.org/dc/elements/1.1/")).isEqualTo("dc");
        assertThat(WxrNames.knownPrefix("http://wordpress.org/export/1.3/")).isNull();
    }
}
