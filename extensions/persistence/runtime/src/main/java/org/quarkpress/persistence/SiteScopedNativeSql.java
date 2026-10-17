package org.quarkpress.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allows a class to run native SQL on site-scoped QuarkPress tables. Native SQL is not filtered by site, so every such
 * statement must take the site explicitly ({@code where site_id = ?}). Without this annotation the build fails when a
 * QuarkPress class passes SQL naming a site-scoped table to {@code createNativeQuery} or
 * {@code createNativeMutationQuery}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface SiteScopedNativeSql {}
