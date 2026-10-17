package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.phpserialize.PhpArray;
import org.quarkpress.lib.phpserialize.PhpBool;
import org.quarkpress.lib.phpserialize.PhpObject;
import org.quarkpress.lib.phpserialize.PhpString;

class WxrReaderEdgeCasesTest {

    private static TestWxr.Result lenient(String file) {
        return TestWxr.read(TestWxr.resource("cases/" + file), WxrReadOptions.LENIENT);
    }

    private static WxrException strictFailure(String file) {
        try {
            TestWxr.read(TestWxr.resource("cases/" + file), WxrReadOptions.STRICT);
        } catch (WxrException e) {
            return e;
        }
        throw new AssertionError("strict mode read " + file + " without an error");
    }

    private static WxrItem onlyItem(TestWxr.Result r) {
        assertThat(r.items()).hasSize(1);
        return r.items().get(0);
    }

    @Test
    void wellFormedVariantsReadWithoutProblems() {
        // bom, latin1-decl and standalone-no make the XML-processor parser fail (R55); SimpleXML and we accept them.
        for (String file : List.of(
                "bom.xml",
                "latin1-decl.xml",
                "standalone-no.xml",
                "one-line.xml",
                "ns-1.0-https.xml",
                "min-entity.xml")) {
            TestWxr.Result r = lenient(file);
            assertThat(r.problems()).as(file).isEmpty();
            assertThat(TestWxr.read(TestWxr.resource("cases/" + file), WxrReadOptions.STRICT)
                            .entries())
                    .as(file)
                    .isEqualTo(r.entries());
        }
        WxrItem bom = onlyItem(lenient("bom.xml"));
        assertThat(bom.title()).isEqualTo("Hi");
        assertThat(bom.creator()).isEqualTo("admin");
        assertThat(bom.content()).isEqualTo("body");
        assertThat(bom.postId()).isEqualTo(5L);
        assertThat(bom.postType()).isEqualTo("post");
        assertThat(onlyItem(lenient("one-line.xml")).content()).isEqualTo("body");
        assertThat(lenient("ns-1.0-https.xml").site().wxrVersion()).isEqualTo("1.0");
    }

    @Test
    void referencesDecodeCompletely() {
        // min-entity.xml: XML-processor gives guid "&x=1" and post_name "x<Az" (R56); correct decoding below.
        WxrItem item = onlyItem(lenient("min-entity.xml"));
        assertThat(item.title()).isEqualTo("A & B");
        assertThat(item.guid()).isEqualTo("https://example.test/?p=10&x=1");
        assertThat(item.guidIsPermaLink()).isFalse();
        assertThat(item.postName()).isEqualTo("x<yAz");
        assertThat(item.postType()).isEqualTo("premidpost");
        List<WxrItem> two = lenient("item-before-meta-ok-and-2-items.xml").items();
        assertThat(two).extracting(WxrItem::title).containsExactly("A & B", "Two");
        assertThat(two.get(1).postId()).isEqualTo(2L);
    }

    @Test
    void versionProblems() {
        assertThat(lenient("bad-version.xml").kinds()).containsExactly(WxrProblem.Kind.VERSION_INVALID);
        assertThat(onlyItem(lenient("bad-version.xml")).title()).isEqualTo("Hi");
        assertThat(lenient("no-version.xml").kinds()).containsExactly(WxrProblem.Kind.VERSION_MISSING);
        assertThat(onlyItem(lenient("no-version.xml")).title()).isEqualTo("Hi");
        assertThat(strictFailure("no-version.xml").kind()).isEqualTo(WxrProblem.Kind.VERSION_MISSING);
    }

