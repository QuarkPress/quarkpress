package org.quarkpress.blocks.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.blocks.BlockRenderContext;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;

/** A renderer for a block type nobody registered stops the application at start-up. */
class OrphanRendererTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Types.class, Orphan.class, Failures.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: the block renderer for acme/ghost names no registered block type"));

    @ApplicationScoped
    public static class Types implements BlockTypeProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(BlockType.builder("acme/real").build());
        }
    }

    @ApplicationScoped
    public static class Orphan implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/ghost";
        }

        @Override
        public String render(BlockRenderContext context) {
            return "";
        }
    }

    @Test
    void startUpFails() {
        // assertException above checks the failure
    }
}
