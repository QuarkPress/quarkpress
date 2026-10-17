package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * The tables shipped in wordpress-tables.txt, the entity names and the context resolution, compared with live
 * WordPress (-Pwp-parity). A difference after a WordPress bump means: regenerate the resource with
 * src/test/php/export-tables.php and review the diff.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WpKsesTablesParityTest {

    @Test
    void tablesEqualWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), KsesOracle.WORDPRESS_FILES);
        ObjectNode in = OracleJson.MAPPER.createObjectNode().put("fn", "tables").put("mode", "wp");
        JsonNode result = PhpOracle.create(wordpress)
                .run(KsesOracle.DRIVER, List.of(new PhpOracle.Case("tables", in)))
                .get("tables");
        JsonNode wordpressTables = result.get("out");
        ObjectNode java = TablesJson.javaTables();
        assertThat(wordpressTables.properties()).hasSize(java.size());
        wordpressTables
                .properties()
                .forEach(e -> assertThat(java.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue()));
    }
}
