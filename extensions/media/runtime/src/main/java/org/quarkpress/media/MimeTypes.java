package org.quarkpress.media;

import java.util.List;
import java.util.Map;

/**
 * The types a site may upload: extension patterns such as {@code jpg|jpeg|jpe}, each with its MIME type, in lookup
 * order. The module's default bean holds WordPress's list; an application bean of this type replaces it.
 */
public interface MimeTypes {

    /** The pattern and MIME type of each allowed type, in lookup order. */
    List<Map.Entry<String, String>> patterns();
}
