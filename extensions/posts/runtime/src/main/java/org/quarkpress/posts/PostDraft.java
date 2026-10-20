package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * A post as it is about to be written, after every rule of the save ran (WordPress's {@code wp_insert_post_data}).
 * {@link PostWriteFilter}s may return a changed draft; values too long for their column, and a parent that is not a
 * post of the site, are refused after the filters ran.
 */
public record PostDraft(
        String type,
        String status,
        String slug,
        String title,
        String content,
        String excerpt,
        Optional<UserId> author,
        Optional<PostId> parent,
        int menuOrder,
        Optional<Instant> publishedAt,
        Instant modifiedAt,
        String commentStatus,
        String pingStatus,
        String password) {

    public PostDraft {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Objects.requireNonNull(commentStatus, "commentStatus");
        Objects.requireNonNull(pingStatus, "pingStatus");
        Objects.requireNonNull(password, "password");
    }

    public PostDraft withType(String value) {
        return new PostDraft(
                value,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withStatus(String value) {
        return new PostDraft(
                type,
                value,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withSlug(String value) {
        return new PostDraft(
                type,
                status,
                value,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withTitle(String value) {
        return new PostDraft(
                type,
                status,
                slug,
                value,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withContent(String value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                value,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withExcerpt(String value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                value,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withAuthor(Optional<UserId> value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                value,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withParent(Optional<PostId> value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                value,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withMenuOrder(int value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                value,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withPublishedAt(Optional<Instant> value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                value,
                modifiedAt,
                commentStatus,
                pingStatus,
                password);
    }

    public PostDraft withCommentStatus(String value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                value,
                pingStatus,
                password);
    }

    public PostDraft withPingStatus(String value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                value,
                password);
    }

    public PostDraft withPassword(String value) {
        return new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                menuOrder,
                publishedAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                value);
    }
}
