package org.quarkpress.it.media;

import jakarta.ws.rs.Path;

/**
 * The media resource of {@link ItMediaResource} for a site in a subdirectory: {@code /<site>/it/media}. The request's
 * path binds that site (the network resolves the site from the path before any resource runs), so every operation
 * acts on it; the path segment itself is not read.
 */
@Path("/{site}/it/media")
public class ItSiteMediaResource extends ItMediaResource {}
