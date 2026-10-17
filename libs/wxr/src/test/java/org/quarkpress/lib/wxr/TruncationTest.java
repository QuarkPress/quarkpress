package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TruncationTest {

    private static final String EXAMPLE_A = new String(TestWxr.resource("example-a.xml"), StandardCharsets.UTF_8);

    /** Example A cut right after the first occurrence of {@code marker}. */
    private static byte[] cutAfter(String marker) {
        int at = EXAMPLE_A.indexOf(marker);
        assertThat(at).as(marker).isNotNegative();
        return EXAMPLE_A.substring(0, at + marker.length()).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void cutInsideCommentContentKeepsCompletedFields() {
        TestWxr.Result r = TestWxr.read(cutAfter("<wp:comment_content><![CDATA[Nice"), WxrReadOptions.LENIENT);
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED);
        assertThat(r.entries()).hasSize(4);
        WxrItem partial = r.items().get(0);
        assertThat(partial.title()).isEqualTo("Hello");
        assertThat(partial.terms()).hasSize(2);
        assertThat(partial.postMeta()).containsExactly(new WxrMeta("colors", "a:1:{i:0;s:3:\"red\";}"));
        WxrComment comment = partial.comments().get(0);
        assertThat(comment.id()).isEqualTo(100L);
        assertThat(comment.authorIp()).isEqualTo("127.0.0.1");
        assertThat(comment.dateGmt()).isEqualTo("2026-01-04 14:00:00");
        assertThat(comment.content()).as("cut in the middle").isNull();
        assertThat(comment.approved()).isNull();
    }

    @Test
    void cutInsideMetaValueDropsTheMeta() {
        TestWxr.Result r = TestWxr.read(cutAfter("<wp:meta_value><![CDATA[a:1:{i:0;s:3:"), WxrReadOptions.LENIENT);
        WxrItem partial = r.items().get(0);
        assertThat(partial.postMeta()).isEmpty();
        assertThat(partial.comments()).isEmpty();
        assertThat(partial.isSticky()).isEqualTo(0L);
        assertThat(partial.terms()).extracting(WxrItemTerm::nicename).containsExactly("news", "php");
    }

    @Test
    void cutInsideContentKeepsTheFieldsBeforeIt() {
        TestWxr.Result r = TestWxr.read(cutAfter("<content:encoded><![CDATA[<p>Use"), WxrReadOptions.LENIENT);
        WxrItem partial = r.items().get(0);
        assertThat(partial.guid()).isEqualTo("https://example.test/?p=10");
        assertThat(partial.description()).isEmpty();
        assertThat(partial.content()).isNull();
        assertThat(partial.postId()).isNull();
    }

    @Test
    void cutBetweenEntriesDeliversNoPartialEntry() {
        TestWxr.Result r = TestWxr.read(cutAfter("</wp:category>"), WxrReadOptions.LENIENT);
        assertThat(r.entries().stream().map(Canonical::strip).toList())
                .containsExactly(ExampleA.AUTHOR, ExampleA.CATEGORY);
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED);
    }

    @Test
    void cutInTheChannelHeaderKeepsTheSiteFields() {
        TestWxr.Result r = TestWxr.read(cutAfter("<wp:wxr_version>1.2</wp:wxr_version>"), WxrReadOptions.LENIENT);
        assertThat(r.entries()).isEmpty();
        assertThat(r.site().wxrVersion()).isEqualTo("1.2");
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED);
    }

    @Test
    void strictModeDeliversCompleteEntriesThenThrows() {
        byte[] cut = cutAfter("<wp:comment_content><![CDATA[Nice");
        try (WxrReader reader = WxrReader.open(new ByteArrayInputStream(cut), WxrReadOptions.STRICT)) {
            List<Class<?>> seen = List.of(
                    reader.next().getClass(),
                    reader.next().getClass(),
                    reader.next().getClass());
            assertThat(seen).containsExactly(WxrAuthor.class, WxrCategory.class, WxrTag.class);
            assertThatThrownBy(reader::hasNext)
                    .isInstanceOfSatisfying(
                            WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.TRUNCATED));
            assertThat(reader.hasNext()).isFalse();
        }
    }

    @Test
    void cutBeforeTheRootElementIsNotWxr() {
        for (String marker : List.of("<?xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>", "<!-- 1. Log in")) {
            TestWxr.Result r = TestWxr.read(cutAfter(marker), WxrReadOptions.LENIENT);
            assertThat(r.entries()).as(marker).isEmpty();
            assertThat(r.kinds()).as(marker).containsExactly(WxrProblem.Kind.NOT_WXR);
        }
    }

    @Test
    void cutInsideTheRootStartTagIsTruncation() {
        TestWxr.Result r = TestWxr.read(cutAfter("xmlns:wfw="), WxrReadOptions.LENIENT);
        assertThat(r.entries()).isEmpty();
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED, WxrProblem.Kind.VERSION_MISSING);
    }

    @Test
    void brokenMarkupMidFileIsMalformedAndTheItemSoFarIsDelivered() {
        String xml = TestWxr.doc("<item><title>a</title><wp:status>x</wrong></item><item><title>b</title></item>");
        TestWxr.Result r = TestWxr.read(xml, WxrReadOptions.LENIENT);
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.MALFORMED);
        assertThat(r.items()).extracting(WxrItem::title).containsExactly("a");
        assertThat(r.items().get(0).status()).as("the field cut by the error").isNull();
    }

    @Test
    void garbageMarkupAfterCompleteEntriesIsMalformedWithItsPosition() {
        // a broken tag between the tag and the item of example A (line 51 is blank but for tabs)
        String broken = EXAMPLE_A.replace("</wp:tag>\n", "</wp:tag>\n<p class=\"x\" <<<>\n");
        byte[] bytes = broken.getBytes(StandardCharsets.UTF_8);
        TestWxr.Result r = TestWxr.read(bytes, WxrReadOptions.LENIENT);
        assertThat(r.entries().stream().map(Canonical::strip).toList())
                .containsExactly(ExampleA.AUTHOR, ExampleA.CATEGORY, ExampleA.TAG);
        assertThat(r.problems()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(WxrProblem.Kind.MALFORMED);
            assertThat(p.line()).isEqualTo(51);
            assertThat(p.column()).isPositive();
        });
        try (WxrReader reader = WxrReader.open(new ByteArrayInputStream(bytes), WxrReadOptions.STRICT)) {
            assertThat(reader.next()).isInstanceOf(WxrAuthor.class);
            assertThat(reader.next()).isInstanceOf(WxrCategory.class);
            assertThat(reader.next()).isInstanceOf(WxrTag.class);
            assertThatThrownBy(reader::hasNext).isInstanceOfSatisfying(WxrException.class, e -> {
                assertThat(e.kind()).isEqualTo(WxrProblem.Kind.MALFORMED);
                assertThat(e.line()).isEqualTo(51);
            });
            assertThat(reader.hasNext()).isFalse();
        }
    }

    @Test
    void anErrorInsideAnItemIsMalformedAndEndsReadingAfterThePartialItem() {
        String broken = EXAMPLE_A.replace("<wp:post_id>10</wp:post_id>", "<wp:post_id>10</wp:post_id><a b=c>");
        TestWxr.Result r = TestWxr.read(broken, WxrReadOptions.LENIENT);
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.MALFORMED);
        assertThat(r.entries()).hasSize(4);
        WxrItem partial = r.items().get(0);
        assertThat(partial.postId()).isEqualTo(10L);
        assertThat(partial.content()).isEqualTo(ExampleA.ITEM.content());
        assertThat(partial.postDate()).as("after the error").isNull();
    }

    @Test
    void theEndOfInputIsFoundAlsoWhenTheParsersOffsetWraps() {
        long twoTo31 = 1L << 31;
        long twoTo32 = 1L << 32;
        assertThat(WxrReader.atEndOfInput(100, 100)).isTrue();
        assertThat(WxrReader.atEndOfInput(98, 100)).isTrue();
        assertThat(WxrReader.atEndOfInput(97, 100)).isFalse();
        assertThat(WxrReader.atEndOfInput(101, 100)).as("one past the end").isTrue();
        // the JDK parser's offset at the end of unclosed.xml (674 chars given, namespace bindings included)
        assertThat(WxrReader.atEndOfInput(1894, 674)).as("past the end").isTrue();
        assertThat(WxrReader.atEndOfInput(-1, 100)).as("unknown").isTrue();
        // past 2^31 the parser's int offset is negative
        assertThat(WxrReader.atEndOfInput((int) (twoTo31 + 5), twoTo31 + 5)).isTrue();
        assertThat(WxrReader.atEndOfInput((int) (twoTo31 + 3), twoTo31 + 5)).isTrue();
        assertThat(WxrReader.atEndOfInput((int) twoTo31, twoTo31 + 5)).isFalse();
        assertThat(WxrReader.atEndOfInput(Integer.MAX_VALUE, twoTo31 + 5)).isFalse();
        // past 2^32 it starts again from 0: a cut 4.4G-char export
        assertThat(WxrReader.atEndOfInput((int) (twoTo32 + 50), twoTo32 + 50)).isTrue();
        assertThat(WxrReader.atEndOfInput(50, twoTo32 + 50)).isTrue();
        assertThat(WxrReader.atEndOfInput(10, twoTo32 + 50)).isFalse();
        assertThat(WxrReader.atEndOfInput(1, twoTo32 + 1)).isTrue();
        assertThat(WxrReader.atEndOfInput(1_000, 4_400_000_000L)).isFalse();
        assertThat(WxrReader.atEndOfInput((int) 4_400_000_000L, 4_400_000_000L)).isTrue();
    }

    @Test
    void strictModeDeliversTheEntriesBeforeABadByteThenRefusesIt() {
        for (int items : new int[] {4, 400}) {
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= items; i++) {
                body.append("<item><title>t")
                        .append(i)
                        .append("</title><wp:post_id>")
                        .append(i)
                        .append("</wp:post_id><content:encoded><![CDATA[")
                        .append("x".repeat(100))
                        .append("]]></content:encoded></item>\n");
            }
            String xml = TestWxr.doc(body.toString());
            int at = xml.indexOf("<title>t" + items + "<") + "<title>t".length();
            byte[] head = xml.substring(0, at).getBytes(StandardCharsets.UTF_8);
            byte[] tail = xml.substring(at).getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[head.length + 1 + tail.length];
            System.arraycopy(head, 0, bytes, 0, head.length);
            bytes[head.length] = (byte) 0xE9;
            System.arraycopy(tail, 0, bytes, head.length + 1, tail.length);

            try (WxrReader reader = WxrReader.open(new ByteArrayInputStream(bytes), WxrReadOptions.STRICT)) {
                for (int i = 1; i < items; i++) {
                    assertThat(reader.next())
                            .as("item %d of %d", i, items)
                            .isInstanceOfSatisfying(
                                    WxrItem.class, it -> assertThat(it.title()).isEqualTo("t" + it.postId()));
                }
                assertThatThrownBy(reader::hasNext)
                        .isInstanceOfSatisfying(
                                WxrException.class,
                                e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.INVALID_ENCODING));
                assertThat(reader.hasNext()).isFalse();
            }
            TestWxr.Result lenient = TestWxr.read(bytes, WxrReadOptions.LENIENT);
            assertThat(lenient.items()).hasSize(items);
            assertThat(lenient.kinds()).containsExactly(WxrProblem.Kind.INVALID_ENCODING);
        }
    }

    @Test
    void problemsAreCappedAndCounted() {
        String xml = TestWxr.doc("<item><title>" + "a & ".repeat(3_000) + "</title></item>");
        try (WxrReader reader = WxrReader.open(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), WxrReadOptions.LENIENT)) {
            while (reader.hasNext()) {
                reader.next();
            }
            assertThat(reader.problemCount()).isEqualTo(3_000);
            assertThat(reader.problems()).hasSize(1_001);
            assertThat(reader.problems().get(1_000).kind()).isEqualTo(WxrProblem.Kind.TOO_MANY_PROBLEMS);
        }
    }
}
