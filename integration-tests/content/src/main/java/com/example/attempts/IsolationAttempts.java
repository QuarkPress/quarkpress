package com.example.attempts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.it.content.model.ItBook;
import org.quarkpress.it.content.model.ItShelf;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;

/**
 * Application code (outside the QuarkPress packages, so the build-time checks do not apply) that tries the run-time
 * paths around site isolation. Each method answers "ok" when isolation held, or describes the leak.
 */
@ApplicationScoped
public class IsolationAttempts {

    @Inject
    @PersistenceUnit("quarkpress")
    EntityManager em;

    @Inject
    @PersistenceUnit("quarkpress")
    EntityManagerFactory emf;

    @Inject
    SiteScope scope;

    @Inject
    SiteBinding binding;

    @Inject
    ManagedExecutor executor;

    @Inject
    Options options;

    @Inject
    OptionRepository optionRepository;

    /**
     * Shared caches would serve one site's rows to another: they are off, and a row site A loaded by id is not handed
     * to site B by a later load of the same id.
     */
    public String cachesOff(long a, long b) throws Exception {
        SessionFactoryOptions settings =
                emf.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
        if (settings.isSecondLevelCacheEnabled() || settings.isQueryCacheEnabled()) {
            return "cache on: second-level=" + settings.isSecondLevelCacheEnabled() + " query="
                    + settings.isQueryCacheEnabled();
        }
        SiteId siteA = SiteId.of(a);
        SiteId siteB = SiteId.of(b);
        long shelfId = scope.call(siteA, () -> {
            ItShelf shelf = ItShelf.labelled("cached shelf of A");
            em.persist(shelf);
            em.flush();
            return shelf.id;
        });
        boolean foundByA = scope.call(siteA, () -> em.find(ItShelf.class, shelfId) != null);
        boolean foundByB = scope.call(siteB, () -> em.find(ItShelf.class, shelfId) != null);
        return foundByA && !foundByB ? "ok" : "loaded by id: site A " + foundByA + ", site B " + foundByB;
    }

    /**
     * Site B must not attach a row to a row of site A: the composite foreign key (site_id, shelf_id) refuses the write,
     * nothing is stored, and the collection of the site-A row shows only its own children.
     */
    public String crossSiteAttachRefused(long a, long b) throws Exception {
        SiteId siteA = SiteId.of(a);
        SiteId siteB = SiteId.of(b);
        long shelfId = scope.call(siteA, () -> {
            ItShelf shelf = ItShelf.labelled("shelf of A");
            em.persist(shelf);
            em.persist(ItBook.on(shelf, "book of A"));
            em.flush();
            return shelf.id;
        });
        String outcome;
        try {
            scope.run(siteB, () -> {
                em.persist(ItBook.on(em.getReference(ItShelf.class, shelfId), "book of B"));
                em.flush();
            });
            outcome = "written";
        } catch (RuntimeException refused) {
            outcome = refusedByConstraint(refused, "it_book_shelf_fk") ? "refused" : "failed otherwise: " + refused;
        }
        Number stored = scope.call(
                siteA, () -> (Number) em.createNativeQuery("select count(*) from it_book where shelf_id = :shelf")
                        .setParameter("shelf", shelfId)
                        .getSingleResult());
        List<String> titles = scope.call(
                siteA,
                () -> em.find(ItShelf.class, shelfId).books.stream()
                        .map(book -> book.title)
                        .sorted()
                        .toList());
        return outcome.equals("refused") && stored.longValue() == 1 && titles.equals(List.of("book of A"))
                ? "ok"
                : outcome + ", rows on the shelf " + stored + ", the shelf of site A shows " + titles;
    }

    /** A row loaded around the filter must not be writable from another site. */
    public String loadedSiteGuarded(long a, long b) throws Exception {
        SiteId siteA = SiteId.of(a);
        SiteId siteB = SiteId.of(b);
        String name = "guard-" + UUID.randomUUID();
        scope.run(siteA, () -> options.set(name, TextNode.valueOf("a")));
        long id =
                scope.call(siteA, () -> optionRepository.findOption(siteA, name).orElseThrow().id);
        String outcome;
        try {
            scope.run(siteB, () -> {
                OptionEntity row = (OptionEntity)
                        em.createNativeQuery("select * from qp_option where id = :id", OptionEntity.class)
                                .setParameter("id", id)
                                .getSingleResult();
                row.value = TextNode.valueOf("b");
                em.flush();
            });
            outcome = "written";
        } catch (RuntimeException refused) {
            outcome = mentions(refused, "changed under site") ? "refused" : "failed otherwise: " + refused;
        }
        JsonNode stored =
                scope.call(siteA, () -> optionRepository.findOption(siteA, name).orElseThrow().value);
        return outcome.equals("refused") && stored.equals(TextNode.valueOf("a"))
                ? "ok"
                : outcome + ", stored value " + stored;
    }

