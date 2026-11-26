package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The post-password form is read with a bound: nothing of a body announced over it, at most one byte past it. */
class PostPassFormTest {

    /** A body that fails the test when read. */
    private static final InputStream UNREAD = new InputStream() {
        @Override
        public int read() {
            throw new AssertionError("the body was read");
        }
    };

    /** A body that counts the bytes read from an endless run of {@code a}. */
    private static final class Endless extends InputStream {
        long read;

        @Override
        public int read() {
            read++;
            return 'a';
        }
    }

    private static InputStream bytes(int length) {
        return new ByteArrayInputStream("a".repeat(length).getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void aBodyAnnouncedOverTheBoundIsNotRead() throws Exception {
        assertThat(PostPassEndpoint.readForm(Optional.of("65537"), UNREAD)).isEmpty();
        assertThat(PostPassEndpoint.readForm(Optional.of(" 10485760 "), UNREAD)).isEmpty();
        assertThat(PostPassEndpoint.readForm(Optional.of("99999999999999999999"), UNREAD))
                .isEmpty();
    }

    @Test
    void aBodyWithoutLengthIsReadOneBytePastTheBound() throws Exception {
        Endless endless = new Endless();
        assertThat(PostPassEndpoint.readForm(Optional.empty(), endless)).isEmpty();
        assertThat(endless.read).isEqualTo(PostPassEndpoint.MAX_FORM_BYTES + 1L);
        // a length that is no number is no announcement: the read bound still holds
        Endless unparsed = new Endless();
        assertThat(PostPassEndpoint.readForm(Optional.of("lots"), unparsed)).isEmpty();
        assertThat(unparsed.read).isEqualTo(PostPassEndpoint.MAX_FORM_BYTES + 1L);
    }

    @Test
    void aBodyUpToTheBoundIsRead() throws Exception {
        assertThat(PostPassEndpoint.readForm(Optional.of("65536"), bytes(PostPassEndpoint.MAX_FORM_BYTES)))
                .hasValueSatisfying(form -> assertThat(form).hasSize(PostPassEndpoint.MAX_FORM_BYTES));
        // a body longer than announced is still cut at the bound
        assertThat(PostPassEndpoint.readForm(Optional.of("3"), bytes(PostPassEndpoint.MAX_FORM_BYTES + 1)))
                .isEmpty();
        assertThat(PostPassEndpoint.readForm(Optional.of("3"), bytes(3)))
                .hasValueSatisfying(form -> assertThat(form).hasSize(3));
        assertThat(PostPassEndpoint.readForm(Optional.empty(), null))
                .hasValueSatisfying(form -> assertThat(form).isEmpty());
    }
}
