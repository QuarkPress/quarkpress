package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * What the block editor's settings take from the media module, in plain types: a bean only while that module is present,
 * so the editor route asks {@code Instance<EditorMedia>.isResolvable()} first and, without it, tells the editor that
 * nothing may be uploaded and no image size is registered.
 */
public interface EditorMedia {

    /**
     * A registered image sub-size as the editor's {@code imageDimensions} shows it: the box and how it is cut
     * ({@code true} from the centre, {@code false} not at all, or a two-element list of the gravity pair).
     */
    record Dimensions(int width, int height, JsonNode crop) {}

    /**
     * The file types the caller may upload on the current site, as extension patterns (such as {@code jpg|jpeg|jpe})
     * with their MIME types, in WordPress's order.
     */
    List<Map.Entry<String, String>> allowedTypes();

    /** The largest upload the site takes, in bytes, as WordPress's {@code wp_max_upload_size} answers it. */
    long maxUploadSize();

    /** The current site's registered image sub-sizes by name, in WordPress's order; a size of 0x0 is left out. */
    Map<String, Dimensions> imageSizes();
}
