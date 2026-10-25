package org.quarkpress.revisions.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.TenantIds;

/** Queries over revisions; every query names the site. */
@ApplicationScoped
public class RevisionRepository implements PanacheRepositoryBase<RevisionEntity, Long> {

    public Optional<RevisionEntity> findRevision(SiteId site, RevisionId id) {
        return find("siteId = ?1 and id = ?2", TenantIds.param(site), id.value())
                .firstResultOptional();
    }

    /** The revision locked for update and read again under the lock; pending changes are written first. */
    public Optional<RevisionEntity> lockRevision(SiteId site, RevisionId id) {
        flush();
        Optional<RevisionEntity> found = findRevision(site, id);
        found.ifPresent(row -> getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return found;
    }

    /** The post's revisions, autosaves included, oldest first (by creation time, then id). */
    public List<RevisionEntity> ofPost(SiteId site, PostId post) {
        return list("siteId = ?1 and postId = ?2 order by createdAt, id", TenantIds.param(site), post.value());
    }

    /** The post's newest regular (not autosave) revision, by creation time, then id. */
    public Optional<RevisionEntity> latestRegular(SiteId site, PostId post) {
        return find(
                        "siteId = ?1 and postId = ?2 and autosave = false order by createdAt desc, id desc",
                        TenantIds.param(site),
                        post.value())
                .firstResultOptional();
    }

    /**
     * {@link #ofPost} with every row locked, taken in ascending id order so writers never wait for each other in
     * turn.
     */
    public List<RevisionEntity> lockOfPost(SiteId site, PostId post) {
        flush();
        List<RevisionEntity> rows = find("siteId = ?1 and postId = ?2 order by id", TenantIds.param(site), post.value())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
        // a row already in this transaction's context keeps its old state on a query: read it again
        rows.forEach(row -> getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE));
        List<RevisionEntity> oldestFirst = new ArrayList<>(rows);
        oldestFirst.sort(
                Comparator.comparing((RevisionEntity row) -> row.createdAt).thenComparing(row -> row.id));
        return oldestFirst;
    }

    /**
     * The post's autosave by {@code author}, or for an empty {@code author} the newest one without author (a user's
     * deleted profile leaves its autosave authorless, so there may be several).
     */
    public Optional<RevisionEntity> autosaveOfAuthor(SiteId site, PostId post, Optional<UserId> author) {
        return (author.isPresent()
                        ? find(
                                "siteId = ?1 and postId = ?2 and autosave = true and authorId = ?3",
                                TenantIds.param(site),
                                post.value(),
                                author.get().value())
                        : find(
                                "siteId = ?1 and postId = ?2 and autosave = true and authorId is null"
                                        + " order by createdAt desc, id desc",
                                TenantIds.param(site),
                                post.value()))
                .firstResultOptional();
    }

    public long countOfPost(SiteId site, PostId post) {
        return count("siteId = ?1 and postId = ?2", TenantIds.param(site), post.value());
    }
}
