package org.quarkpress.it.restwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;

/**
 * The block types of the REST parity comparison's block supports probes: {@code acme/notice} (a title only),
 * {@code acme/supports} (most supports), {@code acme/late} (a title only) and the {@code qp-probe/*} types, each with
 * one support or two, titled "Probe &lt;name&gt;". The WordPress side of the comparison registers the same types when
 * a scenario turns on its must-use plugin's {@code qp_parity_block_types} switch through an option setup step (there
 * {@code acme/late} is registered after WordPress sets up its block supports, so it lacks their attributes).
 */
@ApplicationScoped
public class ItBlockTypes implements BlockTypeProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The probes' supports, by name, as the must-use plugin registers them. */
    private static final Map<String, String> PROBES = probes();

    private static Map<String, String> probes() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("anchor", "{\"anchor\":true}");
        probes.put("border", "{\"__experimentalBorder\":true}");
        probes.put("layout", "{\"__experimentalLayout\":true}");
        probes.put("spacing", "{\"customCSS\":false,\"spacing\":{\"padding\":true}}");
        probes.put("query", "{\"align\":[\"wide\",\"full\"],\"layout\":true,\"anchor\":true}");
        probes.put("line-height", "{\"typography\":{\"lineHeight\":true},\"anchor\":true}");
        probes.put("fit-text", "{\"typography\":{\"fitText\":true},\"anchor\":true}");
        probes.put("text-indent", "{\"typography\":{\"textIndent\":true},\"anchor\":true}");
        probes.put("color-true", "{\"color\":true,\"anchor\":true}");
        probes.put("link-only", "{\"color\":{\"text\":false,\"background\":false,\"link\":true}}");
        return probes;
    }

    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<BlockType> blockTypes() {
        List<BlockType> types = new ArrayList<>();
        types.add(BlockType.builder("acme/notice").title("Notice").build());
        types.add(BlockType.builder("acme/supports")
                .title("Supports")
                .supports(object("{\"align\":true,\"anchor\":true,\"ariaLabel\":true,"
                        + "\"color\":{\"background\":true,\"text\":true,\"gradients\":true,\"link\":true},"
                        + "\"typography\":{\"fontSize\":true,\"lineHeight\":true},"
                        + "\"border\":{\"color\":true,\"radius\":true,\"style\":true,\"width\":true},"
                        + "\"spacing\":{\"margin\":true,\"padding\":true},\"dimensions\":{\"minHeight\":true},"
                        + "\"shadow\":true,\"background\":{\"backgroundImage\":true},\"layout\":true,"
                        + "\"position\":{\"sticky\":true},\"filter\":{\"duotone\":true}}"))
                .build());
        PROBES.forEach((name, supports) -> types.add(BlockType.builder("qp-probe/" + name)
                .title("Probe " + name)
                .supports(object(supports))
                .build()));
        types.add(BlockType.builder("acme/late").title("Late").build());
        return types;
    }
}
