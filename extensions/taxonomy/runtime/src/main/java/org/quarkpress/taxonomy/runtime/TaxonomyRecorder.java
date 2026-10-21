package org.quarkpress.taxonomy.runtime;

import io.quarkus.runtime.annotations.Recorder;
import java.util.function.Supplier;
import org.quarkpress.taxonomy.Taxonomies;

@Recorder
public class TaxonomyRecorder {

    /** The taxonomies, recorded at build time in their JSON form. */
    public Supplier<Taxonomies> taxonomies(String json) {
        return () -> new RegisteredTaxonomies(TaxonomyJson.read(json));
    }
}