    @Test
    void anUndeclaredWpPrefixIsReadAsWordPressesInLenientMode() {
        // no-wp-ns-decl.xml: the regex parser matches the literal "wp:" text and reads the post (R37); the
        // XML-processor and SimpleXML parsers find no version. Lenient mode follows the regex parser, strict refuses.
        TestWxr.Result r = lenient("no-wp-ns-decl.xml");
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.UNKNOWN_NAMESPACE);
        assertThat(r.problems().get(0).line()).isEqualTo(5);
        assertThat(r.site().title()).isEqualTo("T");
        assertThat(r.site().wxrVersion()).isEqualTo("1.2");
        assertThat(r.site().baseSiteUrl()).isEqualTo("http://s.test");
        WxrItem item = onlyItem(r);
        assertThat(item.title()).isEqualTo("Hi");
        assertThat(item.creator()).isEqualTo("admin");
        assertThat(item.content()).isEqualTo("body");
        assertThat(item.postId()).isEqualTo(5L);
        assertThat(item.postType()).isEqualTo("post");
        assertThat(strictFailure("no-wp-ns-decl.xml").kind()).isEqualTo(WxrProblem.Kind.MALFORMED);
    }

    @Test
    void everyUndeclaredWxrPrefixIsBoundAndADeclaredOneIsKept() {
        String xml = "<rss><channel><wp:wxr_version>1.2</wp:wxr_version>"
                + "<item><title>t</title><dc:creator>c</dc:creator><content:encoded>b</content:encoded>"
                + "<excerpt:encoded>e</excerpt:encoded><wp:post_id>7</wp:post_id></item></channel></rss>";
        TestWxr.Result r = TestWxr.read(xml, WxrReadOptions.LENIENT);
        WxrItem item = onlyItem(r);
        assertThat(item.creator()).isEqualTo("c");
        assertThat(item.content()).isEqualTo("b");
        assertThat(item.excerpt()).isEqualTo("e");
        assertThat(item.postId()).isEqualTo(7L);
        assertThat(r.kinds()).containsOnly(WxrProblem.Kind.UNKNOWN_NAMESPACE).hasSize(4);
        assertThat(r.problems()).extracting(WxrProblem::detail).allMatch(d -> d.contains("not declared"));

        // a binding on the root, even to another URI, is the document's own and stays
        TestWxr.Result own = TestWxr.read(
                "<rss xmlns:wp=\"urn:other\"><channel><wp:wxr_version>1.2</wp:wxr_version></channel></rss>",
                WxrReadOptions.LENIENT.withAcceptAnyWpNamespace(false));
        assertThat(own.site().wxrVersion()).isNull();
        assertThat(own.kinds()).containsExactly(WxrProblem.Kind.VERSION_MISSING);
    }

    @Test
    void aDocumentBindingTheInternalUriItselfIsNotReadAsWordPresses() {
        String xml = "<rss xmlns:wp=\"" + WxrNames.UNDECLARED + "wp\"><channel><wp:wxr_version>1.2</wp:wxr_version>"
                + "<item><title>t</title></item></channel></rss>";
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VERSION_MISSING));
        TestWxr.Result r = TestWxr.read(xml, WxrReadOptions.LENIENT.withAcceptAnyWpNamespace(false));
        assertThat(r.site().wxrVersion()).isNull();
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.VERSION_MISSING);
    }

    @Test
    void documentsWithoutAnElementAreNotWxr() {
        for (String xml : List.of(
                "",
                "   \n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- nothing -->\n",
                "just text")) {
            TestWxr.Result r = TestWxr.read(xml, WxrReadOptions.LENIENT);
            assertThat(r.entries()).as(xml).isEmpty();
            assertThat(r.kinds()).as(xml).containsExactly(WxrProblem.Kind.NOT_WXR);
            assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT))
                    .as(xml)
                    .isInstanceOfSatisfying(
                            WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.NOT_WXR));
        }
    }

    @Test
    void unknownNamespaceIsReadWithANotice() {
        TestWxr.Result r = lenient("ns-unknown-1.3.xml");
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.UNKNOWN_NAMESPACE);
        assertThat(onlyItem(r).title()).isEqualTo("Hi");
    }

    @Test
    void repairsAreRecorded() {
        TestWxr.Result amp = lenient("raw-amp.xml");
        assertThat(onlyItem(amp).title()).isEqualTo("Tom & Jerry");
        assertThat(amp.problems()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(WxrProblem.Kind.BARE_AMPERSAND);
            assertThat(p.line()).isEqualTo(6);
            assertThat(p.column()).isEqualTo(12);
        });
        assertThat(onlyItem(lenient("raw-lt.xml")).title()).isEqualTo("a < b");
        assertThat(lenient("raw-lt.xml").kinds()).containsExactly(WxrProblem.Kind.BARE_LESS_THAN);
        // named-entity.xml: the XML-processor parser keeps "a &nbsp; b" verbatim (R55); so do we.
        assertThat(onlyItem(lenient("named-entity.xml")).title()).isEqualTo("a &nbsp; b");
        assertThat(lenient("named-entity.xml").kinds()).containsExactly(WxrProblem.Kind.UNDEFINED_ENTITY);
        // invalid-utf8.xml: a lone E9 byte in a UTF-8 file.
        assertThat(onlyItem(lenient("invalid-utf8.xml")).title()).isEqualTo("Caf\uFFFD");
        assertThat(lenient("invalid-utf8.xml").kinds()).containsExactly(WxrProblem.Kind.INVALID_ENCODING);
        // ctrl-char.xml: XML-processor keeps "a\u0001b\u000bc"; XML-illegal characters become U+FFFD (R72).
        assertThat(onlyItem(lenient("ctrl-char.xml")).content()).isEqualTo("a\uFFFDb\uFFFDc");
        assertThat(lenient("ctrl-char.xml").kinds())
                .containsExactly(WxrProblem.Kind.ILLEGAL_CHARACTER, WxrProblem.Kind.ILLEGAL_CHARACTER);
        assertThat(strictFailure("ctrl-char.xml").kind()).isEqualTo(WxrProblem.Kind.ILLEGAL_CHARACTER);
        assertThat(strictFailure("raw-amp.xml").kind()).isEqualTo(WxrProblem.Kind.BARE_AMPERSAND);
    }

    @Test
    void doctypeIsSkippedOrRefused() {
        TestWxr.Result r = lenient("doctype.xml");
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.DOCTYPE);
        assertThat(onlyItem(r).title()).isEqualTo("Hi");
        assertThat(strictFailure("doctype.xml").kind()).isEqualTo(WxrProblem.Kind.DOCTYPE);
    }

    @Test
    void textShapes() {
        assertThat(onlyItem(lenient("split-cdata.xml")).content()).isEqualTo("x]]>y");
        assertThat(onlyItem(lenient("whitespace-content.xml")).content()).isEqualTo("  \n\n  ");
        assertThat(onlyItem(lenient("cdata-multiline-blank.xml")).content()).isEqualTo("line1\n\nline3");
    }

    @Test
    void legacyCategoriesAndFieldOrder() {
        assertThat(onlyItem(lenient("category-no-nicename.xml")).terms())
                .containsExactly(
                        new WxrItemTerm(null, null, "Old style"),
                        new WxrItemTerm("category", "a", "A"),
                        new WxrItemTerm("tag", "t", "T"));
        WxrItem afterMeta = onlyItem(lenient("field-after-meta.xml"));
        assertThat(afterMeta.status()).isEqualTo("draft");
        assertThat(afterMeta.postMeta()).containsExactly(new WxrMeta("k", "v"));
    }

    @Test
    void serializedMetaParses() {
        List<WxrMeta> meta = onlyItem(lenient("serialized-meta.xml")).postMeta();
        assertThat(meta.get(0).parsed()).contains(PhpArray.of(PhpString.of("x"), PhpBool.of(true)));
        assertThat(meta.get(1).parsed()).contains(new PhpObject("stdClass", PhpArray.empty()));
    }

    @Test
    void squarespaceAuthorIsSkippedAndTheOtherKept() {
        TestWxr.Result r = lenient("wp-author-variant.xml");
        assertThat(r.entries().stream().map(Canonical::strip).toList().get(0))
                .isEqualTo(new WxrAuthor(3L, null, "e@x", null, null, null));
        assertThat(r.items()).hasSize(1);
    }

    @Test
    void anUnclosedChannelIsTruncation() {
        // unclosed.xml: the XML-processor parser still yields the post (R55, R75).
        TestWxr.Result r = lenient("unclosed.xml");
        assertThat(onlyItem(r).title()).isEqualTo("Hi");
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED);
        try (WxrReader reader = WxrReader.open(
                new ByteArrayInputStream(TestWxr.resource("cases/unclosed.xml")), WxrReadOptions.STRICT)) {
            assertThat(reader.next()).isInstanceOf(WxrItem.class);
            assertThatThrownBy(reader::hasNext)
                    .isInstanceOfSatisfying(
                            WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.TRUNCATED));
        }
    }

    @Test
    void wordPressExportsWithRawAmpersandsAndControlCharactersRead() {
        // export-full.xml has the raw & of R48; export-quirks.xml the raw control characters of R22.
        TestWxr.Result full = TestWxr.read(TestWxr.resource("export-full.xml"), WxrReadOptions.LENIENT);
        assertThat(full.kinds()).containsExactly(WxrProblem.Kind.BARE_AMPERSAND);
        assertThat(full.site().title()).isEqualTo("Ex & Co <b>Blog</b>");
        assertThat(full.site().description()).isEqualTo("Just \"another\" site");
        assertThat(full.entries()).hasSize(2 + 3 + 1 + 3 + 3);
        WxrItem hello = full.items().get(0);
        assertThat(hello.title()).isEqualTo("Hello & <World>");
        assertThat(hello.guid()).isEqualTo("https://example.test/?p=10&x=1");
        assertThat(hello.content()).isEqualTo("<!-- wp:paragraph -->\n<p>Code: a[b[0]]> c</p>\n<!-- /wp:paragraph -->");
        assertThat(hello.isSticky()).isEqualTo(1L);
        assertThat(hello.postMeta())
                .containsExactly(new WxrMeta("_thumbnail_id", "12"), new WxrMeta("arr", "a:1:{s:1:\"k\";s:2:\"v]\";}"));
        assertThat(hello.comments()).extracting(WxrComment::authorUrl).containsExactly("https://ann.test/?a=1&b=2", "");
        assertThat(hello.comments().get(0).content()).isEqualTo("Nice ]]> post");
        assertThat(hello.comments().get(1).parent()).isEqualTo(100L);
        assertThat(full.items().get(2).attachmentUrl())
                .isEqualTo("https://example.test/wp-content/uploads/2026/01/logo.png");
        assertThat(full.entries().get(4))
                .isInstanceOfSatisfying(
                        WxrCategory.class, c -> assertThat(c.parent()).isEqualTo("news"));

        TestWxr.Result quirks = TestWxr.read(TestWxr.resource("export-quirks.xml"), WxrReadOptions.LENIENT);
        WxrItem draft = quirks.items().get(0);
        assertThat(draft.title()).isEqualTo("Caf\u00e9");
        assertThat(draft.content()).isEqualTo("a\uFFFDb\uFFFDc\uFFFDd");
        assertThat(draft.pubDate()).isEmpty();
        assertThat(draft.status()).isEqualTo("draft");
        assertThat(quirks.kinds())
                .containsOnly(WxrProblem.Kind.ILLEGAL_CHARACTER)
                .hasSize(3);
    }

    private static byte[] gzip(byte[] bytes) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(out)) {
            z.write(bytes);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Test
    void whitespaceBeforeTheXmlDeclarationIsDroppedOrRefused() {
        // themes and plugins print blank lines before the export; the declaration names Latin-1, so the e-acute
        // byte reads right only if the declaration is still found behind the whitespace
        String body = "<item><title>caf\u00e9 &amp</title></item>\n<x></y>";
        String doc = TestWxr.doc(body).replace("encoding=\"UTF-8\"", "encoding=\"ISO-8859-1\"");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        for (String space : List.of("\n", "\n\n", " \t\r\n ", "\r\r")) {
            byte[] latin1 = (space + doc).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] utf8 =
                    (space + doc.replace("ISO-8859-1", "UTF-8")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] withBom = new byte[bom.length + utf8.length];
            System.arraycopy(bom, 0, withBom, 0, bom.length);
            System.arraycopy(utf8, 0, withBom, bom.length, utf8.length);
            long lines = space.chars().filter(c -> c == '\n').count()
                    + space.replace("\r\n", "\n").chars().filter(c -> c == '\r').count();
            for (byte[] bytes : List.of(latin1, withBom, gzip(latin1), gzip(withBom))) {
                TestWxr.Result r = TestWxr.read(bytes, WxrReadOptions.LENIENT);
                assertThat(r.items()).extracting(WxrItem::title).as(space).containsExactly("caf\u00e9 &amp");
                assertThat(r.kinds())
                        .as(space)
                        .containsExactly(
                                WxrProblem.Kind.INVALID_MARKUP,
                                WxrProblem.Kind.BARE_AMPERSAND,
                                WxrProblem.Kind.MALFORMED);
                assertThat(r.problems().get(0).detail()).isEqualTo("whitespace before the XML declaration");
                // positions after the declaration stay those of the file: the pre-filter's and the parser's
                assertThat(r.problems().get(1).line()).as(space).isEqualTo(5 + lines);
                assertThat(r.problems().get(2).line()).as(space).isEqualTo(6 + lines);
                assertThatThrownBy(() -> TestWxr.read(bytes, WxrReadOptions.STRICT))
                        .isInstanceOfSatisfying(
                                WxrException.class,
                                e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.INVALID_MARKUP));
            }
        }
        // whitespace before a document without a declaration is fine, as XML allows it there
        TestWxr.Result plain = TestWxr.read(
                "\n \n"
                        + TestWxr.doc("<item><title>t</title></item>")
                                .substring(TestWxr.doc("").indexOf("<rss")),
                WxrReadOptions.STRICT);
        assertThat(plain.items()).extracting(WxrItem::title).containsExactly("t");
        assertThat(plain.problems()).isEmpty();
    }

    @Test
    void aClosedReaderHasNoNextEntry() {
        byte[] bytes = TestWxr.doc("<item><title>a</title></item><item><title>b</title></item>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WxrReader reader = WxrReader.open(new ByteArrayInputStream(bytes));
        assertThat(reader.hasNext()).isTrue();
        reader.close();
        assertThat(reader.hasNext()).isFalse();
        assertThatThrownBy(reader::next).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void theEncodingSniffAndThePreFilterAgreeOnHowMuchLeadingWhitespaceIsAllowed() {
        String doc = TestWxr.doc("<item><title>caf\u00e9</title></item>")
                .replace("encoding=\"UTF-8\"", "encoding=\"ISO-8859-1\"");
        for (int blanks : new int[] {2_000, XmlInput.MAX_LEADING_WHITESPACE - 1}) {
            byte[] bytes = (" ".repeat(blanks) + doc).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            TestWxr.Result r = TestWxr.read(bytes, WxrReadOptions.LENIENT);
            assertThat(r.items())
                    .extracting(WxrItem::title)
                    .as("%d blanks", blanks)
                    .containsExactly("caf\u00e9");
            assertThat(r.kinds()).as("%d blanks", blanks).containsExactly(WxrProblem.Kind.INVALID_MARKUP);
        }
        // at the bound neither takes it for a declaration: read as UTF-8, the declaration is out of place
        byte[] far = (" ".repeat(XmlInput.MAX_LEADING_WHITESPACE) + doc)
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        TestWxr.Result r = TestWxr.read(far, WxrReadOptions.LENIENT);
        assertThat(r.kinds()).contains(WxrProblem.Kind.INVALID_ENCODING).doesNotContain(WxrProblem.Kind.INVALID_MARKUP);
    }
}
