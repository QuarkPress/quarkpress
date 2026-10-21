package org.quarkpress.taxonomy.runtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * Keeps terms in step with the posts module, inside the transaction that saves or deletes the post (which holds the
 * site's post lock). On every save announced as a status change, a post that is not an auto-draft and has no term of a
 * taxonomy of its type that has a default term gets that term (the default category for categories; a custom
 * taxonomy's default term only when the caller may assign its terms, also when the post is published as scheduled
 * publishing does, where WordPress gives it to any caller); then, when the save moved the post into or out of a counted
 * status, the counts of all its terms are recomputed. The terms of attachments that inherit from the post are not
 * recounted, as in WordPress. Before a post is deleted, its assignments are removed and their counts recomputed. Runs
 * before application observers, so they see the result.
 */
@ApplicationScoped
public class PostTermObserver {

    @Inject
    Taxonomies taxonomies;

    @Inject
    TermAssignments assignments;

    @Inject
    TermCounts counts;

    @Inject
    DefaultTerms defaults;

    @Inject
    Acting acting;

    void saved(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) PostStatusChanged event) {
        SiteId site = event.site();
        PostSnapshot post = event.post();
        if (!post.status().equals("auto-draft")) {
            for (TaxonomyDefinition taxonomy : taxonomies.forType(post.type())) {
                Optional<TermId> fallback = defaults.current(site, taxonomy);
                if (fallback.isEmpty()) {
                    continue;
                }
                if (!taxonomy.key().equals("category")
                        && !acting.can(site, taxonomy.capability("assign_terms"), Optional.empty())) {
                    continue;
                }
                if (assignments.termsOf(site, post.id(), taxonomy.key()).isEmpty()) {
                    assignments.set(site, post.id(), taxonomy, List.of(fallback.get()), false);
                }
            }
        }
        if (counted(event.from()) != counted(event.to())) {
            counts.recount(site, assignments.allTermsOf(site, post.id()));
        }
    }

    void deleting(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) PostDeleting event) {
        assignments.forgetPost(event.site(), event.post().id());
    }

    private static boolean counted(String status) {
        return TermCounts.COUNTED_STATUSES.contains(status);
    }
}
