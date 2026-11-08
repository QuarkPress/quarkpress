package org.quarkpress.media.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;

/**
 * The registered sizes an attachment lacks, as WordPress lists them: what a post-process would try to make, and what
 * the REST view reports as missing.
 */
public final class MissingSizes {

    private MissingSizes() {}

    /**
     * The names of {@code registered} that {@code media} lacks, in registration order. None for an attachment that is
     * not an image (its type does not start with {@code image/}); every registered name while it has no metadata
     * (see {@link #hasNoMetadata}), whatever its dimensions. Otherwise the sizes possible for the reference dimensions
     * whose name is not among the attachment's sizes: the reference is {@code original}, the untouched upload's stored
     * dimensions as read from its file, when the main file is a copy of it and the original could be read; else the
     * main file's dimensions (unknown dimensions make no size possible). The dimensions are never rotated, so a size
     * can be listed that the upright image can never give, as in WordPress.
     *
     * @param original the dimensions read from the original file, when there is one and it could be read
     */
    public static List<String> of(
            MediaSnapshot media, Map<String, ImageSizeDefinition> registered, Optional<ImageInfo> original) {
        if (!media.mimeType().startsWith("image/")) {
            return List.of();
        }
        if (hasNoMetadata(media)) {
            return List.copyOf(registered.keySet());
        }
        int width;
        int height;
        if (media.originalImage().isPresent() && original.isPresent()) {
            width = original.get().width();
            height = original.get().height();
        } else {
            width = media.width().orElse(0);
            height = media.height().orElse(0);
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, ImageSizeDefinition> entry : registered.entrySet()) {
            ImageSizeDefinition size = entry.getValue();
            if (!media.sizes().containsKey(entry.getKey())
                    && Dimensions.resize(width, height, size.width(), size.height(), size.crop())
                            .isPresent()) {
                missing.add(entry.getKey());
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Whether no run has saved the attachment's metadata: it is pending, or failed (a run ended before its first save,
     * or could not read the file). Processing it again then starts from scratch, as WordPress does for an attachment
     * without metadata. A partial attachment has its metadata, and so does one a run finished without dimensions (a
     * file of an image type that cannot be read as an image keeps its file size alone, as in WordPress).
     */
    public static boolean hasNoMetadata(MediaSnapshot media) {
        return media.processing() == Processing.PENDING || media.processing() == Processing.FAILED;
    }
}
