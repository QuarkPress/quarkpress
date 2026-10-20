package org.quarkpress.posts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.PostId;

/**
 * Post meta of the current site, as WordPress's meta functions behave: each key holds a list of values. A key that
 * is empty or "0" is refused with false, as WordPress refuses it. A public post's meta can be read by anyone; any other
 * post's needs {@code read_post}, as WordPress's REST API decides. Writes need {@code edit_post}; keys starting with
 * {@code _} (after any leading characters outside printable ASCII) are protected and written only by the system
 * identity (and by QuarkPress's own services). A post of another site, or none, is {@code invalid_post}.
 *
 * <p>Unlike WordPress, values are matched exactly, as the JSON values they are stored as: WordPress compares a value
 * with the stored text under MySQL's case-insensitive collation, so there "Red" also matches "red". There is no
 * previous value to narrow an update, and an empty key does not stand for every key: {@link #all} is the equivalent
 * of WordPress's empty key.
 */
public interface PostMeta {

    /** The values of {@code key}, in order; an empty key has none (use {@link #all}). */
    List<JsonNode> get(PostId post, String key);

    /** Every key with its values, in order. */
    Map<String, List<JsonNode>> all(PostId post);

    /** {@code add_post_meta}: appends a value; with {@code unique}, false when the key has a value already. */
    boolean add(PostId post, String key, JsonNode value, boolean unique);

    /** {@code update_post_meta}: every value of the key becomes {@code value} (added when none); false if unchanged. */
    boolean update(PostId post, String key, JsonNode value);

    /** {@code delete_post_meta}: removes the key; false when it had no value. */
    boolean delete(PostId post, String key);

    /**
     * {@code delete_post_meta} with a value: removes the values equal to it; false when none was. A value that is
     * null (Java or JSON), the empty string or false removes every value of the key, as in WordPress.
     */
    boolean delete(PostId post, String key, JsonNode value);
}
