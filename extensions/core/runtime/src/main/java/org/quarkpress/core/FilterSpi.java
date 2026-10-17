package org.quarkpress.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a filter interface: implementations are applied in a chain, each receiving the previous result as the first
 * argument and returning a value of the same type. The {@code @SiteFeature} annotation provides the contract that a filter method whose
 * feature is disabled passes its first argument through unchanged.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FilterSpi {}
