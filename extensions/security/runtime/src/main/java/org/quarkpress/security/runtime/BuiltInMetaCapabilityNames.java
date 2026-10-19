package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.quarkpress.security.MetaCapabilityNames;

/** The meta capability names of WordPress's reusable blocks. */
@ApplicationScoped
public class BuiltInMetaCapabilityNames implements MetaCapabilityNames {

    @Override
    public Map<String, String> metaCapabilities() {
        return Map.of("edit_block", "edit_post", "read_block", "read_post", "delete_block", "delete_post");
    }
}
