package org.quarkpress.security;

import java.util.Map;

/**
 * Meta capability names of post types that map meta capabilities, such as {@code edit_book}, with the core name they
 * stand for ({@code edit_post}, {@code read_post} or {@code delete_post}). A check of such a name maps as the core
 * name with the same object. The posts module contributes one bean covering every registered type.
 */
public interface MetaCapabilityNames {

    Map<String, String> metaCapabilities();
}
