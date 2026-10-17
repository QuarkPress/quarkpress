package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Properties;

/**
 * Whole generated documents through the writer, then read back by the streaming reader and by a plain JDK StAX
 * parser that shares no code with it. Single values with nasty text are covered by {@code WxrWriterTest}; huge values
 * by its linear-time test.
 */
class RoundTripPropertyTest {

    @Test
    void writtenDocumentsReadBackExactly() {
        Properties.check("writer then strict reader", 300, WxrArbitraries.documents(true), doc -> {
            WxrArbitraries.Document expected = doc.sanitized();
            TestWxr.Result r = TestWxr.read(doc.write(), WxrReadOptions.STRICT);
            assertThat(r.problems()).isEmpty();
            assertThat(r.entries().stream().map(Canonical::strip).toList())
                    .containsExactlyElementsOf(expected.entries());
            assertThat(r.site()).isEqualTo(expected.site());
        });
    }

    @Test
    void writtenDocumentsAreWellFormedForAnIndependentParser() {
        Properties.check("writer then JDK StAX", 300, WxrArbitraries.documents(true), doc -> {
            WxrArbitraries.Document expected = doc.sanitized();
            List<String> expectedTexts = new ArrayList<>();
            for (WxrItem item : expected.items()) {
                add(expectedTexts, item.title());
                add(expectedTexts, item.content());
                add(expectedTexts, item.excerpt());
                item.postMeta().forEach(m -> {
                    add(expectedTexts, m.key());
                    add(expectedTexts, m.value());
                });
                item.comments()
                        .forEach(c -> c.commentMeta().forEach(m -> {
                            add(expectedTexts, m.key());
                            add(expectedTexts, m.value());
                        }));
            }
            Scan scan = scan(doc.write());
            // a carriage return survives (the writer references it), unlike in a raw-CR file
            assertThat(scan.itemTexts).containsExactlyElementsOf(expectedTexts);
            assertThat(scan.items).isEqualTo(expected.items().size());
            assertThat(scan.channelTitle).isEqualTo(expected.site().title());
            assertThat(scan.authors).isEqualTo(expected.authors().size());
        });
    }

    private static void add(List<String> texts, String value) {
        if (value != null) {
            texts.add(value);
        }
    }

    private record Scan(String channelTitle, int items, int authors, List<String> itemTexts) {}

    /** title, content:encoded, excerpt:encoded, meta_key and meta_value of every item, in document order. */
    private static Scan scan(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        try {
            XMLStreamReader in = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            List<String> texts = new ArrayList<>();
            String channelTitle = null;
            int items = 0;
            int authors = 0;
            int depth = 0;
            boolean inItem = false;
            while (in.hasNext()) {
                int event = in.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = in.getLocalName();
                    if (depth == 3 && name.equals("item")) {
                        inItem = true;
                        items++;
                    } else if (depth == 3 && name.equals("author")) {
                        authors++;
                    } else if (depth == 3 && name.equals("title")) {
                        channelTitle = in.getElementText();
                        depth--;
                    } else if (inItem
                            && (name.equals("title")
                                    || name.equals("encoded")
                                    || name.equals("meta_key")
                                    || name.equals("meta_value"))) {
                        texts.add(in.getElementText());
                        depth--;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 3 && in.getLocalName().equals("item")) {
                        inItem = false;
                    }
                    depth--;
                }
            }
            return new Scan(channelTitle, items, authors, texts);
        } catch (XMLStreamException e) {
            throw new AssertionError("not well-formed: " + e.getMessage(), e);
        }
    }
}
