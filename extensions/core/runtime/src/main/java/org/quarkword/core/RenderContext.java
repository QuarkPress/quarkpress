package org.quarkword.core;

/** What rendering filters know about the rendering in progress. Later versions add methods with defaults. */
public interface RenderContext {

    SiteId site();
}
