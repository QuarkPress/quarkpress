package org.quarkpress.taxonomy.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.security.TaxonomyCapabilities;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermException;

/** The taxonomies of this build, with each taxonomy's capability names built once. */
public class RegisteredTaxonomies implements Taxonomies {

    private final List<TaxonomyDefinition> all;
    private final Map<String, TaxonomyDefinition> byKey = new LinkedHashMap<>();
    private final Map<String, TaxonomyCapabilities> capabilities = new LinkedHashMap<>();

    public RegisteredTaxonomies(List<TaxonomyDefinition> taxonomies) {
        this.all = List.copyOf(taxonomies);
        for (TaxonomyDefinition taxonomy : all) {
            byKey.put(taxonomy.key(), taxonomy);
            capabilities.put(taxonomy.key(), TaxonomyCapabilities.of(taxonomy.key(), taxonomy.capabilities()));
        }
    }

    @Override
    public List<TaxonomyDefinition> all() {
        return all;
    }

    @Override
    public Optional<TaxonomyDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public boolean registered(String key) {
        return byKey.containsKey(key);
    }

    @Override
    public boolean hierarchical(String key) {
        TaxonomyDefinition taxonomy = byKey.get(key);
        return taxonomy != null && taxonomy.hierarchical();
    }

    @Override
    public List<TaxonomyDefinition> forType(String postType) {
        return all.stream().filter(taxonomy -> taxonomy.attachedTo(postType)).toList();
    }

    @Override
    public Optional<TaxonomyCapabilities> capabilities(String key) {
        return Optional.ofNullable(capabilities.get(key));
    }

    @Override
    public TaxonomyDefinition require(String key) {
        TaxonomyDefinition taxonomy = byKey.get(key);
        if (taxonomy == null) {
            throw TermException.invalidTaxonomy(key);
        }
        return taxonomy;
    }
}
