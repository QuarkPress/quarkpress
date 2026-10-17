package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class WxrWriterTest {

    private static final WxrSite SITE = new WxrSite(
            "S",
            "https://s.test",
            "",
            "Thu, 01 Jan 2026 00:00:00 +0000",
            "en",
            null,
            "https://s.test",
            "https://s.test",
            null);

    private static byte[] write(WxrSite site, Consumer<WxrWriter> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WxrWriter writer = WxrWriter.open(out, site)) {
            body.accept(writer);
        }
        return out.toByteArray();
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] exampleA() {
        return write(ExampleA.SITE, w -> {
            w.author(ExampleA.AUTHOR);
            w.category(ExampleA.CATEGORY);
            w.tag(ExampleA.TAG);
            w.item(ExampleA.ITEM);
        });
    }

    @Test
    void exampleAHasWordPressesElementTree() throws Exception {
        // Element names, order, attributes, leaf text and CDATA usage all equal WordPress 7.1.3's Example A.
        assertThat(XmlTree.flatten(XmlTree.parse(exampleA())))
                .containsExactlyEntriesOf(XmlTree.flatten(XmlTree.parse(TestWxr.resource("example-a.xml"))));
    }

    @Test
    void exampleAReadsBackStrictly() {
        TestWxr.Result r = TestWxr.read(exampleA(), WxrReadOptions.STRICT);
        assertThat(r.entries().stream().map(Canonical::strip).toList()).containsExactlyElementsOf(ExampleA.entries());
        assertThat(r.site()).isEqualTo(ExampleA.SITE);
    }

    @Test
    void contentIsSplitLikeWordPress() {
        assertThat(text(exampleA()))
                .contains("<content:encoded><![CDATA[<p>Use x[y[0]]]]><![CDATA[> z</p>]]></content:encoded>")
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<rss version=\"2.0\"");
        assertThat(exampleA()[0]).as("no BOM").isEqualTo((byte) '<');
    }

    @Test
    void commentUrlAndCategoryAttributesAreEscaped() {
        // D7: WordPress writes both raw (R48, R44), which makes its file malformed.
        WxrItem item = WxrItem.builder()
                .terms(List.of(new WxrItemTerm("a\"b&c<d", "x&y", "N")))
                .comments(List.of(WxrComment.builder()
                        .authorUrl("https://ann.test/?a=1&b=2")
                        .build()))
                .build();
        String out = text(write(SITE, w -> w.item(item)));
        assertThat(out)
                .contains("<wp:comment_author_url>https://ann.test/?a=1&amp;b=2</wp:comment_author_url>")
                .contains("<category domain=\"a&quot;b&amp;c&lt;d\" nicename=\"x&amp;y\"><![CDATA[N]]></category>");
        WxrItem back = TestWxr.read(out, WxrReadOptions.STRICT).items().get(0);
        assertThat(back.comments().get(0).authorUrl()).isEqualTo("https://ann.test/?a=1&b=2");
        assertThat(back.terms()).containsExactly(new WxrItemTerm("a\"b&c<d", "x&y", "N"));
    }

    @Test
    void illegalCharactersBecomeReplacementCharacters() {
        // D8: WordPress writes a\x01b raw (R22).
        byte[] out = write(
                SITE,
                w -> w.item(
                        WxrItem.builder().content("a\u0001b").title("\uD800").build()));
        assertThat(text(out)).contains("<![CDATA[a\uFFFDb]]>").contains("<title><![CDATA[\uFFFD]]></title>");
        for (byte b : out) {
            assertThat(b).isNotEqualTo((byte) 1);
        }
    }

    @Test
    void carriageReturnsSurvive() {
        WxrItem item = WxrItem.builder()
                .content("line1\r\nline2\r")
                .link("https://s.test/\r")
                .build();
        WxrItem back = TestWxr.read(write(SITE, w -> w.item(item)), WxrReadOptions.STRICT)
                .items()
                .get(0);
        assertThat(back.content()).isEqualTo("line1\r\nline2\r");
        assertThat(back.link()).isEqualTo("https://s.test/\r");
    }

    @Test
    void navMenusUseWordPressesShortForm() {
        // Verified (export): out-full.xml writes the nav menu term on one line with a plain nav_menu taxonomy.
        String out = text(write(SITE, w -> w.term(new WxrTerm(9L, "nav_menu", "main", "ignored", "Main", "x", null))));
        assertThat(out)
                .contains("<wp:term><wp:term_id>9</wp:term_id><wp:term_taxonomy>nav_menu</wp:term_taxonomy>"
                        + "<wp:term_slug><![CDATA[main]]></wp:term_slug><wp:term_name><![CDATA[Main]]></wp:term_name>"
                        + "</wp:term>");
    }

    @Test
    void attachmentUrlIsWrittenOnlyForAttachments() {
        String out = text(write(SITE, w -> {
            w.item(WxrItem.builder()
                    .postType("post")
                    .attachmentUrl("https://s.test/a.png")
                    .build());
            w.item(WxrItem.builder()
                    .postType("attachment")
                    .attachmentUrl("https://s.test/b.png")
                    .build());
        }));
        assertThat(out).doesNotContain("a.png").contains("<wp:attachment_url><![CDATA[https://s.test/b.png]]>");
    }

    @Test
    void emptyNamesAreOmittedButZeroIsKept() {
        // R10: WordPress omits an empty cat_name; its empty() also drops "0" (D12), we keep it.
        String out = text(write(SITE, w -> {
            w.category(new WxrCategory(1L, "a", "", "", null, null));
            w.category(new WxrCategory(2L, "b", "", "0", "", null));
        }));
        assertThat(out).containsOnlyOnce("<wp:cat_name>").contains("<wp:cat_name><![CDATA[0]]></wp:cat_name>");
        assertThat(out).doesNotContain("category_description");
    }

    @Test
    void unknownParentIsRejected() {
        assertThatThrownBy(
                        () -> write(SITE, w -> w.category(new WxrCategory(3L, "child", "news", "Child", null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'child'")
                .hasMessageContaining("'news'");
        assertThatThrownBy(
                        () -> write(SITE, w -> w.term(new WxrTerm(8L, "genre", "sci-fi", "fiction", "S", null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'fiction'");
        // R31: WXR 1.0 data names the parent by its name.
        write(SITE, w -> {
            w.category(new WxrCategory(null, "news", "", "News", null, null));
            w.category(new WxrCategory(null, "local", "News", "Local", null, null));
        });
    }

    @Test
    void sectionsMustComeInWordPressOrder() {
        assertThatThrownBy(() -> write(SITE, w -> {
                    w.item(WxrItem.builder().build());
                    w.author(new WxrAuthor(1L, "a", "", "", "", ""));
                }))
                .isInstanceOf(IllegalStateException.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WxrWriter writer = WxrWriter.open(out, SITE);
        writer.close();
        writer.close();
        assertThatThrownBy(() -> writer.item(WxrItem.builder().build())).isInstanceOf(IllegalStateException.class);
        assertThat(text(out.toByteArray())).endsWith("</channel>\n</rss>\n");
    }

    @Test
    void integersFallBackToTheirRawTextOrAreOmitted() {
        String out = text(write(SITE, w -> {
            w.item(WxrItem.builder().rawFields(Map.of("wp:post_id", "12abc")).build());
            w.item(WxrItem.builder().build());
        }));
        assertThat(out).containsOnlyOnce("<wp:post_id>").contains("<wp:post_id>12abc</wp:post_id>");
    }

    @Test
    void pubDateDefaultsToTheClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-17T04:03:27Z"), ZoneOffset.UTC);
        WxrSite noDate = new WxrSite("S", "https://s.test", "", null, "en", null, null, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WxrWriter.open(out, noDate, clock).close();
        assertThat(text(out.toByteArray())).contains("<pubDate>Sat, 17 Oct 2026 04:03:27 +0000</pubDate>");
    }

    /** Reads a file, writes what was read, and reads the result again. */
    private static TestWxr.Result rewrite(TestWxr.Result first) {
        byte[] out = write(first.site(), w -> {
            for (WxrEntry e : first.entries()) {
                switch (e) {
                    case WxrAuthor a -> w.author(a);
                    case WxrCategory c -> w.category(c);
                    case WxrTag t -> w.tag(t);
                    case WxrTerm t -> w.term(t);
                    case WxrItem i -> w.item(i);
                }
            }
        });
        return TestWxr.read(out, WxrReadOptions.STRICT);
    }

    @Test
    void referenceExamplesRoundTripFieldForField() {
        for (String name : List.of("example-a.xml", "example-b.xml", "example-c.xml")) {
            TestWxr.Result first = TestWxr.read(TestWxr.resource(name), WxrReadOptions.STRICT);
            TestWxr.Result second = rewrite(first);
            assertThat(second.problems()).as(name).isEmpty();
            // Every field, raw fields included, comes back equal.
            assertThat(second.entries()).as(name).containsExactlyElementsOf(first.entries());
            // The file is always WXR 1.2, and a site without pubDate gets the clock's date.
            WxrSite expected = first.site();
            expected = new WxrSite(
                    expected.title(),
                    expected.link(),
                    expected.description(),
                    expected.pubDate() == null ? second.site().pubDate() : expected.pubDate(),
                    expected.language(),
                    WxrWriter.WXR_VERSION,
                    expected.baseSiteUrl(),
                    expected.baseBlogUrl(),
                    expected.generator());
            assertThat(second.site()).as(name).isEqualTo(expected);
        }
        assertThat(TestWxr.read(TestWxr.resource("example-c.xml"), WxrReadOptions.STRICT)
                        .site()
                        .wxrVersion())
                .isEqualTo("1.0");
    }

    @Test
    void absentFieldsStayAbsent() {
        // WordPress never has a missing field; for data read from a file that lacks one, null means "not written".
        WxrItem item = WxrItem.builder()
                .title("T")
                .comments(List.of(WxrComment.builder().content("c").build()))
                .postMeta(List.of(new WxrMeta("k", null)))
                .build();
        String out = text(write(SITE, w -> w.item(item)));
        assertThat(out.substring(out.indexOf("<item>")))
                .doesNotContain("<link>")
                .doesNotContain("<guid")
                .doesNotContain("wp:comment_author_url")
                .doesNotContain("wp:meta_value");
        WxrItem back = TestWxr.read(out, WxrReadOptions.STRICT).items().get(0);
        assertThat(Canonical.strip(back)).isEqualTo(item);
    }

    @Test
    void guidAttributeIsWrittenOnlyWhenKnown() {
        String out = text(write(SITE, w -> {
            w.item(WxrItem.builder().guid("g1").build());
            w.item(WxrItem.builder().guid("g2").guidIsPermaLink(true).build());
        }));
        assertThat(out).contains("<guid>g1</guid>").contains("<guid isPermaLink=\"true\">g2</guid>");
    }

    @Test
    void termParentsAreLookedUpWithinTheirTaxonomy() {
        assertThatThrownBy(() -> write(SITE, w -> {
                    w.term(new WxrTerm(1L, "genre", "fiction", "", "Fiction", null, null));
                    w.term(new WxrTerm(2L, "format", "short", "fiction", "Short", null, null));
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'short'")
                .hasMessageContaining("'fiction'");
        String out = text(write(SITE, w -> {
            w.term(new WxrTerm(1L, "genre", "fiction", "", "Fiction", null, null));
            w.term(new WxrTerm(2L, "genre", "sci-fi", "fiction", "Sci-fi", null, null));
        }));
        assertThat(out).contains("<wp:term_parent><![CDATA[fiction]]></wp:term_parent>");
    }

    @Test
    void eachEntryReachesTheStreamWhenItIsWritten() {
        // Memory holds one entry, never the document: the author is in the stream before the writer is closed.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WxrWriter writer = WxrWriter.open(out, SITE);
        writer.author(new WxrAuthor(1L, "streamed", "", "", "", ""));
        writer.item(WxrItem.builder().title("first").build());
        writer.item(WxrItem.builder().title("second").build());
        // A 64 KiB writer buffer may hold the tail, so push a large entry through to force earlier ones out.
        writer.item(WxrItem.builder().content("x".repeat(200_000)).build());
        assertThat(text(out.toByteArray()))
                .contains("streamed")
                .contains("first")
                .contains("second");
        writer.close();
    }

    @Test
    void hugeValuesAreWrittenInLinearTime() {
        // Every character of the value needs work: a split CDATA, a carriage return, an illegal character.
        int count = 2_000_000;
        String value = "]]>\r\u0001".repeat(count);
        Instant start = Instant.now();
        byte[] out = write(SITE, w -> w.item(WxrItem.builder().content(value).build()));
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(30));
        String body = "]]]]><![CDATA[>]]>&#13;<![CDATA[\uFFFD";
        assertThat(text(out)).contains("<content:encoded><![CDATA[" + body.repeat(count) + "]]></content:encoded>");
        assertThat(TestWxr.read(out, WxrReadOptions.STRICT).items().get(0).content())
                .isEqualTo("]]>\r\uFFFD".repeat(count));
    }

    /** Text that stresses escaping: markup, CDATA ends, CR, controls, lone and paired surrogates, any char. */
    private static final Gen<String> NASTY = Gens.lists(
                    Gens.oneOf(
                            Gens.elements(
                                    "]]>",
                                    "]]",
                                    "]",
                                    ">",
                                    "<",
                                    "&",
                                    "\"",
                                    "'",
                                    "\r",
                                    "\r\n",
                                    "\n",
                                    "\t",
                                    "\u0000",
                                    "\u0001",
                                    "\u001F",
                                    "\uD800",
                                    "\uDC00",
                                    "\uD83D\uDE00",
                                    "\uFFFE",
                                    "\uFFFF",
                                    "&amp;",
                                    "<![CDATA[",
                                    " ",
                                    "a"),
                            Gens.strings(Gens.unicodeChars(), 0, 3)),
                    0,
                    12)
            .map(parts -> String.join("", parts));

    @Test
    void generatedValuesReadBackAsTheirSanitizedForm() {
        Gen<List<String>> values = Gens.lists(NASTY, 7, 7);
        Properties.check("wxr writer round trip", 2_000, values, v -> {
            WxrItem item = WxrItem.builder()
                    .title(v.get(0))
                    .link(v.get(1))
                    .content(v.get(2))
                    .terms(List.of(new WxrItemTerm(v.get(3), v.get(4), v.get(5))))
                    .postMeta(List.of(new WxrMeta(v.get(6), v.get(2))))
                    .comments(List.of(WxrComment.builder().authorUrl(v.get(1)).build()))
                    .build();
            WxrCategory category = new WxrCategory(1L, v.get(3), "", v.get(0), v.get(6), null);
            byte[] out = write(SITE, w -> {
                w.category(category);
                w.item(item);
            });
            TestWxr.Result back = TestWxr.read(out, WxrReadOptions.STRICT);
            assertThat(back.problems()).isEmpty();
            WxrCategory c = (WxrCategory) back.entries().get(0);
            assertThat(c.nicename()).isEqualTo(XmlText.sanitize(v.get(3)));
            WxrItem i = back.items().get(0);
            assertThat(i.title()).isEqualTo(XmlText.sanitize(v.get(0)));
            assertThat(i.link()).isEqualTo(XmlText.sanitize(v.get(1)));
            assertThat(i.content()).isEqualTo(XmlText.sanitize(v.get(2)));
            assertThat(i.terms())
                    .containsExactly(new WxrItemTerm(
                            XmlText.sanitize(v.get(3)), XmlText.sanitize(v.get(4)), XmlText.sanitize(v.get(5))));
            assertThat(i.postMeta())
                    .containsExactly(new WxrMeta(XmlText.sanitize(v.get(6)), XmlText.sanitize(v.get(2))));
            assertThat(i.comments().get(0).authorUrl()).isEqualTo(XmlText.sanitize(v.get(1)));
        });
    }

    /** Fails the writes numbered {@code failFrom} up to {@code failTo} (1-based, inclusive); remembers close(). */
    private static final class FlakyStream extends ByteArrayOutputStream {
        int writes;
        int failFrom = Integer.MAX_VALUE;
        int failTo = Integer.MAX_VALUE;
        boolean closed;

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            writes++;
            if (writes >= failFrom && writes <= failTo) {
                throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));
            }
            super.write(b, off, len);
        }

        @Override
        public void write(byte[] b) throws java.io.IOException {
            write(b, 0, b.length);
        }

        @Override
        public void close() {
            closed = true;
        }

        int count(String needle) {
            String all = toString(StandardCharsets.UTF_8);
            int n = 0;
            for (int at = all.indexOf(needle); at >= 0; at = all.indexOf(needle, at + 1)) {
                n++;
            }
            return n;
        }
    }

    /** An OutputStream wrapper that turns the flaky stream's unchecked failures into the IOExceptions streams throw. */
    private static java.io.OutputStream checked(FlakyStream flaky) {
        return new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                try {
                    flaky.write(b, off, len);
                } catch (java.io.UncheckedIOException e) {
                    throw e.getCause();
                }
            }

            @Override
            public void close() {
                flaky.close();
            }
        };
    }

    @Test
    void anIoErrorInsideAnEntryBreaksTheWriterWithoutDuplicatesOrTail() {
        FlakyStream flaky = new FlakyStream();
        WxrWriter writer = WxrWriter.open(checked(flaky), SITE);
        writer.author(new WxrAuthor(1L, "first-author", "", "", "", ""));
        writer.item(
                WxrItem.builder().title("item-one").content("x".repeat(100_000)).build());
        int before = flaky.writes;
        flaky.failFrom = before + 3; // a few chunks of item two reach the stream, then one write fails
        flaky.failTo = before + 3;
        assertThatThrownBy(() -> writer.item(WxrItem.builder()
                        .title("item-two")
                        .content("y".repeat(200_000))
                        .build()))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasRootCauseMessage("disk full");
        // the stream works again: nothing more may be written to it all the same
        assertThatThrownBy(
                        () -> writer.item(WxrItem.builder().title("item-three").build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> writer.author(new WxrAuthor(2L, "late", "", "", "", "")))
                .isInstanceOf(IllegalStateException.class);
        writer.close();
        assertThat(flaky.closed).isTrue();
        assertThat(flaky.count("<?xml")).isEqualTo(1);
        assertThat(flaky.count("first-author")).isEqualTo(1);
        assertThat(flaky.count("item-one")).isLessThanOrEqualTo(1);
        assertThat(flaky.count("item-two")).isLessThanOrEqualTo(1);
        assertThat(flaky.count("y")).isLessThanOrEqualTo(200_000);
        assertThat(flaky.count("item-three")).isZero();
        assertThat(flaky.count("</rss>"))
                .as("a broken writer does not end the document")
                .isZero();
        writer.close();
    }

    @Test
    void aRefusedEntryLeavesNoTraceAndTheWriterUsable() {
        String out = text(write(SITE, w -> {
            w.category(new WxrCategory(2L, "news", "", "News", null, null));
            assertThatThrownBy(() -> w.category(new WxrCategory(3L, "child", "nope", "Child", null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            w.category(new WxrCategory(4L, "local", "news", "Local", null, null));
            assertThatThrownBy(() -> w.term(new WxrTerm(8L, "genre", "sci-fi", "fiction", "S", null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            w.term(new WxrTerm(9L, "genre", "fiction", "", "F", null, null));
            w.item(WxrItem.builder().title("t").build());
            assertThatThrownBy(() -> w.tag(new WxrTag(5L, "php", "PHP", null, null)))
                    .isInstanceOf(IllegalStateException.class);
            w.item(WxrItem.builder().title("u").build());
        }));
        assertThat(out).doesNotContain("\t\t<wp:term>", "\t\t<wp:category>", "\t\t<item>", "\t\t\t", "sci-fi", "child");
        assertThat(out).contains("\n\t<wp:term>\n", "\n\t<wp:category>\n").endsWith("</channel>\n</rss>\n");
        TestWxr.Result r = TestWxr.read(out, WxrReadOptions.STRICT);
        assertThat(r.entries()).hasSize(5);
    }

    @Test
    void aFailingHeaderClosesTheStream() {
        FlakyStream flaky = new FlakyStream();
        flaky.failFrom = 1;
        WxrSite huge = new WxrSite("t".repeat(300_000), "l", "", null, "en", null, "b", "b", null);
        assertThatThrownBy(() -> WxrWriter.open(checked(flaky), huge)).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(flaky.closed).isTrue();

        FlakyStream ok = new FlakyStream();
        Clock broken = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("no time");
            }
        };
        WxrSite noDate = new WxrSite("t", "l", "", null, "en", null, "b", "b", null);
        assertThatThrownBy(() -> WxrWriter.open(ok, noDate, broken)).hasMessage("no time");
        assertThat(ok.closed).isTrue();
    }

    @Test
    void closeClosesTheStreamWhenWritingTheEndFails() {
        FlakyStream flaky = new FlakyStream();
        WxrWriter writer = WxrWriter.open(checked(flaky), SITE);
        writer.item(WxrItem.builder().title("t").build());
        flaky.failFrom = 1;
        assertThatThrownBy(writer::close).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(flaky.closed).isTrue();
        writer.close(); // already closed: nothing happens
    }
}
