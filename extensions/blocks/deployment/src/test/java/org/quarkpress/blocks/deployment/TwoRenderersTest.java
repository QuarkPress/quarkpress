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

/** Two renderers for one registered block type stop the application at start-up. */
class TwoRenderersTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Types.class, R1.class, R2.class, Failures.class))
            .assertException(failure ->
                    assertThat(Failures.messages(failure)).contains("QuarkPress: two block renderers for acme/dyn"));

    @ApplicationScoped
    public static class Types implements BlockTypeProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(BlockType.builder("acme/dyn").build());
        }
    }

    @ApplicationScoped
    public static class R1 implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/dyn";
        }

        @Override
        public String render(BlockRenderContext context) {
            return "1";
        }
    }

    @ApplicationScoped
    public static class R2 extends R1 {

        @Override
        public String render(BlockRenderContext context) {
            return "2";
        }
    }

    @Test
    void startUpFails() {
        // assertException above checks the failure
    }
}
