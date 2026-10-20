package org.quarkpress.posts.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.MetaCapabilityNames;
import org.quarkpress.security.PostTypeCapabilities;

/**
 * The meta capability names of the registered types that map meta capabilities and are not built in (the security
 * module knows the built-in ones): a book type's {@code edit_book}, {@code read_book} and {@code delete_book} map
 * as {@code edit_post}, {@code read_post} and {@code delete_post}.
 */
@ApplicationScoped
public class RegisteredMetaCapabilityNames implements MetaCapabilityNames {

    @Inject
    PostTypes types;

    @Override
    public Map<String, String> metaCapabilities() {
        Map<String, String> names = new LinkedHashMap<>();
        for (PostTypeDefinition type : types.all()) {
            if (type.builtIn() || !type.mapMetaCap()) {
                continue;
            }
            PostTypeCapabilities capabilities = types.capabilitiesOrPost(type.key());
            put(names, capabilities.editPost(), "edit_post");
            put(names, capabilities.readPost(), "read_post");
            put(names, capabilities.deletePost(), "delete_post");
        }
        return names;
    }

    private static void put(Map<String, String> names, String name, String core) {
        if (!name.equals(core)) {
            names.put(name, core);
        }
    }
}
