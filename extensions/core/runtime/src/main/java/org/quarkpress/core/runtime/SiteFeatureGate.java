package org.quarkpress.core.runtime;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Added by the build to every method a method-level {@code @SiteFeature} covers, and to every class that carries a
 * class-level {@code @SiteFeature}. Not for application code.
 *
 * <p>On a method, {@link #key()} and {@link #kind()} describe that method. On a class, {@link #byClass()} is true and
 * {@link #methods()} lists the methods the class-level annotation covers (declared and inherited, as
 * {@code name(parameter,types)}) with their behaviour in {@link #kinds()} at the same index; an intercepted method
 * that is not listed is not gated.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SiteFeatureGate {

    @Nonbinding
    String key() default "";

    @Nonbinding
    GateKind kind() default GateKind.OTHER;

    @Nonbinding
    boolean byClass() default false;

    @Nonbinding
    String[] methods() default {};

    @Nonbinding
    GateKind[] kinds() default {};

    /** On a method: the position of its observed event parameter, or -1 when it is not an observer. */
    @Nonbinding
    int eventParameter() default -1;

    /** On a class: the observed parameter of each method in {@link #methods()}, -1 for methods that do not observe. */
    @Nonbinding
    int[] eventParameters() default {};
}
