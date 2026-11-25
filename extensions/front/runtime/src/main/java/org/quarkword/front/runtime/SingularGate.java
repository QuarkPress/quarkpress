package org.quarkword.front.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkword.core.PostStatusDefinition;
import org.quarkword.core.QuarkWordClock;
import org.quarkword.core.WordPressKeys;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.Viewer;
import org.quarkword.posts.AutosaveOverlay;
import org.quarkword.posts.PostPermissions;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostStatuses;
import org.quarkword.posts.runtime.PostCapabilityFacts;
import org.quarkword.taxonomy.PostFormats;

/**
 * What WordPress does with the post a singular request found, after the query has left out what the viewer may not
 * see: an attachment reached through a page path is an attachment, and one without a parent is single rather than a
 * page; a post whose effective status (an attachment's is its parent's) is draft, pending or scheduled is shown as a
 * preview, dated now unless it is scheduled; and for an armed preview of a post the viewer may edit, the latest
 * autosave of any user replaces the title and content, the request's {@code post_format} the format (the exact
 * {@code standard}, else an existing format by its sanitized key) and, when {@code preview_id} names the post, its
 * {@code _thumbnail_id} the featured image (removed by a value of 0 or less, unchanged when empty or {@code 0}). The autosave comes from the revisions
 * module and is skipped without it.
 */
@ApplicationScoped
public class SingularGate {

    @Inject
    PostPermissions permissions;

    @Inject
    PostStatuses statuses;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    PostFormats formats;

    @Inject
    QuarkWordClock clock;

    /** The latest autosave; a bean only while the revisions module is present. */
    @Inject
    Instance<AutosaveOverlay> autosaves;

    /** The request with its shown post, flags and date; no preview values are read from the request. */
    public FrontRequest apply(FrontRequest draft, Viewer viewer, boolean previewArmed) {
        return apply(draft, viewer, previewArmed, Map.of());
    }

    /**
     * The request with its shown post, flags and date, the previewed format and featured image read from the raw
     * {@code query} parameters (the last value of a repeated name counts).
     */
    public FrontRequest apply(
            FrontRequest draft, Viewer viewer, boolean previewArmed, Map<String, List<String>> query) {
        List<PostSnapshot> posts = draft.posts().posts();
        if (posts.isEmpty()) {
            return draft.withShown(Optional.empty(), clock.now());
        }
        PostSnapshot post = posts.get(0);
        if (!draft.flags().singular()) {
            return draft.withShown(Optional.of(post), dated(post));
        }
        FrontFlags flags = draft.flags();
        if (post.type().equals("attachment")) {
            if (flags.page()) {
                flags = flags.withAttachment(true);
            }
            if (post.parent().isEmpty()) {
                flags = flags.withPage(false).withSingle(true).withAttachment(true);
            }
        }
        String status = facts.of(post).effectiveStatus();
        Optional<PostStatusDefinition> definition = statuses.find(status);
        boolean publicly = definition.map(PostStatusDefinition::publicly).orElse(false);
        boolean protectedStatus = !publicly
                && definition.map(PostStatusDefinition::protectedStatus).orElse(false);
        Instant shownAt = dated(post);
        if (protectedStatus) {
            flags = flags.withPreview(true);
            if (!status.equals("future")) {
                shownAt = clock.now();
            }
        }
        PostSnapshot shown = post;
        if (flags.preview() && previewArmed && permissions.can(viewer.identity(), "edit_post", post)) {
            shown = overlaid(post, query);
        }
        return draft.withFlags(flags).withShown(Optional.of(shown), shownAt);
    }

    /** The post's date: its GMT date, or its modification time while the date floats. */
    private static Instant dated(PostSnapshot post) {
        return post.publishedAt().orElse(post.modifiedAt());
    }

    /** The post with the latest autosave's title and content, and the request's previewed format and image. */
    private PostSnapshot overlaid(PostSnapshot post, Map<String, List<String>> query) {
        String title = post.title();
        String content = post.content();
        if (autosaves.isResolvable()) {
            Optional<AutosaveOverlay.Autosave> autosave = autosaves.get().latest(post.id());
            if (autosave.isPresent()) {
                title = autosave.get().title();
                content = autosave.get().content();
            }
        }
        Map<String, List<JsonNode>> meta = new LinkedHashMap<>(post.meta());
        last(query, "post_format").filter(format -> !format.isEmpty()).ifPresent(format -> {
            // only the exact "standard" means no format; any other value is looked up by its sanitized key
            String key = format.equals("standard") ? format : WordPressKeys.sanitize(format);
            if (format.equals("standard") || (!key.isEmpty() && formats.exists(key))) {
                meta.put("post_format", List.of(TextNode.valueOf(key)));
            }
        });
        // PHP's empty(): an image of "" or "0" leaves the post's image as it is
        Optional<String> thumbnail =
                last(query, "_thumbnail_id").filter(value -> !value.isEmpty() && !value.equals("0"));
        Optional<String> previewId = last(query, "preview_id");
        if (thumbnail.isPresent()
                && previewId.isPresent()
                && FlagParser.phpInt(previewId.get()) == post.id().value()) {
            long id = FlagParser.phpInt(thumbnail.get());
            if (id > 0) {
                meta.put("_thumbnail_id", List.of(LongNode.valueOf(id)));
            } else {
                meta.remove("_thumbnail_id");
            }
        }
        return new PostSnapshot(
                post.id(),
                post.site(),
                post.type(),
                post.status(),
                post.slug(),
                title,
                content,
                post.excerpt(),
                post.author(),
                post.parent(),
                post.menuOrder(),
                post.publishedAt(),
                post.createdAt(),
                post.modifiedAt(),
                post.commentStatus(),
                post.pingStatus(),
                post.password(),
                post.sticky(),
                post.guid(),
                meta,
                post.version());
    }

    private static Optional<String> last(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(values.size() - 1));
    }
}
