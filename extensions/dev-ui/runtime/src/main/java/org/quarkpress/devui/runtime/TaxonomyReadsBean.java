package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.devui.runtime.DevViews.TaxonomyView;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.Terms;

/** The content reads of the taxonomy module, through its services, as the caller (the Dev UI identity). */
public class TaxonomyReadsBean implements TaxonomyReads {

    @Inject
    Taxonomies taxonomies;

    @Inject
    Terms terms;

    @Override
    public List<TaxonomyView> taxonomies() {
        List<TaxonomyView> views = new ArrayList<>();
        for (TaxonomyDefinition taxonomy : taxonomies.all()) {
            views.add(new TaxonomyView(
                    taxonomy.key(),
                    taxonomy.labels().get("name"),
                    taxonomy.objectTypes(),
                    taxonomy.hierarchical(),
                    taxonomy.builtIn()));
        }
        return views;
    }

    @Override
    public Map<String, Long> termCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (TaxonomyDefinition taxonomy : taxonomies.all()) {
            counts.put(
                    taxonomy.key(),
                    terms.count(TermQuery.builder().taxonomies(taxonomy.key()).build()));
        }
        return counts;
    }
}
