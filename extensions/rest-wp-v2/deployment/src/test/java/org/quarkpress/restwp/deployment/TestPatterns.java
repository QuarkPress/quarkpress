package org.quarkpress.restwp.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.quarkpress.blocks.BlockPattern;
import org.quarkpress.blocks.BlockPatternCategory;
import org.quarkpress.blocks.BlockPatternCategoryProvider;
import org.quarkpress.blocks.BlockPatternProvider;

/** The block patterns and the pattern category of the block patterns tests. */
final class TestPatterns {

    private TestPatterns() {}

    /**
     * The patterns WordPress's must-use plugin registers with its {@code qp_parity_patterns} switch: {@code qp/inner}
     * (categories under old and new names), {@code qp/outer} (referring to {@code qp/inner}, an unknown pattern and
     * itself) and {@code qp/plain} (every other property).
     */
    @ApplicationScoped
    public static class Patterns implements BlockPatternProvider {

        @Override
        public List<BlockPattern> patterns() {
            return List.of(
                    BlockPattern.builder("qp/inner", "Inner", "<!-- wp:paragraph --><p>Inner</p><!-- /wp:paragraph -->")
                            .categories(List.of("buttons", "columns", "query", "x"))
                            .build(),
                    BlockPattern.builder(
                                    "qp/outer",
                                    "Outer",
                                    "<!-- wp:group --><div class=\"wp-block-group\">"
                                            + "<!-- wp:pattern {\"slug\":\"qp/inner\"} /-->"
                                            + "<!-- wp:pattern {\"slug\":\"qp/missing\"} /-->"
                                            + "<!-- wp:pattern {\"slug\":\"qp/outer\"} /-->"
                                            + "</div><!-- /wp:group -->")
                            .build(),
                    BlockPattern.builder("qp/plain", "Plain", "<!-- wp:paragraph --><p>Plain</p><!-- /wp:paragraph -->")
                            .inserter(false)
                            .viewportWidth(500)
                            .description("Plain text.")
                            .keywords(List.of("plain"))
                            .blockTypes(List.of("core/paragraph"))
                            .postTypes(List.of("post"))
                            .build());
        }
    }

    /** A pattern category of the application, after the core ones: a label and no description. */
    @ApplicationScoped
    public static class Categories implements BlockPatternCategoryProvider {

        @Override
        public List<BlockPatternCategory> categories() {
            return List.of(new BlockPatternCategory("acme", Optional.of("Acme"), Optional.empty()));
        }
    }
}
