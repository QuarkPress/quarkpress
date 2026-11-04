package org.quarkpress.it.restwp;

import com.fasterxml.jackson.databind.node.IntNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostTypes;

/**
 * The registered meta of {@link ItBookType} in the REST parity comparison: {@code book_rating} (an integer, one
 * value, default 3) and {@code book_tags} (strings, several values), both shown in REST. The WordPress side registers
 * the same keys, after its footnotes, through its must-use plugin.
 */
@ApplicationScoped
public class ItBookMeta implements MetaKeyProvider {

    @Override
    public List<MetaKeyDefinition> metaKeys(PostTypes types) {
        return List.of(
                MetaKeyDefinition.builder("book_rating")
                        .subtype("book")
                        .type("integer")
                        .defaultValue(IntNode.valueOf(3))
                        .single()
                        .showInRest()
                        .build(),
                MetaKeyDefinition.builder("book_tags")
                        .subtype("book")
                        .type("string")
                        .showInRest()
                        .build());
    }
}
