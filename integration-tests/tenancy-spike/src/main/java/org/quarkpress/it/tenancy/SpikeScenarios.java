package org.quarkpress.it.tenancy;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** The gate criteria for site isolation by filter and listener. Each criterion throws AssertionError when it does not hold. */
@ApplicationScoped
public class SpikeScenarios {

    public static final List<String> CRITERIA = List.of(
            "boots",
            "insert-stamps-site",
            "query-filters-other-sites",
            "find-by-id-filtered",
            "insert-for-other-site-rejected",
            "update-from-other-site-has-no-effect",
            "delete-from-other-site-has-no-effect",
            "root-sees-all-and-inserts-explicitly",
            "root-insert-without-site-fails",
            "unresolved-sees-nothing",
            "bulk-update-with-site-predicate",
            "no-request-context-fails-closed",
            "concurrent-tenants-isolated");

    static final String ROOT = "-1";
    static final String UNRESOLVED = "0";
    static final String SITE_1 = "1";
    static final String SITE_2 = "2";
    static final String SITE_3 = "3";

    @Inject
    SpikeTenant tenant;

    @Inject
    SpikeNoteRepository notes;

    @Inject
    @PersistenceUnit("quarkpress")
    EntityManager em;

    public void run(String criterion) throws Exception {
        switch (criterion) {
            case "boots" -> boots();
            case "insert-stamps-site" -> insertStampsSite();
            case "query-filters-other-sites" -> queryFiltersOtherSites();
            case "find-by-id-filtered" -> findByIdFiltered();
            case "insert-for-other-site-rejected" -> insertForOtherSiteRejected();
            case "update-from-other-site-has-no-effect" -> updateFromOtherSiteHasNoEffect();
            case "delete-from-other-site-has-no-effect" -> deleteFromOtherSiteHasNoEffect();
            case "root-sees-all-and-inserts-explicitly" -> rootSeesAllAndInsertsExplicitly();
            case "root-insert-without-site-fails" -> rootInsertWithoutSiteFails();
            case "unresolved-sees-nothing" -> unresolvedSeesNothing();
            case "bulk-update-with-site-predicate" -> bulkUpdateWithSitePredicate();
            case "no-request-context-fails-closed" -> noRequestContextFailsClosed();
            case "concurrent-tenants-isolated" -> concurrentTenantsIsolated();
            default -> throw new IllegalArgumentException("unknown criterion " + criterion);
        }
    }

    /**
     * What a bulk update without a site predicate does when issued under site 1 while a site-1 row and a site-2 row
     * both match: {@code rows=<changed> site1=<changed|unchanged> site2=<changed|unchanged>}.
     */
    public String unguardedBulkUpdate() {
        String body = marker();
        long site1 = insert(SITE_1, body);
        long site2 = insert(SITE_2, body);
        int rows = as(
                SITE_1,
                () -> em.createQuery("update SpikeNote n set n.body = :next where n.body = :body")
                        .setParameter("next", body + "-next")
                        .setParameter("body", body)
                        .executeUpdate());
        return "rows=" + rows + " site1=" + changed(site1, body) + " site2=" + changed(site2, body);
    }

    private String changed(long id, String original) {
        return as(ROOT, () -> notes.findById(id).body).equals(original) ? "unchanged" : "changed";
    }

    private void boots() {
        long total = as(ROOT, () -> notes.count());
        check(total >= 0, "count under the root tenant returned " + total);
    }

    private void insertStampsSite() {
        long id = insert(SITE_1, marker());
        long stored = storedSite(id);
        check(stored == 1, "a row inserted under site 1 has site_id " + stored);
    }

