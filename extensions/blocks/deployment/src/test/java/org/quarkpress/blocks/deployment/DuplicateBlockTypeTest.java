package org.quarkpress.blocks.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;

/** A block type registered twice stops the application at start-up. */
class DuplicateBlockTypeTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(One.class, Two.class, Failures.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: the block type acme/twice is registered more than once"));

    @ApplicationScoped
    public static class One implements BlockTypeProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(BlockType.builder("acme/twice").build());
        }
    }

    @ApplicationScoped
    public static class Two implements BlockTypeProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(
                    BlockType.builder("acme/once").build(),
                    BlockType.builder("acme/twice").build());
        }
    }

    @Test
    void startUpFails() {
        // assertException above checks the failure
    }
}
