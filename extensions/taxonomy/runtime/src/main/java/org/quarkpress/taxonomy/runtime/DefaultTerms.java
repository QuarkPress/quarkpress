package org.quarkpress.taxonomy.runtime;

import com.fasterxml.jackson.databind.node.LongNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.jboss.logging.Logger;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionWrites;
import org.quarkpress.persistence.runtime.SiteRepository;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermException;

/**
 * The default terms every site has. A new site gets WordPress's default category, Uncategorized, inside the transaction
 * that creates it, written as WordPress's installer writes it: the term fires no {@code TermCreated}, while the write of
 * its {@code default_category} option fires the persistence module's {@code OptionChanged}. Every taxonomy registered
 * with a default term has it on every site: at startup for each existing site (archived and deleted ones included, each
 * under its post lock), and when a site is created, the term is looked up by name (as a slug, then as a name), created
 * when missing, and named by the option {@code default_term_<taxonomy>}. A refused insert is logged and leaves the
 * option unset, as WordPress ignores it. WordPress makes that check on every request; a default term renamed while the
 * application runs is therefore looked up again only at the next startup.
 */
@ApplicationScoped
public class DefaultTerms {

    private static final Logger LOG = Logger.getLogger(DefaultTerms.class);

    /** The option naming a site's default category. */
    static final String DEFAULT_CATEGORY = "default_category";

    @Inject
    Taxonomies taxonomies;

    @Inject
    TermRepository terms;

    @Inject
    TermLookups lookups;

    @Inject
    TermWrites writes;

    @Inject
    OptionWrites options;

    @Inject
    SiteRepository sites;

    @Inject
    SiteScope scope;

    @Inject
    PostLocks locks;

    @Inject
    SiteContext context;

    @Inject
    TransactionSynchronizationRegistry registry;

    /**
     * The name of the option that holds a taxonomy's default term: {@code default_category} for categories,
     * {@code default_term_<key>} for another taxonomy with a default term, and null for a taxonomy without one. This is
     * the single place that names the option; reads and writes of the default term go through it.
     */
    public static String optionOf(TaxonomyDefinition taxonomy) {
        if (taxonomy.key().equals("category")) {
            return DEFAULT_CATEGORY;
        }
        return taxonomy.defaultTerm().isPresent() ? "default_term_" + taxonomy.key() : null;
    }

    /**
     * The taxonomy's default term on {@code site} now: the term its option names, when that is a term of the taxonomy
     * on the site; empty for a taxonomy without default term, an option that is missing, not a positive number or names
     * no term of the taxonomy. The option is read through the current site's options, so the caller is bound to
     * {@code site}.
     */
    public Optional<TermId> current(SiteId site, TaxonomyDefinition taxonomy) {
        String option = optionOf(taxonomy);
        if (option == null) {
            return Optional.empty();
        }
        if (!context.siteId().equals(site)) {
            throw new IllegalStateException("the default term of site " + site.value() + " is read bound to site "
                    + context.siteId().value());
        }
        long id = context.options().number(option, 0);
        if (id <= 0) {
            return Optional.empty();
        }
        return lookups.find(site, TermId.of(id), taxonomy.key()).map(term -> TermId.of(term.id));
    }

    /** A new site's default category and default terms, inside its creation transaction (bound to the network). */
    void siteCreated(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) SiteCreated event) {
        SiteId site = event.site();
        TermEntity uncategorized = TermEntity.create(site, "category");
        uncategorized.name = "Uncategorized";
        uncategorized.slug = "uncategorized";
        terms.persist(uncategorized);
        terms.flush();
        options.set(site, DEFAULT_CATEGORY, LongNode.valueOf(uncategorized.id), null);
        for (TaxonomyDefinition taxonomy : taxonomies.all()) {
            if (taxonomy.defaultTerm().isPresent()) {
                ensure(site, taxonomy);
            }
        }
    }

    /** At startup, every existing site gets the default terms of the taxonomies that have one. */
    void started(@Observes @Priority(Interceptor.Priority.LIBRARY_AFTER) StartupEvent start) {
        ensureEverySite();
    }

    /**
     * Every existing site, archived and deleted ones included, gets the default terms of the taxonomies that have one:
     * each site in a transaction of its own, under its post lock. Called outside any site scope.
     */
    public void ensureEverySite() {
        List<TaxonomyDefinition> withDefaults = taxonomies.all().stream()
                .filter(taxonomy -> taxonomy.defaultTerm().isPresent())
                .toList();
        if (withDefaults.isEmpty()) {
            return;
        }
        List<SiteId> all = QuarkusTransaction.requiringNew()
                .call(() -> sites.findAll(Sort.by("id")).list().stream()
                        .map(site -> SiteId.of(site.id))
                        .toList());
        for (SiteId site : all) {
            scope.run(site, () -> {
                locks.lock(site);
                withDefaults.forEach(taxonomy -> ensure(site, taxonomy));
            });
        }
    }

    /**
     * The taxonomy's default term on {@code site}: found by name, or created; the option naming it is set when it named
     * another id. An insert refused before it writes (a name the save filters empty, a value too long for its column, a
     * {@code TermWriteFilter}'s refusal) is logged and leaves the option as it was, as WordPress ignores the failure;
     * then the answer is empty. A failure after the first write propagates. The caller is bound to the site (holding its
     * post lock) or to the network while the site is created.
     */
    public Optional<TermId> ensure(SiteId site, TaxonomyDefinition taxonomy) {
        TaxonomyDefinition.DefaultTerm term = taxonomy.defaultTerm()
                .orElseThrow(() -> new IllegalArgumentException("taxonomy " + taxonomy.key() + " has no default term"));
        Optional<TermEntity> found = lookups.exists(site, taxonomy.key(), term.name(), OptionalLong.empty());
        TermId id;
        if (found.isPresent()) {
            id = TermId.of(found.get().id);
        } else {
            try {
                id = writes.insert(
                                site,
                                taxonomy,
                                TermChanges.builder()
                                        .name(term.name())
                                        .slug(term.slug())
                                        .description(term.description())
                                        .build())
                        .id();
            } catch (TermException refused) {
                if (registry.getRollbackOnly()) {
                    // refused after the first write: the transaction is already marked, so the failure stands
                    throw refused;
                }
                LOG.warnf(
                        "the default term '%s' of taxonomy %s was not created on site %d: %s (%s)",
                        term.name(), taxonomy.key(), site.value(), refused.getMessage(), refused.code());
                return Optional.empty();
            }
        }
        options.set(site, optionOf(taxonomy), LongNode.valueOf(id.value()), null);
        return Optional.of(id);
    }
}
