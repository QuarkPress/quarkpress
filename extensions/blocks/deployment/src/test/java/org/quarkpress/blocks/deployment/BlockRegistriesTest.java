package org.quarkpress.blocks.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.blocks.BlockPattern;
import org.quarkpress.blocks.BlockPatternCategory;
import org.quarkpress.blocks.BlockPatternCategoryProvider;
import org.quarkpress.blocks.BlockPatternCategoryRegistry;
import org.quarkpress.blocks.BlockPatternProvider;
import org.quarkpress.blocks.BlockPatternRegistry;
import org.quarkpress.blocks.BlockRenderContext;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;
import org.quarkpress.blocks.BlockTypeRegistry;

class BlockRegistriesTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar ->
                    jar.addClasses(Zulu.class, Alpha.class, Mike.class, Kilo.class, Dyn.class, Categories.class));

    /** Registered last by its order, though its class name sorts first. */
    @ApplicationScoped
    public static class Alpha implements BlockTypeProvider, BlockPatternProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(BlockType.builder("zeta/last").build());
        }

        @Override
        public List<BlockPattern> patterns() {
            return List.of(BlockPattern.builder("acme/p1", "P1 again", "<!-- wp:paragraph /-->")
                    .build());
        }

        @Override
        public int order() {
            return 1000;
        }
    }

    /** Registered first by its order, though its class name sorts last. */
    @ApplicationScoped
    public static class Zulu implements BlockTypeProvider, BlockPatternProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(
                    BlockType.builder("acme/first").build(),
                    BlockType.builder("acme/dyn").build());
        }

        @Override
        public List<BlockPattern> patterns() {
            return List.of(
                    BlockPattern.builder("acme/p1", "P1", "").build(),
                    BlockPattern.builder("acme/p2", "P2", "").build());
        }

        @Override
        public int order() {
            return 10;
        }
    }

    /** Equal orders: registered by class name, after Kilo though listed first. */
    @Dependent
    public static class Mike implements BlockTypeProvider {

        static final AtomicInteger DESTROYED = new AtomicInteger();

        @Override
        public List<BlockType> blockTypes() {
            return List.of(BlockType.builder("mike/m").build());
        }

        @Override
        public int order() {
            return 500;
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    @ApplicationScoped
    public static class Kilo implements BlockTypeProvider {

        @Override
        public List<BlockType> blockTypes() {
            return List.of(BlockType.builder("kilo/k").build());
        }

        @Override
        public int order() {
            return 500;
        }
    }

    @ApplicationScoped
    public static class Dyn implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/dyn";
        }

        @Override
        public String render(BlockRenderContext context) {
            return "<p>dyn</p>";
        }
    }

    @ApplicationScoped
    public static class Categories implements BlockPatternCategoryProvider {

        @Override
        public List<BlockPatternCategory> categories() {
            return List.of(
                    new BlockPatternCategory("acme", Optional.of("Acme"), Optional.empty()),
                    new BlockPatternCategory("text", Optional.of("Words"), Optional.empty()));
        }
    }

    @Inject
    BlockTypeRegistry types;

    @Inject
    BlockPatternRegistry patterns;

    @Inject
    BlockPatternCategoryRegistry categories;

    @Test
    void typesInProviderOrder() {
        assertThat(types.all())
                .extracting(BlockType::name)
                .containsExactly("acme/first", "acme/dyn", "kilo/k", "mike/m", "zeta/last");
        assertThat(types.find("acme/dyn")).isPresent();
        assertThat(types.find("Acme/dyn")).isEmpty();
        assertThat(types.find("acme/none")).isEmpty();
    }

    @Test
    void aDependentProviderIsDestroyedOnceRead() {
        assertThat(types.all()).isNotEmpty();
        assertThat(Mike.DESTROYED).hasValue(1);
    }

    @Test
    void aRendererMakesItsTypeDynamic() {
        assertThat(types.isDynamic("acme/dyn")).isTrue();
        assertThat(types.isDynamic("acme/first")).isFalse();
        assertThat(types.isDynamic("acme/none")).isFalse();
        assertThat(types.renderer("acme/dyn"))
                .map(r -> r.render(new BlockRenderContext(
                        "acme/dyn", JsonNodeFactory.instance.objectNode(), OptionalLong.empty())))
                .contains("<p>dyn</p>");
        assertThat(types.renderer("acme/first")).isEmpty();
        assertThat(types.renderer("acme/none")).isEmpty();
    }

    @Test
    void patternsReplaceInPlace() {
        assertThat(patterns.all()).extracting(BlockPattern::name).containsExactly("acme/p1", "acme/p2");
        assertThat(patterns.all()).extracting(BlockPattern::title).containsExactly("P1 again", "P2");
        assertThat(patterns.find("acme/p1")).map(BlockPattern::content).contains("<!-- wp:paragraph /-->");
        assertThat(patterns.find("acme/p3")).isEmpty();
    }

    @Test
    void coreCategoriesComeFirst() {
        List<BlockPatternCategory> all = categories.all();
        assertThat(all)
                .extracting(BlockPatternCategory::name)
                .containsExactly(
                        "banner",
                        "buttons",
                        "columns",
                        "text",
                        "query",
                        "featured",
                        "call-to-action",
                        "team",
                        "testimonials",
                        "services",
                        "contact",
                        "about",
                        "portfolio",
                        "gallery",
                        "media",
                        "videos",
                        "audio",
                        "posts",
                        "footer",
                        "header",
                        "navigation",
                        "acme");
        // registered again: replaced where it stood
        assertThat(all.get(3)).isEqualTo(new BlockPatternCategory("text", Optional.of("Words"), Optional.empty()));
        assertThat(all.get(0))
                .isEqualTo(new BlockPatternCategory(
                        "banner",
                        Optional.of("Banners"),
                        Optional.of("Large sections that open a page or carry one message.")));
        assertThat(all.get(6).label()).contains("Call to action");
        assertThat(all.get(20))
                .isEqualTo(new BlockPatternCategory(
                        "navigation", Optional.of("Navigation"), Optional.of("Menus and site navigation.")));
    }
}
