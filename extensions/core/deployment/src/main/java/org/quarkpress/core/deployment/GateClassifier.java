package org.quarkpress.core.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.PrimitiveType;
import org.jboss.jandex.Type;
import org.quarkpress.core.FilterSpi;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.runtime.GateKind;
import org.quarkpress.core.runtime.SiteFeatureGate;

/**
 * Finds the methods {@code @SiteFeature} covers and decides how each behaves when its feature is off. Placements that
 * cannot be gated (interfaces, annotation types, abstract interface methods, private or static methods, producers and
 * disposers, lifecycle callbacks, unannotated overrides and subclasses) are reported as problems so the build fails instead of leaving the
 * code ungated.
 */
public final class GateClassifier {

    public static final DotName SITE_FEATURE = DotName.createSimple(SiteFeature.class);
    public static final DotName GATE = DotName.createSimple(SiteFeatureGate.class);
    static final DotName FILTER_SPI = DotName.createSimple(FilterSpi.class);
    static final DotName OBJECT = DotName.createSimple(Object.class);
    static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");
    static final List<DotName> RESOURCE_METHOD_ANNOTATIONS = List.of(
            PATH,
            DotName.createSimple("jakarta.ws.rs.GET"),
            DotName.createSimple("jakarta.ws.rs.POST"),
            DotName.createSimple("jakarta.ws.rs.PUT"),
            DotName.createSimple("jakarta.ws.rs.DELETE"),
            DotName.createSimple("jakarta.ws.rs.PATCH"),
            DotName.createSimple("jakarta.ws.rs.HEAD"),
            DotName.createSimple("jakarta.ws.rs.OPTIONS"));
    static final DotName OBSERVES = DotName.createSimple("jakarta.enterprise.event.Observes");
    static final DotName OBSERVES_ASYNC = DotName.createSimple("jakarta.enterprise.event.ObservesAsync");
    static final DotName PRODUCES = DotName.createSimple("jakarta.enterprise.inject.Produces");
    static final DotName DISPOSES = DotName.createSimple("jakarta.enterprise.inject.Disposes");
    static final DotName POST_CONSTRUCT = DotName.createSimple("jakarta.annotation.PostConstruct");
    static final DotName PRE_DESTROY = DotName.createSimple("jakarta.annotation.PreDestroy");

    /**
     * A gated method. {@code coveredBy} is the class whose class-level annotation covers the method (the method may be
     * inherited from a superclass of it), or null when the method carries its own annotation.
     */
    public record Gate(MethodInfo method, String key, GateKind kind, ClassInfo coveredBy) {

        public boolean byClass() {
            return coveredBy != null;
        }
    }

    /** The gates of an index and the placement problems found while collecting them. */
    public record Classification(List<Gate> gates, List<String> problems) {}

    private GateClassifier() {}

    public static List<Gate> gates(IndexView index) {
        return classify(index).gates();
    }

