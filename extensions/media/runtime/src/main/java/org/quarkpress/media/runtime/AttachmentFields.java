package org.quarkpress.media.runtime;

import java.util.Optional;
import org.quarkpress.lib.wpkses.TextFields;
import org.quarkpress.posts.runtime.PostRules;

/**
 * The title, caption and alternative text of a new attachment, from the request, the file's embedded data and its
 * names.
 */
public final class AttachmentFields {

    private AttachmentFields() {}

    /**
     * The title, as WordPress's REST upload chooses it: the first non-empty (as PHP sees it: neither {@code ""} nor
     * {@code "0"}) of the requested title, the embedded
     * title, the client's file name before its last dot (as sent, before any cleaning or correction; nothing for a
     * name without a dot or whose only dot comes first), and the stored file name without its extension.
     *
     * @param requested the title the request gives
     * @param embedded the texts read from the file
     * @param clientName the file name the client sent
     * @param storedName the name the file is stored under
     */
    public static String title(
            Optional<String> requested, EmbeddedText embedded, String clientName, String storedName) {
        if (requested.isPresent() && !PostRules.phpEmpty(requested.get())) {
            return requested.get();
        }
        if (!PostRules.phpEmpty(embedded.title())) {
            return embedded.title();
        }
        int dot = clientName.lastIndexOf('.');
        if (dot > 0 && !PostRules.phpEmpty(clientName.substring(0, dot))) {
            return clientName.substring(0, dot);
        }
        return FileNames.withoutExtension(storedName);
    }

    /**
     * The caption: the requested one unless it is empty as PHP sees it ({@code ""} or {@code "0"}), in which case the
     * embedded caption when that is not empty, else the requested value (or nothing).
     */
    public static String caption(Optional<String> requested, EmbeddedText embedded) {
        if (requested.isPresent() && !PostRules.phpEmpty(requested.get())) {
            return requested.get();
        }
        if (!PostRules.phpEmpty(embedded.caption())) {
            return embedded.caption();
        }
        return requested.orElse("");
    }

    /**
     * The description of a sideloaded file, where WordPress puts an embedded caption: the requested description unless
     * it is empty as PHP sees it, in which case the embedded caption when that is not empty, else the requested value
     * (or nothing).
     */
    public static String sideloadDescription(Optional<String> requested, EmbeddedText embedded) {
        return caption(requested, embedded);
    }

    /**
     * The alternative text: the requested one when given, even empty; else the embedded one when it is not blank
     * (PHP's trim leaves something); else empty; cleaned as WordPress cleans a text field either way.
     */
    public static String alt(Optional<String> requested, EmbeddedText embedded) {
        if (requested.isPresent()) {
            return TextFields.textField(requested.get());
        }
        if (!ImageMetaReader.trim(embedded.alt()).isEmpty()) {
            return TextFields.textField(embedded.alt());
        }
        return "";
    }
}
