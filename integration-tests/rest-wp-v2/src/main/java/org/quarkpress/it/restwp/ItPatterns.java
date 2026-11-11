package org.quarkpress.it.restwp;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.quarkpress.blocks.BlockPattern;
import org.quarkpress.blocks.BlockPatternProvider;

/**
 * The block patterns of the REST parity comparison: {@code qp/inner} (categories under old and new names),
 * {@code qp/outer} (referring to {@code qp/inner}, to a pattern nobody registers and to itself) and {@code qp/plain}
 * (every other property). The WordPress side of the comparison registers the same patterns when a scenario turns on its
 * must-use plugin's {@code qp_parity_patterns} switch through an option setup step; QuarkPress registers no core or
 * theme patterns, as WordPress with its {@code qp_parity_no_core_patterns} and {@code qp_parity_quarkpress_theme}
 * switches on.
 */
@ApplicationScoped
public class ItPatterns implements BlockPatternProvider {

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
