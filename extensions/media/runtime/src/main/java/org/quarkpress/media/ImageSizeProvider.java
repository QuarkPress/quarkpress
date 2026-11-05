package org.quarkpress.media;

import java.util.List;

/**
 * Registers image sizes, as WordPress's {@code add_image_size} does. Every bean of this type is read once, at
 * application start-up: higher {@code @Priority} first (none counts as 0), equal priorities by class name. Its sizes
 * come after WordPress's built-in ones, in the order given. A name registered twice keeps the place of its first
 * registration and the definition of its last one; a provider may so redefine a built-in size, such as {@code
 * thumbnail}, whose site options are then ignored. A size of 0x0 is not made.
 */
public interface ImageSizeProvider {

    List<ImageSizeDefinition> sizes();
}