    private void queryFiltersOtherSites() {
        String body = marker();
        insert(SITE_1, body);
        check(countAs(SITE_2, body) == 0, "site 2 sees a site-1 row through Panache");
        check(countAs(SITE_1, body) == 1, "site 1 does not see its own row");
        List<SpikeNote> viaHql = as(
                SITE_2,
                () -> em.createQuery("select n from SpikeNote n where n.body = :body", SpikeNote.class)
                        .setParameter("body", body)
                        .getResultList());
        check(viaHql.isEmpty(), "site 2 sees a site-1 row through HQL");
        long viaCriteria = as(SITE_2, () -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<SpikeNote> root = query.from(SpikeNote.class);
            query.select(cb.count(root)).where(cb.equal(root.get("body"), body));
            return em.createQuery(query).getSingleResult();
        });
        check(viaCriteria == 0, "site 2 sees a site-1 row through a criteria query");
    }

    private void findByIdFiltered() {
        long id = insert(SITE_1, marker());
        check(as(SITE_2, () -> notes.findById(id)) == null, "Panache findById crosses sites");
        check(as(SITE_2, () -> em.find(SpikeNote.class, id)) == null, "EntityManager.find crosses sites");
        check(as(SITE_1, () -> notes.findById(id)) != null, "site 1 cannot load its own row");
    }

    private void insertForOtherSiteRejected() {
        String body = marker();
        Throwable failure = failure(() -> as(SITE_1, () -> {
            notes.persist(SpikeNote.forSite(SITE_2, body));
            notes.flush();
            return null;
        }));
        check(
                mentions(failure, "written under site"),
                "inserting a site-2 row under site 1 was not refused: " + failure);
        check(countAs(ROOT, body) == 0, "the rejected row was stored");
    }

    private void updateFromOtherSiteHasNoEffect() {
        String body = marker();
        long id = insert(SITE_1, body);
        Throwable merged = failure(() -> as(SITE_2, () -> {
            SpikeNote detached = SpikeNote.forSite(SITE_2, body + "-merged");
            detached.id = id;
            em.merge(detached);
            em.flush();
            return null;
        }));
        check(
                mentions(merged, "OptimisticLockException"),
                "merging a detached site-1 row from site 2 did not fail with an optimistic lock failure: " + merged);
        check(countAs(ROOT, body + "-merged") == 0, "merging a detached site-1 row from site 2 stored a row");
        int bulk = as(
                SITE_2,
                () -> em.createQuery("update SpikeNote n set n.body = :next where n.id = :id and n.siteId = :site")
                        .setParameter("next", body + "-bulk")
                        .setParameter("id", id)
                        .setParameter("site", SiteScopedEntity.siteValue(SITE_2))
                        .executeUpdate());
        check(bulk == 0, "a site-guarded bulk update from site 2 changed " + bulk + " rows");
        String stored = as(SITE_1, () -> notes.findById(id).body);
        check(stored.equals(body), "a site-1 row was changed from site 2: " + stored);
    }

    private void deleteFromOtherSiteHasNoEffect() {
        String body = marker();
        long id = insert(SITE_1, body);
        boolean deleted = as(SITE_2, () -> notes.deleteById(id));
        check(!deleted, "Panache deleteById removed a site-1 row from site 2");
        long bulk = as(SITE_2, () -> notes.delete("id = ?1 and siteId = ?2", id, SiteScopedEntity.siteValue(SITE_2)));
        check(bulk == 0, "a site-guarded bulk delete from site 2 removed " + bulk + " rows");
        check(countAs(ROOT, body) == 1, "the site-1 row is gone");
    }

    private void rootSeesAllAndInsertsExplicitly() {
        String body = marker();
        insert(SITE_1, body);
        insert(SITE_2, body);
        check(countAs(ROOT, body) == 2, "the root tenant does not see both sites");
        long id = as(ROOT, () -> {
            SpikeNote note = SpikeNote.forSite(SITE_3, body);
            notes.persist(note);
            notes.flush();
            return note.id;
        });
        long stored = storedSite(id);
        check(stored == 3, "the root tenant stored site " + stored + " instead of 3");
    }

    private void rootInsertWithoutSiteFails() {
        String body = marker();
        Throwable failure = failure(() -> as(ROOT, () -> {
            notes.persist(SpikeNote.of(body));
            notes.flush();
            return null;
        }));
        check(mentions(failure, "must name its site"), "a site-less insert under the root was not refused: " + failure);
        check(countAs(ROOT, body) == 0, "a row without a site was stored");
    }

    private void unresolvedSeesNothing() {
        String body = marker();
        insert(SITE_1, body);
        check(countAs(UNRESOLVED, body) == 0, "the unresolved tenant sees site rows");
        Throwable failure = failure(() -> insert(UNRESOLVED, body));
        check(
                mentions(failure, "ConstraintViolationException"),
                "an insert for the unresolved site 0 did not hit the foreign key: " + failure);
        check(countAs(ROOT, body) == 1, "a row for site 0 was stored");
    }

    private void bulkUpdateWithSitePredicate() {
        String body = marker();
        insert(SITE_1, body);
        insert(SITE_2, body);
        int changed = as(
                SITE_1,
                () -> em.createQuery("update SpikeNote n set n.body = :next where n.siteId = :site and n.body = :body")
                        .setParameter("next", body + "-next")
                        .setParameter("site", SiteScopedEntity.siteValue(SITE_1))
                        .setParameter("body", body)
                        .executeUpdate());
        check(changed == 1, "the guarded bulk update changed " + changed + " rows");
        check(countAs(ROOT, body) == 1, "the guarded bulk update touched site 2");
        check(countAs(ROOT, body + "-next") == 1, "the guarded bulk update missed site 1");
    }

    private void noRequestContextFailsClosed() throws InterruptedException {
        String body = marker();
        insert(SITE_1, body);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                outcome.set(QuarkusTransaction.requiringNew().call(() -> notes.count("body", body)));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        worker.start();
        worker.join();
        check(
                outcome.get() instanceof Throwable t && mentions(t, "no request context"),
                "a thread without a request context was not refused: " + outcome.get());
    }

    private void concurrentTenantsIsolated() throws Exception {
        String body = marker();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                String site = i % 2 == 0 ? SITE_1 : SITE_2;
                String other = i % 2 == 0 ? SITE_2 : SITE_1;
                results.add(pool.submit(() -> inNewRequest(() -> {
                    insert(site, body + "-" + site);
                    long own = countAs(site, body + "-" + site);
                    long foreign = countAs(site, body + "-" + other);
                    return own >= 1 && foreign == 0 ? "ok" : "site " + site + " saw own=" + own + " foreign=" + foreign;
                })));
            }
            for (Future<String> result : results) {
                String outcome = result.get();
                check(outcome.equals("ok"), outcome);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> T as(String tenantId, Supplier<T> work) {
        String previous = tenant.get();
        tenant.set(tenantId);
        try {
            return QuarkusTransaction.requiringNew().call(work::get);
        } finally {
            tenant.set(previous);
        }
    }

    private long insert(String tenantId, String body) {
        return as(tenantId, () -> {
            SpikeNote note = SpikeNote.of(body);
            notes.persist(note);
            notes.flush();
            return note.id;
        });
    }

    private long countAs(String tenantId, String body) {
        return as(tenantId, () -> notes.count("body", body));
    }

    private long storedSite(long id) {
        return as(
                ROOT,
                () -> ((Number) em.createNativeQuery("select site_id from spike_note where id = :id")
                                .setParameter("id", id)
                                .getSingleResult())
                        .longValue());
    }

    private static <T> T inNewRequest(Supplier<T> work) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            return work.get();
        } finally {
            requestContext.terminate();
        }
    }

    private static Throwable failure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    /** Whether the failure or one of its causes has {@code needle} in its class name or message. */
    private static boolean mentions(Throwable failure, String needle) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (t.getClass().getName().contains(needle) || (message != null && message.contains(needle))) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String marker() {
        return "spike-" + UUID.randomUUID();
    }
}
