package org.quarkpress.taxonomy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a taxonomy when put on any class of the application (WordPress's {@code register_taxonomy}). An attribute
 * not given takes WordPress's default: a taxonomy is public; {@code showUi} follows {@code publicly}; labels are
 * derived from {@code name} and {@code singularName}. {@code labels} adds labels as {@code key=value} entries;
 * {@code defaultTerm} names the term every post of an attached type gets when it has none of this taxonomy, and that
 * term cannot be deleted. A key registered twice, the built-in {@code category} and {@code post_tag} included, fails
 * the build. An extension that needs every argument (capability names, every interface flag) registers a
 * {@code org.quarkpress.core.deployment.spi.TaxonomyBuildItem} instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Taxonomy {

    /** The taxonomy's name: 1 to 32 bytes, used as given. */
    String key();

    /** The post types whose posts take terms of this taxonomy. */
    String[] objectTypes() default {};

    /** The {@code name} label (plural), if given. */
    String name() default "";

    /** The {@code singular_name} label, if given. */
    String singularName() default "";

    String description() default "";

    boolean hierarchical() default false;

    boolean publicly() default true;

    /** Whether the admin shows the taxonomy; not given, the same as {@code publicly}. */
    boolean showUi() default true;

    /** The REST base; given, the taxonomy shows in REST. */
    String restBase() default "";

    /** More labels, each {@code key=value}. */
    String[] labels() default {};

    /** The name of the taxonomy's default term; empty for none. */
    String defaultTerm() default "";

    /** Whether assignments keep the order terms are given in. */
    boolean sort() default false;
}
