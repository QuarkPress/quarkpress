package org.quarkword.front;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.posts.PostPage;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostSnapshot;

/**
 * A front-end request resolved as WordPress resolves it: the request parser's public variables ({@code vars}), the
 * main query's variables as its own code reads them after the query ran ({@code queryVars}: normalised, the slug of a
 * {@code pagename} or {@code attachment} path written back as {@code name}, the id of {@code page_id} or
 * {@code attachment_id} as {@code p}), the conditional flags, the queried object, the main query that ran (none when the request was answered before it),
 * its posts, the content page ({@code page}, 0 when none) and the list page ({@code paged}, 0 when none) asked for,
 * and the answer: {@code status} 200, 404 or 403 (or another status a plain-permalink {@code error} variable asks
 * for), {@code message} the 403's text, {@code previewArmed} whether a valid preview pair came with the request.
 *
 * <p>{@code shownPost} is the first post after the visibility gate, with the latest autosave's title and content in
 * place of the post's for an armed preview the viewer may edit; a previewed format is then in its meta under {@code
 * post_format} and a previewed featured image under {@code _thumbnail_id}. {@code shownAt} is the date to show: now
 * for a draft or pending post shown as a preview, else the post's date (now when no post is shown).
 */
public record FrontRequest(
        QueryVars vars,
        QueryVars queryVars,
        FrontFlags flags,
        Optional<QueriedObject> queriedObject,
        Optional<PostQuery> query,
        PostPage posts,
        int page,
        int paged,
        int status,
        Optional<String> message,
        boolean previewArmed,
        Optional<PostSnapshot> shownPost,
        Instant shownAt) {

    /** The text of the 403 a bad preview pair is answered with. */
    public static final String CANNOT_PREVIEW = "Sorry, you are not allowed to preview drafts.";

    /** No posts. */
    public static final PostPage NO_POSTS = new PostPage(List.of(), 0, 0);

    public FrontRequest {
        Objects.requireNonNull(vars, "vars");
        Objects.requireNonNull(queryVars, "queryVars");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(queriedObject, "queriedObject");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(posts, "posts");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(shownPost, "shownPost");
        Objects.requireNonNull(shownAt, "shownAt");
    }

    /**
     * A request answered with {@code status} before any query: no posts, nothing shown; the main query's variables
     * are the request's.
     */
    public static FrontRequest answered(QueryVars vars, FrontFlags flags, int status, Instant now) {
        return new FrontRequest(
                vars,
                vars,
                flags,
                Optional.empty(),
                Optional.empty(),
                NO_POSTS,
                0,
                0,
                status,
                Optional.empty(),
                false,
                Optional.empty(),
                now);
    }

    /** This request with the main query's variables {@code value}. */
    public FrontRequest withQueryVars(QueryVars value) {
        return new FrontRequest(
                vars,
                value,
                flags,
                queriedObject,
                query,
                posts,
                page,
                paged,
                status,
                message,
                previewArmed,
                shownPost,
                shownAt);
    }

    public FrontRequest withFlags(FrontFlags value) {
        return new FrontRequest(
                vars,
                queryVars,
                value,
                queriedObject,
                query,
                posts,
                page,
                paged,
                status,
                message,
                previewArmed,
                shownPost,
                shownAt);
    }

    public FrontRequest withQueriedObject(Optional<QueriedObject> value) {
        return new FrontRequest(
                vars,
                queryVars,
                flags,
                value,
                query,
                posts,
                page,
                paged,
                status,
                message,
                previewArmed,
                shownPost,
                shownAt);
    }

    public FrontRequest withStatus(int value) {
        return new FrontRequest(
                vars,
                queryVars,
                flags,
                queriedObject,
                query,
                posts,
                page,
                paged,
                value,
                message,
                previewArmed,
                shownPost,
                shownAt);
    }

    public FrontRequest withMessage(String value) {
        return new FrontRequest(
                vars,
                queryVars,
                flags,
                queriedObject,
                query,
                posts,
                page,
                paged,
                status,
                Optional.of(value),
                previewArmed,
                shownPost,
                shownAt);
    }

    public FrontRequest withShown(Optional<PostSnapshot> post, Instant at) {
        return new FrontRequest(
                vars,
                queryVars,
                flags,
                queriedObject,
                query,
                posts,
                page,
                paged,
                status,
                message,
                previewArmed,
                post,
                at);
    }
}
