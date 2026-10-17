package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Memory follows one entity, not the file: a stream many times the heap reads in a child JVM with a small heap. */
class StreamingMemoryTest {

    static final int ITEMS = 150_000;
    static final String HEAP = "-Xmx32m";

    /** Generates a WXR document of {@code items} items (about 1 KB each, with meta and a comment) on the fly. */
    static final class GeneratedWxr extends InputStream {

        private final int items;
        private int next = -1;
        private byte[] chunk = new byte[0];
        private int pos;
        private boolean done;
        long bytes;

        GeneratedWxr(int items) {
            this.items = items;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            while (pos == chunk.length) {
                if (done) {
                    return -1;
                }
                chunk = nextChunk().getBytes(StandardCharsets.UTF_8);
                pos = 0;
            }
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            bytes += n;
            return n;
        }

        private String nextChunk() {
            if (next < 0) {
                next = 0;
                return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" " + TestWxr.NAMESPACES
                        + ">\n<channel>\n<wp:wxr_version>1.2</wp:wxr_version>\n";
            }
            if (next == items) {
                done = true;
                return "</channel>\n</rss>\n";
            }
            int i = next++;
            return "<item><title>item-" + i + "</title><wp:post_id>" + i + "</wp:post_id>"
                    + "<content:encoded><![CDATA[" + "lorem ipsum ".repeat(60) + "]]></content:encoded>"
                    + "<wp:postmeta><wp:meta_key>k</wp:meta_key><wp:meta_value>" + i + "</wp:meta_value></wp:postmeta>"
                    + "<wp:comment><wp:comment_id>" + i + "</wp:comment_id><wp:comment_content>c &amp; "
                    + i + "</wp:comment_content></wp:comment></item>\n";
        }
    }

    /** Child JVM entry point: reads the generated stream and prints "items bytes" when every item checks out. */
    public static void main(String[] args) {
        int items = Integer.parseInt(args[0]);
        GeneratedWxr in = new GeneratedWxr(items);
        int seen = 0;
        try (WxrReader reader = WxrReader.open(in, WxrReadOptions.STRICT)) {
            while (reader.hasNext()) {
                WxrItem item = (WxrItem) reader.next();
                if (!("item-" + seen).equals(item.title())
                        || item.postId() != seen
                        || !String.valueOf(seen).equals(item.postMeta().get(0).value())
                        || !("c & " + seen).equals(item.comments().get(0).content())) {
                    throw new AssertionError("item " + seen + " reads wrong: " + item);
                }
                seen++;
            }
            if (!reader.problems().isEmpty()) {
                throw new AssertionError("problems: " + reader.problems());
            }
        }
        System.out.println(seen + " " + in.bytes);
    }

    @Test
    void aStreamManyTimesTheHeapReads() throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(List.of(
                        java.toString(),
                        HEAP,
                        "-XX:TieredStopAtLevel=1",
                        "-XX:ActiveProcessorCount=2",
                        "-cp",
                        System.getProperty("java.class.path"),
                        StreamingMemoryTest.class.getName(),
                        String.valueOf(ITEMS)))
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream out = child.getInputStream()) {
            output = new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertThat(child.waitFor(5, TimeUnit.MINUTES)).isTrue();
        assertThat(child.exitValue()).as(output).isZero();
        String[] parts = output.substring(output.lastIndexOf('\n') + 1).trim().split(" ");
        assertThat(Integer.parseInt(parts[0])).isEqualTo(ITEMS);
        assertThat(Long.parseLong(parts[1]))
                .as("input bytes, at least 4x the heap")
                .isGreaterThan(128L << 20);
    }
}
