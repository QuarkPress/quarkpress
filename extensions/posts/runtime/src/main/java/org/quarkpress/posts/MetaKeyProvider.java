package org.quarkpress.posts;

import java.util.List;

/**
 * Registers post meta keys (WordPress's {@code register_post_meta}): CDI beans asked once at startup, after every post
 * type is registered, in bean-class-name order; each bean's definitions keep their order. A key registered twice for
 * one subtype, a key registered both for every type and for a subtype, a key shown in REST as an array without
 * schema items, or a key keeping revisions for a type without revisions support fails the start.
 */
public interface MetaKeyProvider {

    List<MetaKeyDefinition> metaKeys(PostTypes types);
}
