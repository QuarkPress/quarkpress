package org.quarkpress.testing.wpscenario;

import org.quarkpress.taxonomy.Taxonomy;

/**
 * The custom taxonomy of the term scenarios, registered as the PHP driver registers it on WordPress's side: genre, a
 * hierarchical taxonomy on post with the default term General.
 */
@Taxonomy(key = "genre", objectTypes = "post", hierarchical = true, defaultTerm = "General")
public class TierBTaxonomies {}
