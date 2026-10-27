package org.quarkpress.devui.runtime;

import java.util.List;
import java.util.Map;
import org.quarkpress.devui.runtime.DevViews.TaxonomyView;

/**
 * The taxonomy module's part of the Dev UI's content reads, for the current site. A bean only when the taxonomy module
 * is installed; this interface names no type of that module, so the content provider can ask for it either way.
 */
public interface TaxonomyReads {

    /** The registered taxonomies, in registration order. */
    List<TaxonomyView> taxonomies();

    /** The number of terms of each registered taxonomy on the current site, by key, zero included. */
    Map<String, Long> termCounts();
}
