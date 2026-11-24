package org.quarkword.front.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.quarkword.core.SiteContext;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;
import org.quarkword.posts.runtime.Slugs;
import org.quarkword.security.SystemIdentity;

/**
 * WordPress's lookup of a post by its slug path ({@code get_page_by_path}): the request path is percent-decoded, split
 * on slashes and each part slugified as a query slug, so the lookup ignores case; a post of one of the given types
 * whose slug is the last part and whose ancestors' slugs are the earlier parts, exactly, is the match, the first type
 * given preferred over the others (a page over an attachment of the same path). Any status counts except the trashed
 * and auto-draft ones; what the visitor may see is decided afterwards, so the rows are read as the system.
 */
@ApplicationScoped
public class PagePaths {

    @Inject
    Posts posts;

    @Inject
    SystemIdentity system;

    @Inject
    CurrentIdentityAssociation identities;

    @Inject
    SiteContext site;

    /** The post of one of {@code types} at {@code path}, if any. */
    public Optional<PostSnapshot> find(String path, List<String> types) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(types, "types");
        List<String> parts = parts(path);
        if (parts.isEmpty() || types.isEmpty()) {
            return Optional.empty();
        }
        String last = parts.get(parts.size() - 1);
        if (last.isEmpty() || (parts.size() == 1 && last.equals("0"))) {
            return Optional.empty();
        }
        String wanted = String.join("/", parts);
        return asSystem(() -> {
            PostQuery query = PostQuery.builder()
                    .types(types.toArray(String[]::new))
                    .nameIn(last)
                    .statuses("any")
                    .perPage(-1)
                    .build();
            List<PostSnapshot> matches = new ArrayList<>();
            for (PostSnapshot candidate : posts.query(query).posts()) {
                if (candidate.slug().equals(last) && posts.uri(candidate.id()).equals(wanted)) {
                    matches.add(candidate);
                }
            }
            return matches.stream()
                    .min(Comparator.comparingInt((PostSnapshot post) -> types.indexOf(post.type()))
                            .thenComparingLong(post -> post.id().value()));
        });
    }

    /** The current site's time zone, in which its posts' dates are read. */
    public ZoneId zone() {
        return site.zone();
    }

    /** The slugified parts of a percent-decoded path; empty for a blank path. */
    static List<String> parts(String path) {
        String decoded = RewriteRules.phpUrldecode(path);
        int start = 0;
        int end = decoded.length();
        while (start < end && decoded.charAt(start) == '/') {
            start++;
        }
        while (end > start && decoded.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = decoded.substring(start, end);
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int slash = trimmed.indexOf('/', from);
            String part = slash < 0 ? trimmed.substring(from) : trimmed.substring(from, slash);
            parts.add(Slugs.sanitizeTitleForQuery(part));
            if (slash < 0) {
                return parts;
            }
            from = slash + 1;
        }
    }

    private <T> T asSystem(Supplier<T> read) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (!requestContext.isActive()) {
            requestContext.activate();
            try {
                return inContext(read);
            } finally {
                requestContext.terminate();
            }
        }
        return inContext(read);
    }

    private <T> T inContext(Supplier<T> read) {
        SecurityIdentity caller = identities.getIdentity();
        identities.setIdentity(system.identity());
        try {
            return read.get();
        } finally {
            identities.setIdentity(caller);
        }
    }
}
