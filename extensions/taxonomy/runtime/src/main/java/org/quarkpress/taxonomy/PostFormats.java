package org.quarkpress.taxonomy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.PostId;

/**
 * Post formats on the current site: WordPress's {@code get_post_format} and {@code set_post_format}. A format is a
 * term of the built-in {@code post_format} taxonomy whose slug and name are {@code post-format-{slug}}; a post with no
 * such term has the {@code standard} format, which is no term at all. Formats are assigned with {@code edit_post} on
 * the post alone, as WordPress assigns them without the taxonomy's {@code assign_terms}.
 */
public interface PostFormats {

    /** WordPress's format slugs, the standard one first. */
    List<String> SLUGS =
            List.of("standard", "aside", "chat", "gallery", "link", "image", "quote", "status", "video", "audio");

    /**
     * The English labels in {@link #SLUGS} order: Standard, Aside, Chat, Gallery, Link, Image, Quote, Status, Video,
     * Audio.
     */
    Map<String, String> LABELS = labels();

    /** The post's format slug; {@code standard} when it has none. */
    String of(PostId post);

    /**
     * Sets the post's format; needs {@code edit_post} on the post. {@code standard} removes the format term; another
     * slug of {@link #SLUGS} assigns {@code post-format-{slug}}, created on first use with its slug as its name;
     * anything else is an {@link IllegalArgumentException}.
     */
    void set(PostId post, String slug);

    /** Whether the site has the {@code post-format-{slug}} term; never for {@code standard}. */
    boolean exists(String slug);

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String slug : SLUGS) {
            labels.put(slug, Character.toUpperCase(slug.charAt(0)) + slug.substring(1));
        }
        return Collections.unmodifiableMap(labels);
    }
}
