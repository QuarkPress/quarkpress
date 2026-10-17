package org.quarkpress.lib.wxr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reading helpers for tests. */
final class TestWxr {

    static final String NAMESPACES = "xmlns:excerpt=\"http://wordpress.org/export/1.2/excerpt/\""
            + " xmlns:content=\"http://purl.org/rss/1.0/modules/content/\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:wp=\"http://wordpress.org/export/1.2/\"";

    private TestWxr() {}

    record Result(WxrSite site, List<WxrEntry> entries, List<WxrProblem> problems) {
        List<WxrItem> items() {
            return entries.stream()
                    .filter(WxrItem.class::isInstance)
                    .map(WxrItem.class::cast)
                    .toList();
        }

        List<WxrProblem.Kind> kinds() {
            return problems.stream().map(WxrProblem::kind).toList();
        }
    }

    /** A WXR 1.2 document with the usual namespaces, a version, and {@code channelBody}. */
    static String doc(String channelBody) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" " + NAMESPACES + ">\n<channel>\n"
                + "<wp:wxr_version>1.2</wp:wxr_version>\n" + channelBody + "\n</channel>\n</rss>\n";
    }

    static Result read(String xml, WxrReadOptions options) {
        return read(xml.getBytes(StandardCharsets.UTF_8), options);
    }

    static Result read(byte[] xml, WxrReadOptions options) {
        try (WxrReader reader = WxrReader.open(new ByteArrayInputStream(xml), options)) {
            List<WxrEntry> entries = new ArrayList<>();
            while (reader.hasNext()) {
                entries.add(reader.next());
            }
            return new Result(reader.site(), entries, reader.problems());
        }
    }

    static byte[] resource(String path) {
        try (InputStream in = TestWxr.class.getResourceAsStream("/wxr/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource /wxr/" + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
