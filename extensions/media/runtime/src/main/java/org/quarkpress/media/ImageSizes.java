package org.quarkpress.media;

import java.util.Map;

/** The image sizes of the current site. */
public interface ImageSizes {

    /**
     * The current site's sizes by name, in WordPress's order: {@code thumbnail}, {@code medium}, {@code medium_large}
     * and {@code large} from the site's options (unless a provider registered that name), then {@code 1536x1536},
     * {@code 2048x2048} and the providers' sizes. Sizes of 0x0 are left out.
     */
    Map<String, ImageSizeDefinition> registered();
}
