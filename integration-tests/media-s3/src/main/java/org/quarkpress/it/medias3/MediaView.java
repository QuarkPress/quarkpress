package org.quarkpress.it.medias3;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.quarkpress.media.MediaSnapshot;

/**
 * An attachment as the test resource answers it: its id, its main file's storage key and public URL, and its sizes.
 * Test scaffolding, not an API.
 *
 * @param sizes each generated size by name: {@code file}, {@code width}, {@code height}, its storage {@code key} and
 *     its public {@code url}
 */
public record MediaView(long id, String storageKey, String url, ObjectNode sizes) {

    static MediaView of(MediaSnapshot media) {
        String folder = media.storageKey().substring(0, media.storageKey().lastIndexOf('/') + 1);
        ObjectNode sizes = JsonNodeFactory.instance.objectNode();
        media.sizes()
                .forEach((name, size) -> sizes.putObject(name)
                        .put("file", size.file())
                        .put("width", size.width())
                        .put("height", size.height())
                        .put("key", folder + size.file())
                        .put("url", media.url(name)));
        return new MediaView(media.post().id().value(), media.storageKey(), media.url(), sizes);
    }
}