    public static Classification classify(IndexView index) {
        List<Gate> gates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(SITE_FEATURE)) {
            String key = annotation.value().asString();
            AnnotationTarget target = annotation.target();
            if (target.kind() == AnnotationTarget.Kind.METHOD) {
                methodGate(target.asMethod(), key, index, gates, problems);
            } else if (target.kind() == AnnotationTarget.Kind.CLASS) {
                classGates(target.asClass(), key, index, gates, problems);
            }
        }
        return new Classification(gates, problems);
    }

    public static List<String> problems(List<Gate> gates, Set<String> declaredKeys) {
        List<String> problems = new ArrayList<>();
        for (Gate gate : gates) {
            if (!declaredKeys.contains(gate.key())) {
                problems.add("@SiteFeature(\"" + gate.key() + "\") on " + where(gate.method())
                        + " names a feature that no installed module declares; declared features: "
                        + declaredKeys);
            }
            if (gate.kind() == GateKind.FILTER && !returnsFirstParameterType(gate.method())) {
                problems.add("@SiteFeature on filter method " + where(gate.method())
                        + " must return the type of its first parameter");
            }
        }
        return problems;
    }

    /** A stable text key for a method, used to match methods across index views. */
    public static String methodKey(MethodInfo method) {
        return method.declaringClass().name() + "#" + methodId(method);
    }

    /**
     * {@code name(parameter,types)} with erased parameter type names spelled like {@link Class#getName()}, matching
     * what the interceptor computes from the invoked method.
     */
    public static String methodId(MethodInfo method) {
        StringJoiner parameters = new StringJoiner(",", method.name() + "(", ")");
        for (Type type : method.parameterTypes()) {
            parameters.add(runtimeName(type));
        }
        return parameters.toString();
    }

    private static void methodGate(
            MethodInfo method, String key, IndexView index, List<Gate> gates, List<String> problems) {
        ClassInfo owner = method.declaringClass();
        String where = where(method);
        if (Modifier.isInterface(owner.flags()) && Modifier.isAbstract(method.flags())) {
            problems.add("@SiteFeature on abstract interface method " + where
                    + " cannot gate anything; annotate the implementing method (default methods may be gated)");
            return;
        }
        if (Modifier.isPrivate(method.flags())) {
            problems.add(
                    "@SiteFeature on private method " + where + " cannot gate it: private methods are not intercepted");
            return;
        }
        if (Modifier.isStatic(method.flags())) {
            problems.add(
                    "@SiteFeature on static method " + where + " cannot gate it: static methods are not intercepted");
            return;
        }
        if (producerOrDisposerProblem(method, null, problems)) {
            return;
        }
        if (lifecycleCallback(method)) {
            problems.add("@SiteFeature cannot gate lifecycle callback " + where
                    + ": @PostConstruct and @PreDestroy methods are not intercepted as business methods");
            return;
        }
        GateKind kind = kindOf(method, owner, index, problems);
        gates.add(new Gate(method, key, kind, null));
        boolean generic = hasTypeVariable(method);
        for (ClassInfo sub : subtypes(owner, index)) {
            if (sub.hasDeclaredAnnotation(SITE_FEATURE)) {
                continue;
            }
            for (MethodInfo candidate : sub.methods()) {
                if (overrides(candidate, method, generic) && !candidate.hasDeclaredAnnotation(SITE_FEATURE)) {
                    problems.add(where(candidate) + " overrides " + where
                            + ", which @SiteFeature gates, but is not annotated; annotate the override (or its class)"
                            + " with @SiteFeature");
                }
            }
        }
    }

    /**
     * Whether {@code candidate}, declared in a subtype, overrides {@code gated}. Erased parameter types are compared,
     * so type annotations and raw or parameterized spellings do not hide an override. When the gated method has
     * type-variable parameters a subtype may override it with concrete types (through a bridge method), so every
     * method with the same name and parameter count counts.
     */
    private static boolean overrides(MethodInfo candidate, MethodInfo gated, boolean generic) {
        if (candidate.isSynthetic()
                || candidate.isConstructor()
                || Modifier.isStatic(candidate.flags())
                || Modifier.isPrivate(candidate.flags())
                || !candidate.name().equals(gated.name())
                || candidate.parametersCount() != gated.parametersCount()) {
            return false;
        }
        return generic || sameParameterTypes(candidate, gated);
    }

    private static boolean hasTypeVariable(MethodInfo method) {
        for (Type type : method.parameterTypes()) {
            Type element = type;
            while (element.kind() == Type.Kind.ARRAY) {
                element = element.asArrayType().componentType();
            }
            if (element.kind() == Type.Kind.TYPE_VARIABLE
                    || element.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE
                    || element.kind() == Type.Kind.TYPE_VARIABLE_REFERENCE) {
                return true;
            }
        }
        return false;
    }

    private static void classGates(
            ClassInfo type, String key, IndexView index, List<Gate> gates, List<String> problems) {
        if (Modifier.isInterface(type.flags())) {
            if (type.isAnnotation()) {
                problems.add("@SiteFeature on annotation type " + type.name()
                        + " is not supported: stereotypes and binding annotations are not checked; annotate the"
                        + " bean class or method");
            } else {
                problems.add("@SiteFeature on interface " + type.name()
                        + " cannot gate anything: interface types are not intercepted; annotate the implementing"
                        + " bean class or its methods");
            }
            return;
        }
        for (FieldInfo field : type.fields()) {
            if (field.hasDeclaredAnnotation(PRODUCES)) {
                problems.add("@SiteFeature cannot gate producer field " + type.name() + "#" + field.name()
                        + " (covered by the class-level @SiteFeature on " + type.name()
                        + "): the produced instance would outlive the site that created it");
            }
        }
        for (ClassInfo sub : index.getAllKnownSubclasses(type.name())) {
            if (!sub.hasDeclaredAnnotation(SITE_FEATURE)) {
                problems.add(sub.name() + " extends " + type.name()
                        + ", whose class-level @SiteFeature is not inherited; annotate " + sub.name()
                        + " with @SiteFeature");
            }
        }
        Set<String> seen = new HashSet<>();
        ClassInfo current = type;
        boolean declared = true;
        while (current != null) {
            for (MethodInfo method : current.methods()) {
                if (method.isConstructor() || method.isStaticInitializer() || method.isSynthetic()) {
                    continue;
                }
                if (Modifier.isStatic(method.flags()) || objectMethod(method) || lifecycleCallback(method)) {
                    continue;
                }
                String id = methodId(method);
                boolean overridden = !seen.add(id) && !Modifier.isPrivate(method.flags());
                if (overridden || Modifier.isAbstract(method.flags()) || method.hasDeclaredAnnotation(SITE_FEATURE)) {
                    continue;
                }
                if (Modifier.isPrivate(method.flags())) {
                    if (observes(method)) {
                        problems.add("@SiteFeature cannot gate private observer method " + where(method)
                                + " (covered by the class-level @SiteFeature on " + type.name()
                                + "): private methods are not intercepted");
                    }
                    continue;
                }
                if (declared && producerOrDisposerProblem(method, type, problems)) {
                    continue;
                }
                if (!declared && !Modifier.isPublic(method.flags()) && !observes(method)) {
                    continue;
                }
                if (!declared && (method.hasDeclaredAnnotation(PRODUCES) || disposes(method))) {
                    continue;
                }
                gates.add(new Gate(method, key, kindOf(method, type, index, problems), type));
            }
            DotName superName = current.superName();
            if (superName == null || superName.equals(OBJECT)) {
                break;
            }
            ClassInfo superclass = index.getClassByName(superName);
            if (superclass == null) {
                problems.add("superclass " + superName + " of " + type.name()
                        + " is not in the bean archive index, so the class-level @SiteFeature cannot classify the"
                        + " methods it inherits; add an index to its archive or annotate the methods");
            }
            current = superclass;
            declared = false;
        }
        defaultMethodGates(type, key, index, seen, gates, problems);
    }

    /**
     * Covers the default methods a class-gated class inherits from the interfaces of the class and its superclasses,
     * unless a class in the hierarchy declares the method. JDK interfaces are not indexed and are skipped.
     */
    private static void defaultMethodGates(
            ClassInfo type, String key, IndexView index, Set<String> seen, List<Gate> gates, List<String> problems) {
        Deque<DotName> pending = new ArrayDeque<>();
        for (ClassInfo current = type; current != null; ) {
            pending.addAll(current.interfaceNames());
            DotName superName = current.superName();
            current = superName == null || superName.equals(OBJECT) ? null : index.getClassByName(superName);
        }
        Set<DotName> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            DotName name = pending.removeFirst();
            if (!visited.add(name)) {
                continue;
            }
            ClassInfo spi = index.getClassByName(name);
            if (spi == null) {
                if (!name.toString().startsWith("java.")) {
                    problems.add("superinterface " + name + " of " + type.name()
                            + " is not in the bean archive index, so the class-level @SiteFeature cannot classify the"
                            + " default methods it inherits; add an index to its archive or annotate the methods");
                }
                continue;
            }
            for (MethodInfo method : spi.methods()) {
                if (method.isSynthetic()
                        || method.isStaticInitializer()
                        || Modifier.isAbstract(method.flags())
                        || Modifier.isStatic(method.flags())
                        || Modifier.isPrivate(method.flags())
                        || method.hasDeclaredAnnotation(SITE_FEATURE)) {
                    continue;
                }
                if (seen.add(methodId(method))) {
                    gates.add(new Gate(method, key, kindOf(method, type, index, problems), type));
                }
            }
            pending.addAll(spi.interfaceNames());
        }
    }

    private static boolean producerOrDisposerProblem(
            MethodInfo method, ClassInfo coveringClass, List<String> problems) {
        String covered = coveringClass == null
                ? ""
                : " (covered by the class-level @SiteFeature on " + coveringClass.name() + ")";
        if (method.hasDeclaredAnnotation(PRODUCES)) {
            problems.add("@SiteFeature cannot gate producer method " + where(method) + covered
                    + ": the produced instance would outlive the site that created it");
            return true;
        }
        if (disposes(method)) {
            problems.add("@SiteFeature cannot gate disposer method " + where(method) + covered
                    + ": disposal must run for every produced instance");
            return true;
        }
        return false;
    }

    static GateKind kindOf(MethodInfo method, ClassInfo beanClass, IndexView index, List<String> problems) {
        if (observes(method)) {
            return GateKind.OBSERVER;
        }
        if (implementsFilterMethod(method, beanClass, index, problems)) {
            if (!method.typeParameters().isEmpty()
                    || method.returnType().kind() == Type.Kind.TYPE_VARIABLE
                    || method.parameterType(0).kind() == Type.Kind.TYPE_VARIABLE) {
                problems.add("@SiteFeature on filter method " + where(method)
                        + ": generic filter methods are not supported");
            }
            return GateKind.FILTER;
        }
        if (isResource(method, beanClass, index)) {
            return GateKind.RESOURCE;
        }
        return GateKind.OTHER;
    }

    /**
     * Whether a gated method answers HTTP requests: it carries a JAX-RS path or verb annotation, or the bean class
     * (or a superclass or interface of it) is a {@code @Path} resource. Methods inherited under a class-level gate
     * are judged by the covering class.
     */
    private static boolean isResource(MethodInfo method, ClassInfo beanClass, IndexView index) {
        for (DotName name : RESOURCE_METHOD_ANNOTATIONS) {
            if (method.hasDeclaredAnnotation(name)) {
                return true;
            }
        }
        Deque<ClassInfo> pending = new ArrayDeque<>();
        Set<DotName> seen = new HashSet<>();
        pending.push(beanClass);
        while (!pending.isEmpty()) {
            ClassInfo current = pending.pop();
            if (!seen.add(current.name())) {
                continue;
            }
            if (current.hasDeclaredAnnotation(PATH)) {
                return true;
            }
            List<DotName> parents = new ArrayList<>(current.interfaceNames());
            if (current.superName() != null) {
                parents.add(current.superName());
            }
            for (DotName name : parents) {
                ClassInfo parent = index.getClassByName(name);
                if (parent != null) {
                    pending.push(parent);
                }
            }
        }
        return false;
    }

    private static boolean objectMethod(MethodInfo method) {
        return switch (method.name()) {
            case "equals" ->
                method.parametersCount() == 1 && method.parameterType(0).name().equals(OBJECT);
            case "hashCode", "toString" -> method.parametersCount() == 0;
            default -> false;
        };
    }

    private static boolean lifecycleCallback(MethodInfo method) {
        return method.hasDeclaredAnnotation(POST_CONSTRUCT) || method.hasDeclaredAnnotation(PRE_DESTROY);
    }

    private static boolean observes(MethodInfo method) {
        return hasParameterAnnotation(method, OBSERVES) || hasParameterAnnotation(method, OBSERVES_ASYNC);
    }

    private static boolean disposes(MethodInfo method) {
        return hasParameterAnnotation(method, DISPOSES);
    }

    private static boolean hasParameterAnnotation(MethodInfo method, DotName name) {
        for (AnnotationInstance annotation : method.annotations(name)) {
            if (annotation.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
                return true;
            }
        }
        return false;
    }

    private static boolean implementsFilterMethod(
            MethodInfo method, ClassInfo beanClass, IndexView index, List<String> problems) {
        boolean matched = false;
        for (ClassInfo spi : filterInterfaces(beanClass, index)) {
            for (MethodInfo candidate : spi.methods()) {
                if (!candidate.name().equals(method.name())
                        || candidate.parametersCount() != method.parametersCount()
                        || Modifier.isStatic(candidate.flags())) {
                    continue;
                }
                if (generic(candidate)) {
                    problems.add("@SiteFeature on " + where(method) + " matches filter method " + where(candidate)
                            + ": generic filter methods are not supported");
                    continue;
                }
                if (sameParameterTypes(candidate, method)) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean generic(MethodInfo method) {
        if (!method.typeParameters().isEmpty() || method.returnType().kind() == Type.Kind.TYPE_VARIABLE) {
            return true;
        }
        for (Type type : method.parameterTypes()) {
            if (type.kind() == Type.Kind.TYPE_VARIABLE) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameParameterTypes(MethodInfo a, MethodInfo b) {
        for (int i = 0; i < a.parametersCount(); i++) {
            if (!a.parameterType(i).name().equals(b.parameterType(i).name())) {
                return false;
            }
        }
        return true;
    }

    private static List<ClassInfo> filterInterfaces(ClassInfo type, IndexView index) {
        List<ClassInfo> found = new ArrayList<>();
        Set<DotName> seen = new HashSet<>();
        Deque<ClassInfo> pending = new ArrayDeque<>();
        pending.push(type);
        while (!pending.isEmpty()) {
            ClassInfo current = pending.pop();
            if (!seen.add(current.name())) {
                continue;
            }
            if (Modifier.isInterface(current.flags()) && current.hasDeclaredAnnotation(FILTER_SPI)) {
                found.add(current);
            }
            for (DotName name : current.interfaceNames()) {
                ClassInfo info = index.getClassByName(name);
                if (info != null) {
                    pending.push(info);
                }
            }
            if (current.superName() != null) {
                ClassInfo superclass = index.getClassByName(current.superName());
                if (superclass != null) {
                    pending.push(superclass);
                }
            }
        }
        return found;
    }

    /** Classes that extend a class, or classes that implement an interface (directly or not). */
    private static List<ClassInfo> subtypes(ClassInfo type, IndexView index) {
        if (Modifier.isInterface(type.flags())) {
            return List.copyOf(index.getAllKnownImplementors(type.name()));
        }
        return List.copyOf(index.getAllKnownSubclasses(type.name()));
    }

    private static boolean returnsFirstParameterType(MethodInfo method) {
        return method.parametersCount() > 0
                && method.returnType().name().equals(method.parameterType(0).name());
    }

    /** The erased type name as {@link Class#getName()} spells it. */
    private static String runtimeName(Type type) {
        if (type.kind() != Type.Kind.ARRAY) {
            return type.name().toString();
        }
        StringBuilder name = new StringBuilder();
        Type element = type;
        while (element.kind() == Type.Kind.ARRAY) {
            name.append('[');
            element = element.asArrayType().componentType();
        }
        if (element.kind() == Type.Kind.PRIMITIVE) {
            name.append(descriptor(element.asPrimitiveType().primitive()));
        } else {
            name.append('L').append(element.name()).append(';');
        }
        return name.toString();
    }

    private static char descriptor(PrimitiveType.Primitive primitive) {
        return switch (primitive) {
            case BOOLEAN -> 'Z';
            case BYTE -> 'B';
            case CHAR -> 'C';
            case DOUBLE -> 'D';
            case FLOAT -> 'F';
            case INT -> 'I';
            case LONG -> 'J';
            case SHORT -> 'S';
        };
    }

    private static String where(MethodInfo method) {
        return method.declaringClass().name() + "#" + method.name();
    }
}
