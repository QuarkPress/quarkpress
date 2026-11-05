package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/** The upload's metadata reads: a parser library's error never escapes them. */
class UploadPipelineTest {

    @Test
    void aParserErrorLeavesWordPresssDefaultImageMeta() {
        for (Error error : new Error[] {new StackOverflowError(), new OutOfMemoryError()}) {
            ObjectNode meta = UploadPipeline.readImageMeta(() -> {
                throw error;
            });
            assertThat(meta.fieldNames())
                    .toIterable()
                    .containsExactly(
                            "aperture",
                            "credit",
                            "camera",
                            "caption",
                            "created_timestamp",
                            "copyright",
                            "focal_length",
                            "iso",
                            "shutter_speed",
                            "title",
                            "orientation",
                            "keywords",
                            "alt");
            assertThat(meta.get("aperture").textValue()).isEqualTo("0");
            assertThat(meta.get("title").textValue()).isEmpty();
            assertThat(meta.get("keywords").isArray()).isTrue();
            assertThat(meta.get("keywords")).isEmpty();
        }
    }

    @Test
    void whatTheReaderReturnsIsKeptAndReadFailuresPropagate() {
        ObjectNode read = JsonNodeFactory.instance.objectNode().put("title", "T");
        assertThat(UploadPipeline.readImageMeta(() -> read)).isSameAs(read);
        assertThatThrownBy(() -> UploadPipeline.readImageMeta(() -> {
                    throw new IOException("unreadable");
                }))
                .isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> UploadPipeline.readImageMeta(() -> {
                    throw new LinkageError("not a parser failure");
                }))
                .isInstanceOf(LinkageError.class);
    }
}
