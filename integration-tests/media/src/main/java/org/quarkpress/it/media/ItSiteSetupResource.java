package org.quarkpress.it.media;

import jakarta.ws.rs.Path;

/**
 * The setup resource of {@link ItSetupResource} for a site in a subdirectory: {@code /<site>/it/...} (roles, posts and
 * post meta on that site, for the media parity run). The request's path binds that site (the network resolves the site
 * from the path before any resource runs), so every operation acts on it; the path segment itself is not read.
 */
@Path("/{site}/it")
public class ItSiteSetupResource extends ItSetupResource {}
