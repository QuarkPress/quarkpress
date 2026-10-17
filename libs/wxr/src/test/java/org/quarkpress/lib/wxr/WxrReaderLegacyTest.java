package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WxrReaderLegacyTest {

    private static final String ITEM = "<item><title>Hi</title><wp:post_id>5</wp:post_id></item>";

    private static String withVersion(String version) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" " + TestWxr.NAMESPACES
                + ">\n<channel>\n" + version + ITEM + "\n</channel>\n</rss>\n";
    }

    @Test
    void readsExampleC() {
        // Reference Example C (WXR 1.0 from the WP 3.0 template). Correct decoding of the title verified with
        // PHP 8.4 html_entity_decode(..., ENT_XML1); the regex parser keeps it raw, the XML-processor parser
        // returns "&\u2019s day" .
        TestWxr.Result r = TestWxr.read(TestWxr.resource("example-c.xml"), WxrReadOptions.STRICT);
        assertThat(r.site())
                .isEqualTo(new WxrSite(
                        "Old Blog",
                        "http://old.test",
                        "Legacy",
                        "Thu, 01 Jul 2010 12:00:00 +0000",
                        "en",
                        "1.0",
                        "http://old.test",
                        "http://old.test",
                        "http://wordpress.org/?v=3.0"));
        WxrComment bob = WxrComment.builder()
                .id(3L)
                .author("Bob")
                .authorEmail("bob@old.test")
                .authorUrl("http://bob.test/")
                .authorIp("10.0.0.1")
                .date("2010-06-30 07:00:00")
                .dateGmt("2010-06-30 11:00:00")
                .content("Hi")
                .approved("1")
                .type("")
                .parent(0L)
                .userId(0L)
                .build();
        WxrItem item = WxrItem.builder()
                .title("Tom & Jerry\u2019s day")
                .link("http://old.test/?p=7")
                .pubDate("Wed, 30 Jun 2010 10:00:00 +0000")
                .creator("admin")
                .guid("http://old.test/?p=7")
                .guidIsPermaLink(false)
                .description("")
                .content("Body")
                .excerpt("")
                .postId(7L)
                .postDate("2010-06-30 06:00:00")
                .postDateGmt("2010-06-30 10:00:00")
                .commentStatus("open")
                .pingStatus("open")
                .postName("tom-jerry")
                .status("publish")
                .postParent(0L)
                .menuOrder(0L)
                .postType("post")
                .postPassword("")
                .isSticky(0L)
                .terms(List.of(
                        new WxrItemTerm(null, null, "Local"),
                        new WxrItemTerm("category", "local", "Local"),
                        new WxrItemTerm("tag", null, "misc"),
                        new WxrItemTerm("tag", "misc", "misc")))
                .postMeta(List.of(new WxrMeta("_edit_last", "1")))
                .comments(List.of(bob))
                .build();
        assertThat(r.entries().stream().map(Canonical::strip).toList())
                .containsExactly(
                        new WxrCategory(null, "news", "", "News", null, List.of()),
                        new WxrCategory(null, "local", "News", "Local", null, List.of()),
                        new WxrTag(null, "misc", "misc", null, List.of()),
                        item);
        assertThat(r.problems()).isEmpty();
        assertThat(item.terms())
                .extracting(WxrItemTerm::taxonomy)
                .containsExactly("category", "category", "post_tag", "post_tag");
    }

    @Test
    void missingVersionIsAProblemInLenientModeAndAnErrorInStrictMode() {
        // every importer parser rejects such a file; lenient mode still reads it.
        TestWxr.Result r = TestWxr.read(withVersion(""), WxrReadOptions.LENIENT);
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.VERSION_MISSING);
        assertThat(r.items()).extracting(WxrItem::title).containsExactly("Hi");
        assertThatThrownBy(() -> TestWxr.read(withVersion(""), WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VERSION_MISSING));
    }

    @Test
    void invalidVersionsAreRejectedLikeTheImporter() {
        // verified: 1.2.1, 1.x and empty all fail with "missing/invalid WXR version number".
        for (String bad : new String[] {"1.2.1", "1.x", ""}) {
            String xml = withVersion("<wp:wxr_version>" + bad + "</wp:wxr_version>");
            assertThat(TestWxr.read(xml, WxrReadOptions.LENIENT).kinds())
                    .as(bad)
                    .containsExactly(WxrProblem.Kind.VERSION_INVALID);
            assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT)).isInstanceOf(WxrException.class);
        }
    }

    @Test
    void versionIsTrimmedLikeTheImporter() {
        // The importer trims the version before matching ^\d+\.\d+$.
        String xml = withVersion("<wp:wxr_version> 1.2\n</wp:wxr_version>");
        assertThat(TestWxr.read(xml, WxrReadOptions.STRICT).problems()).isEmpty();
    }

    @Test
    void newerVersionsAreReadWithANotice() {
        // the importer warns when the version is a number greater than 1.2; PHP compares "1.10" as 1.1.
        TestWxr.Result newer = TestWxr.read(withVersion("<wp:wxr_version>1.3</wp:wxr_version>"), WxrReadOptions.STRICT);
        assertThat(newer.kinds()).containsExactly(WxrProblem.Kind.VERSION_NEWER);
        assertThat(newer.items()).hasSize(1);
        assertThat(TestWxr.read(withVersion("<wp:wxr_version>1.10</wp:wxr_version>"), WxrReadOptions.STRICT)
                        .problems())
                .isEmpty();
    }

    @Test
    void aFileWithoutEntriesIsCheckedAtTheEndOfTheChannel() {
        String xml = "<rss version=\"2.0\"><channel><title>T</title></channel></rss>";
        assertThat(TestWxr.read(xml, WxrReadOptions.LENIENT).kinds()).containsExactly(WxrProblem.Kind.VERSION_MISSING);
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT)).isInstanceOf(WxrException.class);
    }

    @Test
    void allSixWordPressNamespacesAreRecognized() {
        String xml = withVersion("<wp:wxr_version>1.0</wp:wxr_version>")
                .replace("http://wordpress.org/export/1.2/", "https://wordpress.org/export/1.0/");
        TestWxr.Result r = TestWxr.read(xml, WxrReadOptions.STRICT);
        assertThat(r.site().wxrVersion()).isEqualTo("1.0");
        assertThat(r.items().get(0).postId()).isEqualTo(5L);
    }

    @Test
    void anUnknownUriBoundToWpIsAcceptedOnlyWhenLenient() {
        // Harness case ns-unknown-1.3.xml: the XML-processor parser finds no version.
        String xml = withVersion("<wp:wxr_version>1.2</wp:wxr_version>").replace("export/1.2/", "export/1.3/");
        TestWxr.Result lenient = TestWxr.read(xml, WxrReadOptions.LENIENT);
        assertThat(lenient.kinds()).containsExactly(WxrProblem.Kind.UNKNOWN_NAMESPACE);
        assertThat(lenient.items().get(0).postId()).isEqualTo(5L);
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VERSION_MISSING));
    }

    @Test
    void aDocumentThatIsNotRssIsReported() {
        String xml = "<feed><entry/></feed>";
        assertThat(TestWxr.read(xml, WxrReadOptions.LENIENT).kinds())
                .containsExactly(WxrProblem.Kind.NOT_WXR, WxrProblem.Kind.NOT_WXR, WxrProblem.Kind.VERSION_MISSING);
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.NOT_WXR));
    }

    @Test
    void fieldOrderInsideAnItemDoesNotMatter() {
        // harness case field-after-meta.xml shows the XML-processor parser losing wp:status here.
        String xml = TestWxr.doc("<item><wp:postmeta><wp:meta_key>k</wp:meta_key>"
                + "<wp:meta_value><![CDATA[v]]></wp:meta_value></wp:postmeta>"
                + "<wp:status><![CDATA[draft]]></wp:status></item>");
        WxrItem item = TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0);
        assertThat(item.status()).isEqualTo("draft");
        assertThat(item.postMeta()).containsExactly(new WxrMeta("k", "v"));
    }

    @Test
    void plainTextFieldsAreEntityDecoded() {
        // before WP 4.6 many scalars were plain text, entity-escaped.
        String xml = TestWxr.doc("<item><wp:post_password>a&amp;b&lt;c</wp:post_password>"
                + "<wp:meta_key>ignored outside meta</wp:meta_key></item>");
        WxrItem item = TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0);
        assertThat(item.postPassword()).isEqualTo("a&b<c");
        assertThat(item.rawFields()).containsEntry("wp:meta_key", "ignored outside meta");
    }

    @Test
    void oddIntegersKeepTheirText() {
        // such values appear in real files; validation is the importer's job.
        String xml = TestWxr.doc("<item><wp:post_id>12abc</wp:post_id><wp:menu_order> 7 </wp:menu_order></item>"
                + "<item><wp:post_id>0</wp:post_id></item><item><wp:post_id>0</wp:post_id></item>");
        List<WxrItem> items = TestWxr.read(xml, WxrReadOptions.STRICT).items();
        assertThat(items.get(0).postId()).isNull();
        assertThat(items.get(0).rawFields()).containsEntry("wp:post_id", "12abc");
        assertThat(items.get(0).menuOrder()).isEqualTo(7L);
        assertThat(items).hasSize(3);
        assertThat(items.get(2).postId()).isZero();
    }

    @Test
    void squarespaceStyleAuthorsAreSkipped() {
        // Harness case wp-author-variant.xml; the spec reads unknown elements as skippable.
        String xml = TestWxr.doc("<wp:wp_author><wp:author_login>x</wp:author_login></wp:wp_author>"
                + "<wp:author><wp:author_id>3</wp:author_id><wp:author_email>e@x</wp:author_email></wp:author>");
        assertThat(TestWxr.read(xml, WxrReadOptions.STRICT).entries().stream()
                        .map(Canonical::strip)
                        .toList())
                .containsExactly(new WxrAuthor(3L, null, "e@x", null, null, null));
    }

    @Test
    void anEmptyDomainStaysEmptyWhileAnAbsentOneIsNull() {
        // The importer's expat and SimpleXML parsers keep domain="" as an empty taxonomy; only the regex parser
        // skips it. The reader reports the attribute as written and leaves the decision to the importer.
        String xml = TestWxr.doc("<item><category domain=\"\" nicename=\"x\"><![CDATA[X]]></category>"
                + "<category nicename=\"y\"><![CDATA[Y]]></category></item>");
        WxrItem item = TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0);
        assertThat(item.terms()).containsExactly(new WxrItemTerm("", "x", "X"), new WxrItemTerm(null, "y", "Y"));
        assertThat(item.terms().get(0).taxonomy()).isEmpty();
        assertThat(item.terms().get(1).taxonomy()).isEqualTo("category");
    }
}
