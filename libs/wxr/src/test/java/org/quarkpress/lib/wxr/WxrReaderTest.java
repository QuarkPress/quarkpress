package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.phpserialize.PhpArray;
import org.quarkpress.lib.phpserialize.PhpString;

class WxrReaderTest {

    @Test
    void readsExampleA() {
        TestWxr.Result r = TestWxr.read(TestWxr.resource("example-a.xml"), WxrReadOptions.STRICT);
        assertThat(r.entries().stream().map(Canonical::strip).toList()).containsExactlyElementsOf(ExampleA.entries());
        assertThat(r.site()).isEqualTo(ExampleA.SITE);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void siteFieldsAreThoseSeenSoFar() {
        try (WxrReader reader =
                WxrReader.open(new ByteArrayInputStream(TestWxr.resource("example-a.xml")), WxrReadOptions.STRICT)) {
            WxrSite early = reader.site();
            assertThat(early.title()).isEqualTo("Example");
            assertThat(early.baseBlogUrl()).isEqualTo("https://example.test");
            assertThat(early.generator()).as("written after the terms (R6)").isNull();
            while (reader.hasNext()) {
                reader.next();
            }
            assertThat(reader.site().generator()).isEqualTo("https://wordpress.org/?v=7.1.3");
        }
    }

    @Test
    void rawFieldsKeepTheTextOfEveryField() {
        WxrItem item = TestWxr.read(TestWxr.resource("example-a.xml"), WxrReadOptions.STRICT)
                .items()
                .get(0);
        assertThat(item.rawFields())
                .containsEntry("title", "Hello")
                .containsEntry("dc:creator", "admin")
                .containsEntry("wp:post_id", "10")
                .containsEntry("guid@isPermaLink", "false")
                .containsEntry("content:encoded", "<p>Use x[y[0]]> z</p>");
        assertThat(item.comments().get(0).rawFields()).containsEntry("wp:comment_author_IP", "127.0.0.1");
    }

    @Test
    void serializedMetaParses() {
        WxrItem item = TestWxr.read(TestWxr.resource("example-a.xml"), WxrReadOptions.STRICT)
                .items()
                .get(0);
        assertThat(item.postMeta().get(0).parsed()).contains(PhpArray.of(PhpString.of("red")));
    }

    @Test
    void readsExampleB() {
        // Reference Example B: both runnable importer parsers return one post "Minimal", status publish,
        // type post, author admin, version 1.2, no authors, no terms.
        TestWxr.Result r = TestWxr.read(TestWxr.resource("example-b.xml"), WxrReadOptions.STRICT);
        WxrItem expected = WxrItem.builder()
                .title("Minimal")
                .creator("admin")
                .content("<p>Hi</p>")
                .postId(1L)
                .status("publish")
                .postType("post")
                .build();
        assertThat(r.entries().stream().map(Canonical::strip).toList()).containsExactly(expected);
        assertThat(r.site().wxrVersion()).isEqualTo("1.2");
        assertThat(r.site().title()).isNull();
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void nextPastTheEndThrows() {
        try (WxrReader reader =
                WxrReader.open(new ByteArrayInputStream(TestWxr.resource("example-b.xml")), WxrReadOptions.STRICT)) {
            assertThat(reader.next()).isInstanceOf(WxrItem.class);
            assertThat(reader.hasNext()).isFalse();
            assertThatThrownBy(reader::next).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void textIsNeverTrimmedAndCdataPiecesAreJoined() {
        // R24, R74.
        String xml = TestWxr.doc("<item><content:encoded><![CDATA[  a ]]]]><![CDATA[> b\n\n]]></content:encoded>"
                + "<excerpt:encoded>  x <![CDATA[y]]> &amp; z </excerpt:encoded></item>");
        WxrItem item = TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0);
        assertThat(item.content()).isEqualTo("  a ]]> b\n\n");
        assertThat(item.excerpt()).isEqualTo("  x y & z ");
    }

    @Test
    void valuesOverTheLimitThrowInBothModes() {
        String xml = TestWxr.doc("<item><content:encoded>" + "x".repeat(100) + "</content:encoded></item>");
        for (WxrReadOptions options : new WxrReadOptions[] {WxrReadOptions.STRICT, WxrReadOptions.LENIENT}) {
            assertThatThrownBy(() -> TestWxr.read(xml, options.withMaxCharsPerValue(50)))
                    .isInstanceOfSatisfying(
                            WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE));
        }
    }

    @Test
    void itemsOverTheLimitThrow() {
        String xml = TestWxr.doc("<item><title>1</title></item><item><title>2</title></item><item/>");
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.LENIENT.withMaxItems(2)))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.TOO_MANY_ITEMS));
        assertThat(TestWxr.read(xml, WxrReadOptions.LENIENT.withMaxItems(3)).items())
                .hasSize(3);
    }

    @Test
    void unknownElementsAreSkippedWholeAndUnknownLeavesKeptAsRawText() {
        // R34: plugins add elements in their own namespaces, inside the channel and inside items.
        String xml = TestWxr.doc("<image><url>u</url><title>not the site title</title></image>"
                + "<item><foo:box xmlns:foo=\"urn:x\"><foo:a>1</foo:a></foo:box>"
                + "<foo:note xmlns:foo=\"urn:x\">n</foo:note><title>T</title></item>");
        TestWxr.Result r = TestWxr.read(xml, WxrReadOptions.STRICT);
        assertThat(r.site().title()).isNull();
        WxrItem item = r.items().get(0);
        assertThat(item.title()).isEqualTo("T");
        assertThat(item.rawFields()).containsEntry("{urn:x}note", "n").doesNotContainKey("{urn:x}box");
    }

    @Test
    void theFirstOccurrenceOfAFieldWins() {
        String xml = TestWxr.doc("<item><title>first</title><title>second</title></item>");
        assertThat(TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0).title())
                .isEqualTo("first");
    }

    @Test
    void brokenXmlIsMalformed() {
        String xml = TestWxr.doc("<item><title>a</wrong></item>");
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.MALFORMED));
    }
}
