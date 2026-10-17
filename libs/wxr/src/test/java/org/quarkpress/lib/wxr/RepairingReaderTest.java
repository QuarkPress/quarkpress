package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class RepairingReaderTest {

    private static String repair(String xml, ProblemLog log) throws IOException {
        return repair(xml, log, 5);
    }

    private static String repair(String xml, ProblemLog log, int chunk) throws IOException {
        try (RepairingReader reader = new RepairingReader(new StringReader(xml), log)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[chunk];
            int n;
            while ((n = reader.read(buffer, 0, chunk)) >= 0) {
                out.append(buffer, 0, n);
            }
            assertThat(reader.atEof()).isTrue();
            assertThat(reader.delivered()).isEqualTo(out.length());
            return out.toString();
        }
    }

    private static final String CLEAN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<rss version=\"2.0\" xmlns:wp=\"http://wordpress.org/export/1.2/\">\n"
            + "<!-- a comment with < and & inside -->\n"
            + "<channel><title>A &amp; B &#038; C &#x41;</title>\n"
            + "<item><content:encoded><![CDATA[<p>x & y</p> ]] > ]]]]><![CDATA[>]]></content:encoded>\n"
            + "<category domain=\"category\" nicename='news'><![CDATA[News]]></category></item>\n"
            + "</channel></rss>\n";

    @Test
    void cleanDocumentsPassThroughUnchanged() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair(CLEAN, log)).isEqualTo(CLEAN);
        assertThat(log.problems()).isEmpty();
    }

    @Test
    void readingOneCharAtATimeGivesTheSameText() throws IOException {
        String messy = CLEAN.replace("A &amp; B", "A & B <3 &nbsp;") + "\u0001";
        assertThat(repair(messy, new ProblemLog(true), 1)).isEqualTo(repair(messy, new ProblemLog(true), 4096));
    }

    @Test
    void bareAmpersandBecomesText() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<t>Tom & Jerry</t>", log)).isEqualTo("<t>Tom &amp; Jerry</t>");
        assertThat(log.problems()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(WxrProblem.Kind.BARE_AMPERSAND);
            assertThat(p.line()).isEqualTo(1);
            assertThat(p.column()).isEqualTo(8);
        });
    }

    @Test
    void wordPressCommentUrlIsRepaired() throws IOException {
        // R48, verified (export): WordPress writes this URL with a raw &
        // (target/wp-source/wxr-harness/out-full.xml:128).
        String in = "<wp:comment_author_url>https://ann.test/?a=1&b=2</wp:comment_author_url>";
        assertThat(repair(in, new ProblemLog(true)))
                .isEqualTo("<wp:comment_author_url>https://ann.test/?a=1&amp;b=2</wp:comment_author_url>");
    }

    @Test
    void undefinedNamedReferencesAreKeptAsText() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<t>a &nbsp; b</t>", log)).isEqualTo("<t>a &amp;nbsp; b</t>");
        assertThat(log.problems()).extracting(WxrProblem::kind).containsExactly(WxrProblem.Kind.UNDEFINED_ENTITY);
    }

    @Test
    void validReferencesAreUntouched() throws IOException {
        String in = "<t>&amp;&lt;&gt;&quot;&apos;&#038;&#x41;&#8217;&#128512;</t>";
        ProblemLog log = new ProblemLog(true);
        assertThat(repair(in, log)).isEqualTo(in);
        assertThat(log.problems()).isEmpty();
    }

    @Test
    void referencesToIllegalCodePointsAreReplaced() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<t>a&#1;b&#xFFFE;c&#x110000;</t>", log)).isEqualTo("<t>a&#xFFFD;b&#xFFFD;c&#xFFFD;</t>");
        assertThat(log.problems()).hasSize(3).allMatch(p -> p.kind() == WxrProblem.Kind.ILLEGAL_CHARACTER);
    }

    @Test
    void overlongReferencesAreBareAmpersands() throws IOException {
        String name = "a".repeat(40);
        assertThat(repair("<t>&#123456789;&" + name + ";&#X41;</t>", new ProblemLog(true)))
                .isEqualTo("<t>&amp;#123456789;&amp;" + name + ";&amp;#X41;</t>");
    }

    @Test
    void controlCharactersInsideCdataAreReplaced() throws IOException {
        // R22, verified (export): WordPress writes a\x01b\x0Bc\x00d raw inside CDATA (out-quirks.xml:54).
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<c><![CDATA[a\u0001b\u000Bc\u0000d]]></c>", log))
                .isEqualTo("<c><![CDATA[a\uFFFDb\uFFFDc\uFFFDd]]></c>");
        assertThat(log.problems()).hasSize(3);
    }

    @Test
    void markupCharactersInsideCdataCommentsAndInstructionsAreKept() throws IOException {
        String in = "<?pi a & b < c?><c><![CDATA[x & y < z]]></c><!-- a & b < c -->";
        ProblemLog log = new ProblemLog(true);
        assertThat(repair(in, log)).isEqualTo(in);
        assertThat(log.problems()).isEmpty();
    }

    @Test
    void attributeValuesAreRepaired() throws IOException {
        // R44: WordPress writes category attributes without escaping.
        assertThat(repair("<category domain=\"a&b\" nicename='x<y'>N</category>", new ProblemLog(true)))
                .isEqualTo("<category domain=\"a&amp;b\" nicename='x&lt;y'>N</category>");
    }

    @Test
    void bareLessThanBecomesText() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<t>a < b</t><t>1<2</t><t><<</t>", log))
                .isEqualTo("<t>a &lt; b</t><t>1&lt;2</t><t>&lt;&lt;</t>");
        assertThat(log.problems()).hasSize(4).allMatch(p -> p.kind() == WxrProblem.Kind.BARE_LESS_THAN);
    }

    @Test
    void doctypeIsSkippedInLenientMode() throws IOException {
        ProblemLog log = new ProblemLog(true);
        String in = "<?xml version=\"1.0\"?>\n<!DOCTYPE rss [\n<!ENTITY x \"y>z\">\n]>\n<rss/>";
        assertThat(repair(in, log)).isEqualTo("<?xml version=\"1.0\"?>\n\n<rss/>");
        assertThat(log.problems()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(WxrProblem.Kind.DOCTYPE);
            assertThat(p.line()).isEqualTo(2);
            assertThat(p.column()).isEqualTo(1);
        });
    }

    @Test
    void strictModeRefusesRepairs() {
        ProblemLog doctype = new ProblemLog(false);
        assertThatThrownBy(() -> repair("<!DOCTYPE rss><rss/>", doctype))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.DOCTYPE));
        assertThat(doctype.failure()).isNotNull();
        assertThatThrownBy(() -> repair("<t>Tom & Jerry</t>", new ProblemLog(false)))
                .isInstanceOfSatisfying(WxrException.class, e -> {
                    assertThat(e.kind()).isEqualTo(WxrProblem.Kind.BARE_AMPERSAND);
                    assertThat(e.line()).isEqualTo(1);
                    assertThat(e.column()).isEqualTo(8);
                });
    }

    @Test
    void surrogatesArePairedOrReplaced() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<t>\uD83D\uDE00 a\uD800b\uDC00</t>", log)).isEqualTo("<t>\uD83D\uDE00 a\uFFFDb\uFFFD</t>");
        assertThat(log.problems()).hasSize(2);
    }

    @Test
    void constructsCutOffByTheEndOfInputAreLeftForTheParser() throws IOException {
        assertThat(repair("<c><![CDATA[abc", new ProblemLog(true))).isEqualTo("<c><![CDATA[abc");
        assertThat(repair("<t>a &", new ProblemLog(true))).isEqualTo("<t>a &amp;");
        assertThat(repair("<!-- open", new ProblemLog(true))).isEqualTo("<!-- open");
    }

    // ---- undeclared WordPress prefixes ----

    private static String bindingPrefixes(String xml) throws IOException {
        try (RepairingReader reader = new RepairingReader(
                new StringReader(xml), new ProblemLog(true), WxrReadOptions.DEFAULT_MAX_CHARS_PER_VALUE, true)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[3];
            int n;
            while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
                out.append(buffer, 0, n);
            }
            assertThat(reader.startTagSeen()).isTrue();
            return out.toString();
        }
    }

    @Test
    void undeclaredWordPressPrefixesAreBoundOnTheRootOnly() throws IOException {
        String u = WxrNames.UNDECLARED;
        assertThat(bindingPrefixes("<?xml version=\"1.0\"?><!-- c --><rss version=\"2.0\"\n"
                        + " xmlns:content = 'x' xmlns:wp=\"y\"><channel a=\"1\"><wp:x/></channel></rss>"))
                .isEqualTo("<?xml version=\"1.0\"?><!-- c --><rss version=\"2.0\"\n xmlns:content = 'x' xmlns:wp=\"y\""
                        + " xmlns:excerpt=\"" + u + "excerpt\" xmlns:dc=\"" + u + "dc\" xmlns:wfw=\"" + u + "wfw\">"
                        + "<channel a=\"1\"><wp:x/></channel></rss>");
        // names that only start like a declaration, and a root that closes itself
        assertThat(bindingPrefixes("<r xmlns:wpx=\"a\" xmlns:dcx=\"b\">"))
                .isEqualTo("<r xmlns:wpx=\"a\" xmlns:dcx=\"b\" xmlns:wp=\"" + u + "wp\" xmlns:excerpt=\"" + u
                        + "excerpt\" xmlns:content=\"" + u + "content\" xmlns:dc=\"" + u + "dc\" xmlns:wfw=\"" + u
                        + "wfw\">");
        assertThat(bindingPrefixes("<r a=\"1\"/>")).isEqualTo("<r a=\"1\"/>");
    }

    @Test
    void aRootAttributeNameCutAtTheLimitDeclaresWhatIsLeftOfIt() throws IOException {
        ProblemLog log = new ProblemLog(true);
        try (RepairingReader reader =
                new RepairingReader(new StringReader("<r xmlns:wpzzzz=\"a\"><x/></r>"), log, 8, true)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[3];
            int n;
            while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
                out.append(buffer, 0, n);
            }
            String u = WxrNames.UNDECLARED;
            assertThat(out.toString())
                    .isEqualTo("<r xmlns:wp=\"a\" xmlns:excerpt=\"" + u + "excerpt\" xmlns:content=\"" + u
                            + "content\" xmlns:dc=\"" + u + "dc\" xmlns:wfw=\"" + u + "wfw\"><x/></r>");
            assertThat(reader.boundPrefixes()).isEqualTo(0b11110);
        }
        assertThat(log.problems()).extracting(WxrProblem::kind).containsExactly(WxrProblem.Kind.VALUE_TOO_LARGE);
    }

    // ---- per-value limits on comments, instructions and attributes ----

    private static String repairLimited(String xml, ProblemLog log, int max) throws IOException {
        try (RepairingReader reader = new RepairingReader(new StringReader(xml), log, max)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[3];
            int n;
            while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
                out.append(buffer, 0, n);
            }
            return out.toString();
        }
    }

    @Test
    void longCommentsAndInstructionsAreCutOffInLenientMode() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repairLimited("<r><!--abcdefghij--><?pi 0123456789?>text</r>", log, 4))
                .isEqualTo("<r><!--abcd --><?pi 0?>text</r>");
        assertThat(log.problems()).hasSize(2).allMatch(p -> p.kind() == WxrProblem.Kind.VALUE_TOO_LARGE);
    }

    @Test
    void longAttributeNamesAndValuesAreCutOffInLenientMode() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repairLimited("<r abcdefgh=\"12345678&amp;9\" b='&#65;&#66;' c=\"x\"/>", log, 4))
                .isEqualTo("<r abcd=\"1234\" b='' c=\"x\"/>");
        assertThat(log.problems()).hasSize(3).allMatch(p -> p.kind() == WxrProblem.Kind.VALUE_TOO_LARGE);
        // a reference is kept whole or not at all
        assertThat(repairLimited("<r b='&#65;&#66;&#67;'/>", new ProblemLog(true), 10))
                .isEqualTo("<r b='&#65;&#66;'/>");
    }

    @Test
    void valuesExactlyAtTheLimitAreKept() throws IOException {
        ProblemLog log = new ProblemLog(true);
        String in = "<r abcd=\"1234\"><!--wxyz--><?pi a?></r>";
        assertThat(repairLimited(in, log, 4)).isEqualTo(in);
        assertThat(log.problems()).isEmpty();
    }

    @Test
    void longCommentsAreRefusedInStrictMode() {
        for (String xml : new String[] {"<!--abcde-->", "<?pi abcde?>", "<r a=\"abcde\"/>", "<r abcdef=\"\"/>"}) {
            assertThatThrownBy(() -> repairLimited(xml, new ProblemLog(false), 4))
                    .isInstanceOfSatisfying(
                            WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE));
        }
    }

    @Test
    void cutOffValuesDoNotLogRepairsForTheirDroppedPart() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repairLimited("<r a=\"ab&&\u0001<<<\"/><!--ab&&\u0001<<<-->", log, 2))
                .isEqualTo("<r a=\"ab\"/><!--ab -->");
        assertThat(log.problems()).hasSize(2).allMatch(p -> p.kind() == WxrProblem.Kind.VALUE_TOO_LARGE);
    }

    @Test
    void textAndCdataAreNotCutByTheFilter() throws IOException {
        ProblemLog log = new ProblemLog(true);
        String in = "<r>0123456789<![CDATA[0123456789]]></r>";
        assertThat(repairLimited(in, log, 4)).isEqualTo(in);
        assertThat(log.problems()).isEmpty();
    }

    // ---- reading ----

    @Test
    void readFillsTheCallersBufferUntilTheEnd() throws IOException {
        String in = "<t>a &amp; b &nbsp; c < d</t>";
        // a source that hands out one char at a time must not make read return short
        Reader trickle = new Reader() {
            private final StringReader inner = new StringReader(in);

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                return inner.read(buffer, offset, Math.min(1, length));
            }

            @Override
            public void close() {}
        };
        try (RepairingReader reader = new RepairingReader(trickle, new ProblemLog(true))) {
            char[] buffer = new char[10];
            int total = 0;
            int n;
            while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
                total += n;
                if (reader.atEof()) {
                    assertThat(n).isGreaterThan(0);
                } else {
                    assertThat(n).isEqualTo(buffer.length);
                }
            }
            assertThat(total).isEqualTo("<t>a &amp; b &amp;nbsp; c &lt; d</t>".length());
        }
    }

    // ---- hostile input: linear time ----

    private static String drain(String xml, ProblemLog log) throws IOException {
        try (RepairingReader reader = new RepairingReader(new StringReader(xml), log)) {
            StringBuilder out = new StringBuilder(xml.length() * 2);
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
                out.append(buffer, 0, n);
            }
            return out.toString();
        }
    }

    @Test
    void fourMillionBareLessThansAreRepairedInLinearTime() throws IOException {
        int count = 4_000_000;
        ProblemLog log = new ProblemLog(true);
        Instant start = Instant.now();
        String out = drain("<".repeat(count), log);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(30));
        assertThat(out).hasSize(count * 4).isEqualTo("&lt;".repeat(count));
        assertThat(log.count()).isEqualTo(count);
        assertThat(log.problems()).hasSize(ProblemLog.MAX_STORED + 1);
    }

    @Test
    void fourMillionBareAmpersandsAreRepairedInLinearTime() throws IOException {
        int count = 4_000_000;
        ProblemLog log = new ProblemLog(true);
        Instant start = Instant.now();
        String out = drain("<t>" + "&".repeat(count) + "</t>", log);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(30));
        assertThat(out).hasSize(count * 5 + 7).isEqualTo("<t>" + "&amp;".repeat(count) + "</t>");
        assertThat(log.count()).isEqualTo(count);
    }

    // ---- generated input ----

    private static final Gen<String> FRAGMENTS = Gens.elements(
            "<",
            ">",
            "&",
            ";",
            "#",
            "x",
            "&#",
            "&#x",
            "&amp;",
            "&nbsp;",
            "&#65;",
            "&#1;",
            "<!--",
            "-->",
            "<![CDATA[",
            "]]>",
            "<?",
            "?>",
            "<!DOCTYPE a [",
            "]>",
            "<!DOCTYPE",
            "\"",
            "'",
            "=",
            "/",
            " ",
            "\n",
            "a",
            "<a",
            "</a>",
            "<a b=\"",
            "<a b='",
            "\u0001",
            "\uFFFE",
            "\uD800",
            "\uDC00",
            "\uD83D\uDE00",
            "\u00E9",
            "[",
            "]",
            "!");

    private static final Gen<String> DOCUMENTS = Gens.lists(FRAGMENTS, 0, 40).map(parts -> String.join("", parts));

    @Test
    void repairedOutputIsLegalStableAndIndependentOfTheReadSize() {
        Properties.check(
                "RepairingReaderTest.repairedOutputIsLegalStableAndIndependentOfTheReadSize", 3000, DOCUMENTS, xml -> {
                    try {
                        ProblemLog first = new ProblemLog(true);
                        String once = repair(xml, first, 4096);
                        assertThat(XmlChars.isLegal(once)).isTrue();
                        assertThat(repair(xml, new ProblemLog(true), 1)).isEqualTo(once);
                        assertThat(repair(xml, new ProblemLog(true), 7)).isEqualTo(once);
                        // repairing a repaired text changes nothing and finds nothing
                        ProblemLog second = new ProblemLog(true);
                        assertThat(repair(once, second, 4096)).isEqualTo(once);
                        assertThat(second.problems()).isEmpty();
                        // a text without problems comes out as it went in
                        if (first.problems().isEmpty()) {
                            assertThat(once).isEqualTo(xml);
                        }
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                });
    }

    @Test
    void xmlCharsDefinesLegality() {
        assertThat(XmlChars.isLegal('\t')).isTrue();
        assertThat(XmlChars.isLegal('\u0000')).isFalse();
        assertThat(XmlChars.isLegal('\uFFFE')).isFalse();
        assertThat(XmlChars.isLegal('\uD800')).isFalse();
        assertThat(XmlChars.isLegal(0x10FFFF)).isTrue();
        assertThat(XmlChars.isLegal(0x110000)).isFalse();
        assertThat(XmlChars.isLegal("a\uD83D\uDE00")).isTrue();
        assertThat(XmlChars.isLegal("a\uD83D")).isFalse();
        assertThat(XmlChars.isNameStart('a')).isTrue();
        assertThat(XmlChars.isNameStart('1')).isFalse();
        assertThat(XmlChars.isNameStart(0xD7)).isFalse();
    }

    // ---- DOCTYPE internal subsets ----

    @Test
    void commentsAndInstructionsInTheInternalSubsetAreSkippedWhole() throws IOException {
        assertThat(repair(
                        "<?xml version=\"1.0\"?><!DOCTYPE rss [<!-- it's a test -->]><rss>t</rss>",
                        new ProblemLog(true)))
                .isEqualTo("<?xml version=\"1.0\"?><rss>t</rss>");
        assertThat(repair("<!DOCTYPE r [<!-- ] --><!ENTITY x \"y\">]><r/>", new ProblemLog(true)))
                .isEqualTo("<r/>");
        assertThat(repair("<!DOCTYPE r [<?pi ] \" ?><!ENTITY x \"y\">]><r/>", new ProblemLog(true)))
                .isEqualTo("<r/>");
    }

    // ---- lexical repairs ----

    private static String lenient(String xml) throws IOException {
        return repair(xml, new ProblemLog(true));
    }

    @Test
    void closingBracketsInTextAreEscaped() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<t>a]]>b ]]]>c ]> ]]<!DOCTYPE x>></t>", log))
                .isEqualTo("<t>a]]&gt;b ]]]&gt;c ]> ]]&gt;</t>");
        assertThat(log.problems())
                .extracting(WxrProblem::kind)
                .containsExactly(
                        WxrProblem.Kind.INVALID_MARKUP,
                        WxrProblem.Kind.INVALID_MARKUP,
                        WxrProblem.Kind.DOCTYPE,
                        WxrProblem.Kind.INVALID_MARKUP);
        assertThat(lenient("<c><![CDATA[a]]>]]></c>")).isEqualTo("<c><![CDATA[a]]>]]&gt;</c>");
    }

    @Test
    void doubleDashesInCommentsAreSplit() throws IOException {
        assertThat(lenient("<!--a--b---->")).isEqualTo("<!--a- -b- - -->");
        assertThat(lenient("<!--a--->")).isEqualTo("<!--a- -->");
        assertThat(lenient("<!---->")).isEqualTo("<!---->");
    }

    @Test
    void instructionsAndTagsWithoutNamesAreText() throws IOException {
        assertThat(lenient("<t><? x ?></t>")).isEqualTo("<t>&lt;? x ?></t>");
        assertThat(lenient("<t><?xml version=\"1.0\"?></t>")).isEqualTo("<t>&lt;?xml version=\"1.0\"?></t>");
        assertThat(lenient("<t><?XML a?><?xml-stylesheet a?></t>"))
                .isEqualTo("<t>&lt;?XML a?><?xml-stylesheet a?></t>");
        assertThat(lenient("<t><! x></t><t></ x></t><t><!x></t>"))
                .isEqualTo("<t>&lt;! x></t><t>&lt;/ x></t><t>&lt;!x></t>");
        assertThat(lenient("<t>a <\uD83D\uDE00 b <\u0132</t>")).isEqualTo("<t>a &lt;\uD83D\uDE00 b &lt;\u0132</t>");
    }

    @Test
    void theXmlDeclarationIsNotHeldToTheValueLimit() throws IOException {
        String in = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><r/>";
        ProblemLog log = new ProblemLog(true);
        assertThat(repairLimited(in, log, 4)).isEqualTo(in);
        assertThat(log.problems()).isEmpty();
    }

    @Test
    void anOverlongXmlDeclarationIsRefusedOrDropped() throws IOException {
        String declaration = "<?xml version='1." + "0".repeat(2000) + "'?>";
        assertThatThrownBy(() -> repairLimited(declaration + "<r/>", new ProblemLog(false), 4))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE));
        ProblemLog log = new ProblemLog(true);
        assertThat(repairLimited(declaration + "<r/>", log, 4)).isEqualTo("<r/>");
        assertThat(log.problems())
                .singleElement()
                .satisfies(p -> assertThat(p.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE));
        // a limit above the minimum applies as it is
        assertThat(repairLimited(declaration + "<r/>", new ProblemLog(true), 5000))
                .isEqualTo(declaration + "<r/>");
    }

    @Test
    void commentRepairsAreCountedAsTheCharsTheyBecome() throws IOException {
        // "a--b" becomes "a- -b": five chars, so a limit of four cuts it
        ProblemLog log = new ProblemLog(true);
        assertThat(repairLimited("<!--a--b-->", log, 4)).isEqualTo("<!--a- - -->");
        assertThat(log.problems()).extracting(WxrProblem::kind).contains(WxrProblem.Kind.VALUE_TOO_LARGE);
    }

    @Test
    void doctypeIsRecognisedInAnyCase() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(repair("<!doctype rss><!DocType x [<!ENTITY a \"b\">]><rss/>", log))
                .isEqualTo("<rss/>");
        assertThat(log.problems()).hasSize(2).allMatch(p -> p.kind() == WxrProblem.Kind.DOCTYPE);
        assertStrict("<!doctype rss><rss/>", WxrProblem.Kind.DOCTYPE);
    }

    @Test
    void leadingZerosOfNumericReferencesDoNotCount() throws IOException {
        String in = "<t>&#x000000041;&#0000000065;</t>";
        ProblemLog log = new ProblemLog(true);
        assertThat(repair(in, log)).isEqualTo(in);
        assertThat(log.problems()).isEmpty();
        assertThat(lenient("<t>&#" + "0".repeat(40) + "65;</t>")).startsWith("<t>&amp;#000");
        assertThat(lenient("<t>&#12ab;&#x1G;</t>")).isEqualTo("<t>&amp;#12ab;&amp;#x1G;</t>");
    }

    @Test
    void strictModeRefusesTheOtherRepairsToo() {
        assertStrict("<t>a\u0001</t>", WxrProblem.Kind.ILLEGAL_CHARACTER);
        assertStrict("<t>&#1;</t>", WxrProblem.Kind.ILLEGAL_CHARACTER);
        assertStrict("<t>&nbsp;</t>", WxrProblem.Kind.UNDEFINED_ENTITY);
        assertStrict("<t>a < b</t>", WxrProblem.Kind.BARE_LESS_THAN);
        assertStrict("<t a=\"x<y\"/>", WxrProblem.Kind.BARE_LESS_THAN);
        assertStrict("<t>]]></t>", WxrProblem.Kind.INVALID_MARKUP);
        assertStrict("<!--a--b-->", WxrProblem.Kind.INVALID_MARKUP);
    }

    private static void assertStrict(String xml, WxrProblem.Kind kind) {
        assertThatThrownBy(() -> repair(xml, new ProblemLog(false)))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void charsAlreadyCopiedAreReturnedBeforeAFailure() throws IOException {
        ProblemLog log = new ProblemLog(false);
        try (RepairingReader reader = new RepairingReader(new StringReader("<t>ab</t><!DOCTYPE x>"), log)) {
            char[] buffer = new char[100];
            int n = reader.read(buffer, 0, buffer.length);
            assertThat(new String(buffer, 0, n)).isEqualTo("<t>ab</t>");
            assertThat(reader.delivered()).isEqualTo(9);
            assertThatThrownBy(() -> reader.read(buffer, 0, buffer.length)).isInstanceOf(WxrException.class);
            assertThat(reader.delivered()).isEqualTo(9);
        }
    }

    // ---- the parser accepts the output ----

    /** The text with everything but printable ASCII written as an escape, so a failure message survives. */
    private static String printable(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.toString();
    }

    private static void assertStaxAccepts(String repaired) {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader parser = factory.createXMLStreamReader(new StringReader(repaired));
            while (parser.hasNext()) {
                assertThat(parser.next()).isNotEqualTo(XMLStreamConstants.DTD);
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new AssertionError("StAX refused: " + printable(repaired) + " because " + e.getMessage(), e);
        }
    }

    @Test
    void theParserAcceptsRepairedDoctypeDocuments() throws IOException {
        for (String xml : new String[] {
            "<?xml version=\"1.0\"?><!DOCTYPE rss [<!-- it's a test -->]><rss>t</rss>",
            "<!DOCTYPE r [<!-- ] --><!ENTITY x \"y\">]><r/>",
            "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>",
        }) {
            assertStaxAccepts(lenient(xml).replace("&x;", "&amp;x;"));
        }
    }

    private static final Gen<String> JUNK = Gens.lists(
                    Gens.elements(
                            "<",
                            ">",
                            "&",
                            "]]>",
                            "]",
                            "-",
                            "--",
                            "?",
                            "!",
                            "/",
                            " ",
                            "<?",
                            "<?xml ",
                            "</",
                            "<!",
                            "&nbsp;",
                            "&#65;",
                            "&#1;",
                            "\u0001",
                            "\uFFFE",
                            "\uD800",
                            "\uD83D\uDE00",
                            "\u0132",
                            "\u00D7"),
                    0,
                    12)
            .map(parts -> String.join("", parts))
            .filter(junk -> !junk.contains("<!-"));

    @Test
    void theParserAcceptsRepairedJunkInsideBalancedDocuments() {
        Gen<Junk> parts = random -> new Junk(JUNK.next(random), JUNK.next(random), JUNK.next(random));
        Properties.check("RepairingReaderTest.theParserAcceptsRepairedJunkInsideBalancedDocuments", 4000, parts, p -> {
            String junkInAttribute = p.attribute().replace("\"", "");
            String xml = "<r>" + p.text() + "<c a=\"" + junkInAttribute + "\">" + p.content() + "</c><!--"
                    + p.text().replace('>', ' ') + "--></r>";
            try {
                assertStaxAccepts(repair(xml, new ProblemLog(true), 4096));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** Junk for a text, an attribute value and an element, printed readably when a property fails. */
    private record Junk(String text, String attribute, String content) {
        @Override
        public String toString() {
            return "Junk[" + printable(text) + ", " + printable(attribute) + ", " + printable(content) + "]";
        }
    }

    @Test
    void nameStartAgreesWithTheParser() {
        for (int c = 0; c < 0x30000; c++) {
            if (c >= 0xD800 && c <= 0xDFFF) {
                continue;
            }
            if (XmlChars.isNameStart(c)) {
                String name = new String(Character.toChars(c));
                assertStaxAccepts("<" + name + "/>");
            }
        }
        assertThat(XmlChars.isNameStart(0x132)).isFalse();
        assertThat(XmlChars.isNameStart(0x1F600)).isFalse();
        assertThat(XmlChars.isNameStart('x')).isTrue();
    }
}
