package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkpress.media.Crop;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizes;
import org.quarkpress.media.Media;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The media module's answers for the block editor's settings: the caller's allowed types and the upload limit from the
 * media API, the registered sub-sizes from the site's image sizes. The only class of the editor route that names a
 * media type, so the route loads without that module.
 */
@ApplicationScoped
public class MediaEditorSettings implements EditorMedia {

    @Inject
    Media media;

    @Inject
    ImageSizes sizes;

    @Override
    public List<Map.Entry<String, String>> allowedTypes() {
        return media.allowedTypes();
    }

    @Override
    public long maxUploadSize() {
        return media.maxUploadSize();
    }

    @Override
    public Map<String, Dimensions> imageSizes() {
        Map<String, Dimensions> dimensions = new LinkedHashMap<>();
        for (Map.Entry<String, ImageSizeDefinition> size : sizes.registered().entrySet()) {
            ImageSizeDefinition definition = size.getValue();
            dimensions.put(
                    size.getKey(), new Dimensions(definition.width(), definition.height(), crop(definition.crop())));
        }
        return dimensions;
    }

    /** WordPress's {@code crop} of a registered size: true, false, or the gravity pair as a list. */
    private static JsonNode crop(Crop crop) {
        return switch (crop) {
            case Crop.None none -> BooleanNode.FALSE;
            case Crop.Hard hard -> BooleanNode.TRUE;
            case Crop.At at -> {
                ArrayNode pair = RestJson.NODES.arrayNode();
                pair.add(at.x());
                pair.add(at.y());
                yield pair;
            }
        };
    }
}
