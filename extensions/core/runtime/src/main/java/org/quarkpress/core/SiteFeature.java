package org.quarkpress.core;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated code only when the per-site feature is enabled for the current site. When it is disabled (or no
 * site is resolved, or there is no request context): REST resource methods answer 404, event observer methods do
 * nothing, filter methods return their first argument unchanged, and other methods throw
 * {@link SiteFeatureDisabledException}. The site is read on every call.
 *
 * <p>On a class it applies to the non-private, non-static methods declared in that class and to the public methods
 * (and observer methods) it inherits from superclasses, and to the default methods it inherits from interfaces;
 * {@code equals}, {@code hashCode} and {@code toString} are never
 * gated, and a method's own annotation wins. The annotation is not inherited: a subclass of a gated class must carry
 * its own.
 *
 * <p>The build fails when the key is not declared by an installed module, and for placements that could not be
 * enforced: interfaces, annotation types (stereotypes), abstract interface methods (default methods are allowed),
 * private or static methods, producer and disposer methods or producer fields, {@code @PostConstruct} and
 * {@code @PreDestroy} methods, an unannotated override of a gated method (also one that overrides through a generic
 * bridge), a filter method that does not return the type of its first parameter, and generic filter methods.
 */
@InterceptorBinding
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SiteFeature {

    /** The feature key. */
    String value();
}
