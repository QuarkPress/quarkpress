package org.quarkword.front;

import java.util.Objects;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.security.UserProfile;
import org.quarkword.taxonomy.TermSnapshot;

/**
 * What a front-end request is about, as WordPress's queried object: the post or page of a singular request, the term
 * of a category, tag or taxonomy archive, the author of an author archive, the post type of a post type archive, or
 * the page configured as the posts page. {@link #id()} is WordPress's queried object id: 0 for a post type archive.
 */
public sealed interface QueriedObject {

    /** The object's id: the post's, the term's, the user's, the posts page's, or 0 for a post type archive. */
    long id();

    /** The post, page or attachment of a singular request. */
    record Post(PostSnapshot post) implements QueriedObject {
        public Post {
            Objects.requireNonNull(post, "post");
        }

        @Override
        public long id() {
            return post.id().value();
        }
    }

    /** The term of a category, tag or custom taxonomy archive. */
    record Term(TermSnapshot term) implements QueriedObject {
        public Term {
            Objects.requireNonNull(term, "term");
        }

        @Override
        public long id() {
            return term.id().value();
        }
    }

    /** The user of an author archive. */
    record Author(UserProfile user) implements QueriedObject {
        public Author {
            Objects.requireNonNull(user, "user");
        }

        @Override
        public long id() {
            return user.id().value();
        }
    }

    /** The post type of a post type archive; WordPress gives it no id. */
    record PostTypeArchive(PostTypeDefinition type) implements QueriedObject {
        public PostTypeArchive {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public long id() {
            return 0;
        }
    }

    /** The page configured as the posts page, which lists posts instead of showing itself. */
    record PostsPage(PostSnapshot page) implements QueriedObject {
        public PostsPage {
            Objects.requireNonNull(page, "page");
        }

        @Override
        public long id() {
            return page.id().value();
        }
    }
}
