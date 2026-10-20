package org.quarkpress.posts;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a post type when put on any class of the application (WordPress's {@code register_post_type}). An
 * attribute not given takes WordPress's default, derived as for a registration that leaves that argument out:
 * {@code supports} empty means title, editor and autosave; {@code excludeFromSearch} and {@code showUi} follow
 * {@code publicly}; {@code mapMetaCap} is on for the {@code post} and {@code page} capability types. {@code labels}
 * adds labels as {@code key=value} entries. A key registered twice fails the build.
 *
 * <p>The annotation covers the arguments most types need. An extension that needs every argument WordPress takes
 * (capability names, menu and REST flags, labels as a map and so on) registers a
 * {@code org.quarkpress.core.deployment.spi.PostTypeBuildItem} instead, which gets the same results.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PostType {

    String key();

    /** The {@code name} label (plural), if given. */
    String name() default "";

    /** The {@code singular_name} label, if given. */
    String singularName() default "";

    /** The type's description. */
    String description() default "";

    boolean hierarchical() default false;

    boolean publicly() default false;

    /** Whether a query for "any" type leaves the type out; not given, the opposite of {@code publicly}. */
    boolean excludeFromSearch() default false;

    /** Whether the admin shows the type; not given, the same as {@code publicly}. */
    boolean showUi() default false;

    /** Whether the type has an archive. */
    boolean hasArchive() default false;

    String[] supports() default {};

    /** The capability type: capability names are built from it ({@code edit_book}, {@code edit_books}, ...). */
    String capabilityType() default "post";

    /**
     * Whether checks of the type's meta capabilities ({@code edit_book}, ...) map as the core ones ({@code edit_post},
     * ...); not given, on only for the {@code post} and {@code page} capability types.
     */
    boolean mapMetaCap() default false;

    /** The REST base; given, the type shows in REST. */
    String restBase() default "";

    /** More labels, each {@code key=value}. */
    String[] labels() default {};
}