    /**
     * Code sharing this request keeps this request's site while a scope for another site runs: a task that captured
     * the request's context, and a second blocking handler on the request's own Vert.x duplicated context (which finds
     * the request context through that Vert.x context, not through a captured copy). Runs inside an HTTP request on a
     * worker thread, where the request context lives on that duplicated context.
     */
    public String propagatedTasksKeepTheirSite(long b) {
        Context vertxContext = Vertx.currentContext();
        if (vertxContext == null || !VertxContext.isDuplicatedContext(vertxContext) || Context.isOnEventLoopThread()) {
            return "not on a worker thread of an HTTP request's duplicated context: " + vertxContext;
        }
        Binding outer = binding.current();
        Binding inner = Binding.site(SiteId.of(b));
        CountDownLatch scopeEntered = new CountDownLatch(1);
        AtomicReference<Binding> seenByTask = new AtomicReference<>();
        AtomicReference<Binding> seenInScope = new AtomicReference<>();
        CompletableFuture<Void> task = executor.runAsync(() -> {
            try {
                scopeEntered.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            seenByTask.set(binding.current());
        });
        CompletableFuture<Object> sameContextReader = vertxContext
                .executeBlocking(
                        () -> {
                            scopeEntered.await(30, TimeUnit.SECONDS);
                            try {
                                return (Object) binding.current();
                            } catch (RuntimeException failure) {
                                return "no binding: " + failure;
                            }
                        },
                        false)
                .toCompletionStage()
                .toCompletableFuture();
        scope.run(SiteId.of(b), () -> {
            seenInScope.set(binding.current());
            scopeEntered.countDown();
            task.join();
            sameContextReader.orTimeout(30, TimeUnit.SECONDS).join();
        });
        Object seenOnSameContext = sameContextReader.join();
        if (!inner.equals(seenInScope.get())) {
            return "the scope saw " + seenInScope.get() + " instead of " + inner;
        }
        if (!outer.equals(seenByTask.get())) {
            return "the task saw " + seenByTask.get() + " instead of " + outer;
        }
        if (!outer.equals(seenOnSameContext)) {
            return "another thread on the request's Vert.x context saw " + seenOnSameContext + " instead of " + outer;
        }
        if (!outer.equals(binding.current())) {
            return "the request saw " + binding.current() + " after the scope instead of " + outer;
        }
        return "ok";
    }

    /**
     * Nothing is written while no site is bound, even when the row names one. The request's own binding is written once
     * and cannot be cleared, so the attempt runs where a host's background code would: on a thread of its own, in a
     * request context it activated and bound to no site.
     */
    public String unboundWritesRefused(long a) throws Exception {
        SiteId siteA = SiteId.of(a);
        String name = "unbound-" + UUID.randomUUID();
        AtomicReference<String> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                binding.bind(Binding.UNRESOLVED);
                QuarkusTransaction.requiringNew().run(() -> {
                    optionRepository.persist(OptionEntity.create(
                            siteA,
                            name,
                            TextNode.valueOf("x"),
                            true,
                            Instant.now().truncatedTo(ChronoUnit.MICROS)));
                    optionRepository.flush();
                });
                outcome.set("written");
            } catch (RuntimeException refused) {
                outcome.set(mentions(refused, "no site is bound") ? "refused" : "failed otherwise: " + refused);
            } finally {
                requestContext.terminate();
            }
        });
        worker.start();
        worker.join();
        boolean stored =
                scope.call(siteA, () -> optionRepository.findOption(siteA, name).isPresent());
        return "refused".equals(outcome.get()) && !stored ? "ok" : outcome.get() + ", stored " + stored;
    }

    private static boolean refusedByConstraint(Throwable failure, String constraint) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && (contains(violation.getConstraintName(), constraint)
                            || contains(violation.getMessage(), constraint))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean mentions(Throwable failure, String needle) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
