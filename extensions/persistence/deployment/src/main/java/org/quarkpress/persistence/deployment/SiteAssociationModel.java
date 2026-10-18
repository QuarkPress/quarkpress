package org.quarkpress.persistence.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

/**
 * Finds the associations between site-scoped entities, so the listener can check that a row refers only to rows of
 * its own site, and rejects every mapping it could not check. Only shapes whose database side is a composite foreign
 * key are allowed: a to-one association on a field with join columns, and the inverse side of one. A target that may
 * be site-scoped (a site-scoped entity, a generic type, {@code Object}, an interface, an array) in any other shape
 * fails the build.
 */
final class SiteAssociationModel {

    static final DotName SITE_SCOPED = SiteIsolationModel.SITE_SCOPED;
    static final DotName MANY_TO_ONE = DotName.createSimple("jakarta.persistence.ManyToOne");
    static final DotName ONE_TO_ONE = DotName.createSimple("jakarta.persistence.OneToOne");
    static final DotName MANY_TO_MANY = DotName.createSimple("jakarta.persistence.ManyToMany");
    static final DotName ONE_TO_MANY = DotName.createSimple("jakarta.persistence.OneToMany");
    static final DotName ELEMENT_COLLECTION = DotName.createSimple("jakarta.persistence.ElementCollection");
    static final DotName EMBEDDED = DotName.createSimple("jakarta.persistence.Embedded");
    static final DotName EMBEDDED_ID = DotName.createSimple("jakarta.persistence.EmbeddedId");
    static final DotName EMBEDDABLE = DotName.createSimple("jakarta.persistence.Embeddable");
    static final DotName JOIN_TABLE = DotName.createSimple("jakarta.persistence.JoinTable");
    static final DotName MAP_KEY_JOIN_COLUMN = DotName.createSimple("jakarta.persistence.MapKeyJoinColumn");
    static final DotName MAP_KEY_JOIN_COLUMNS = DotName.createSimple("jakarta.persistence.MapKeyJoinColumns");
    static final DotName MAP_KEY_CLASS = DotName.createSimple("jakarta.persistence.MapKeyClass");
    static final DotName ANY = DotName.createSimple("org.hibernate.annotations.Any");
    static final DotName MANY_TO_ANY = DotName.createSimple("org.hibernate.annotations.ManyToAny");
    static final DotName ASSOCIATION_OVERRIDE = DotName.createSimple("jakarta.persistence.AssociationOverride");
    static final DotName ASSOCIATION_OVERRIDES = DotName.createSimple("jakarta.persistence.AssociationOverrides");
    static final DotName TARGET_EMBEDDABLE = DotName.createSimple("org.hibernate.annotations.TargetEmbeddable");
    static final DotName TRANSIENT = DotName.createSimple("jakarta.persistence.Transient");
    static final Set<String> BASIC_KEYS = Set.of(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Character",
            "java.lang.Float",
            "java.lang.Double",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.UUID",
            "java.util.Date");
    static final Set<String> JDK_MAPS = Set.of(
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.SortedMap",
            "java.util.NavigableMap",
            "java.util.EnumMap",
            "java.util.IdentityHashMap",
            "java.util.WeakHashMap",
            "java.util.Hashtable",
            "java.util.Properties",
            "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.AbstractMap");
    static final DotName OBJECT = DotName.createSimple("java.lang.Object");
    static final DotName VOID = DotName.createSimple("void");

    private static final int SYNTHETIC = 0x1000;

    record Result(Map<String, List<String>> fieldsByClass, List<String> problems) {}

    /** A field, or a method seen as a property getter, of a class. */
    private record Member(ClassInfo owner, String name, Type type, FieldInfo field, MethodInfo method) {

        boolean getter() {
            return method != null;
        }

        AnnotationInstance annotation(DotName annotation) {
            return field != null ? field.annotation(annotation) : method.declaredAnnotation(annotation);
        }

        boolean has(DotName annotation) {
            return annotation(annotation) != null;
        }

        String label() {
            return owner.name() + "." + name + (getter() ? "()" : "");
        }
    }

    private final IndexView index;
    private final Set<DotName> scoped = new HashSet<>();

    private SiteAssociationModel(IndexView index) {
        this.index = index;
    }

    static Result analyse(IndexView index) {
        SiteAssociationModel model = new SiteAssociationModel(index);
        Collection<ClassInfo> entities = index.getAllKnownSubclasses(SITE_SCOPED);
        for (ClassInfo entity : entities) {
            model.scoped.add(entity.name());
        }
        Map<String, List<String>> fields = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (ClassInfo entity : entities) {
            if (joinTableOverride(
                    entity.declaredAnnotation(ASSOCIATION_OVERRIDE),
                    entity.declaredAnnotation(ASSOCIATION_OVERRIDES))) {
                problems.add("QuarkPress: " + entity.name() + " overrides an association with a join table"
                        + " (@AssociationOverride); a join table carries no site, so keep the join columns");
            }
            for (Member member : members(entity)) {
                String problem = model.check(member);
                if (problem != null) {
                    problems.add("QuarkPress: " + problem);
                } else if (model.recorded(member)) {
                    fields.computeIfAbsent(entity.name().toString(), name -> new ArrayList<>())
                            .add(member.name());
                }
            }
        }
        return new Result(fields, problems);
    }

    /** The mapped fields, and the methods that carry a mapping annotation (property access), of a class. */
    private static List<Member> members(ClassInfo type) {
        List<Member> members = new ArrayList<>();
        for (FieldInfo field : type.fields()) {
            if (!Modifier.isStatic(field.flags())
                    && !Modifier.isTransient(field.flags())
                    && !field.hasAnnotation(TRANSIENT)) {
                members.add(new Member(type, field.name(), field.type(), field, null));
            }
        }
        for (MethodInfo method : type.methods()) {
            if (!Modifier.isStatic(method.flags())
                    && (method.flags() & SYNTHETIC) == 0
                    && method.parametersCount() == 0
                    && !method.hasDeclaredAnnotation(TRANSIENT)
                    && mapped(method)) {
                members.add(new Member(type, method.name(), method.returnType(), null, method));
            }
        }
        return members;
    }

    private static boolean mapped(MethodInfo method) {
        for (AnnotationInstance annotation : method.declaredAnnotations()) {
            String name = annotation.name().toString();
            if (name.startsWith("jakarta.persistence.") || name.startsWith("org.hibernate.annotations.")) {
                return true;
            }
        }
        return false;
    }

    /** Whether a to-one association of this member is one the listener checks. */
    private boolean recorded(Member member) {
        if (member.getter()) {
            return false;
        }
        AnnotationInstance single = toOne(member);
        return single != null && !mappedBy(single) && scoped.contains(target(single, member.type()));
    }

    /** The reason the member is refused, or null. */
    private String check(Member member) {
        String label = member.label();
        if (member.has(ANY) || member.has(MANY_TO_ANY)) {
            return label + " maps an any-association (@Any or @ManyToAny) from a site-scoped entity; map it as a"
                    + " @ManyToOne field to a concrete site-scoped entity";
        }
        if (joinTableOverride(member.annotation(ASSOCIATION_OVERRIDE), member.annotation(ASSOCIATION_OVERRIDES))) {
            return joinTableProblem(member);
        }
        if (member.has(MAP_KEY_JOIN_COLUMN) || member.has(MAP_KEY_JOIN_COLUMNS) || mapKeyMayBeScoped(member)) {
            return label + " maps an association to a site-scoped entity as a map key; map it as a @ManyToOne field"
                    + " of the site-scoped entity";
        }
        AnnotationInstance single = toOne(member);
        if (single != null) {
            Type type = member.type();
            DotName target = target(single, type);
            if (!mayBeScoped(target, type)) {
                return null;
            }
            if (member.getter()) {
                return getterProblem(member);
            }
            if (!scoped.contains(target) || type.kind() == Type.Kind.ARRAY || isTypeVariable(type)) {
                return genericProblem(member);
            }
            if (member.has(JOIN_TABLE)) {
                return joinTableProblem(member);
            }
            return null;
        }
        AnnotationInstance many = member.annotation(MANY_TO_MANY);
        if (many == null) {
            many = member.annotation(ONE_TO_MANY);
        }
        if (many != null) {
            return checkToMany(member, many);
        }
        if (member.has(ELEMENT_COLLECTION)
                || member.has(EMBEDDED)
                || member.has(EMBEDDED_ID)
                || isEmbeddable(member.type())) {
            ClassInfo embeddable = embeddableOf(member);
            if (opaque(member, embeddable)) {
                return label + " maps an embeddable through @TargetEmbeddable or as an interface or abstract type,"
                        + " whose associations cannot be checked; use a concrete @Embeddable class";
            }
            if (embeddable != null && reachesSiteScoped(embeddable, new HashSet<>())) {
                return label + " maps an association to a site-scoped entity inside an embeddable; map it as a"
                        + " @ManyToOne field of the site-scoped entity";
            }
        }
        return null;
    }

    private String checkToMany(Member member, AnnotationInstance many) {
        Type type = member.type();
        Type element = Type.Kind.ARRAY == type.kind() || type.kind() == Type.Kind.PARAMETERIZED_TYPE
                ? SiteIsolationModel.rawElementType(type)
                : null;
        DotName target = elementTarget(many, type);
        if (!mayBeScoped(target, element)) {
            return null;
        }
        String label = member.label();
        if (member.getter()) {
            return getterProblem(member);
        }
        if (many.name().equals(MANY_TO_MANY)) {
            return label + " maps a many-to-many association between site-scoped entities; map the join table as a"
                    + " site-scoped entity with composite keys instead";
        }
        if (mappedBy(many)) {
            return element == null || !scoped.contains(target) || genericElement(element)
                    ? genericProblem(member)
                    : null;
        }
        return label + " maps an association to a site-scoped entity as a unidirectional one-to-many; map it as a"
                + " @ManyToOne field of the site-scoped entity";
    }

    private static String getterProblem(Member member) {
        return member.label() + " maps an association to a site-scoped entity on a getter; map associations between"
                + " site-scoped entities on fields so the listener can check their site";
    }

    private static String genericProblem(Member member) {
        return member.label() + " maps an association to a target that may be site-scoped but is not one concrete"
                + " site-scoped entity class (a type variable, wildcard, Object, interface or array); name the"
                + " concrete entity so the listener can check its site";
    }

    private static String joinTableProblem(Member member) {
        return member.label() + " maps an association to a site-scoped entity through a join table, which carries no"
                + " site; map it as a @ManyToOne field with join columns";
    }

    private static AnnotationInstance toOne(Member member) {
        AnnotationInstance single = member.annotation(MANY_TO_ONE);
        return single != null ? single : member.annotation(ONE_TO_ONE);
    }

    private static boolean mappedBy(AnnotationInstance mapping) {
        AnnotationValue mappedBy = mapping.value("mappedBy");
        return mappedBy != null && !mappedBy.asString().isEmpty();
    }

    private static boolean isTypeVariable(Type type) {
        return type.kind() == Type.Kind.TYPE_VARIABLE
                || type.kind() == Type.Kind.TYPE_VARIABLE_REFERENCE
                || type.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE
                || type.kind() == Type.Kind.WILDCARD_TYPE;
    }

    private static boolean genericElement(Type element) {
        return isTypeVariable(element) || element.kind() == Type.Kind.ARRAY;
    }

    /** Whether the target is a site-scoped entity, or a type one could be assigned to (checked fail-closed). */
    private boolean mayBeScoped(DotName target, Type declared) {
        if (declared != null && (isTypeVariable(declared) || declared.kind() == Type.Kind.ARRAY)) {
            return true;
        }
        return mayBeScoped(target);
    }

    private boolean mayBeScoped(DotName target) {
        if (target == null || scoped.contains(target) || target.equals(SITE_SCOPED) || target.equals(OBJECT)) {
            return true;
        }
        if (target.toString().startsWith("[")) {
            return true;
        }
        ClassInfo type = index.getClassByName(target);
        return type == null || Modifier.isInterface(type.flags());
    }

    /** A map key type that cannot be an entity: strings, numbers, enums, dates. */
    private boolean basicKey(Type key) {
        if (key.kind() == Type.Kind.PRIMITIVE) {
            return true;
        }
        String name = key.name().toString();
        if (BASIC_KEYS.contains(name) || name.startsWith("java.time.")) {
            return true;
        }
        ClassInfo type = index.getClassByName(key.name());
        return type != null && type.isEnum();
    }

    private boolean opaque(Member member, ClassInfo embeddable) {
        return member.has(TARGET_EMBEDDABLE)
                || embeddable != null
                        && (embeddable.hasDeclaredAnnotation(TARGET_EMBEDDABLE)
                                || Modifier.isInterface(embeddable.flags())
                                || Modifier.isAbstract(embeddable.flags()));
    }

    private static boolean joinTableOverride(AnnotationInstance single, AnnotationInstance many) {
        if (single != null && single.value("joinTable") != null) {
            return true;
        }
        if (many != null && many.value() != null) {
            for (AnnotationInstance override : many.value().asNestedArray()) {
                if (override.value("joinTable") != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private DotName target(AnnotationInstance mapping, Type type) {
        AnnotationValue explicit = mapping.value("targetEntity");
        if (explicit != null && !explicit.asClass().name().equals(VOID)) {
            return explicit.asClass().name();
        }
        return type.name();
    }

    private DotName elementTarget(AnnotationInstance mapping, Type type) {
        AnnotationValue explicit = mapping.value("targetEntity");
        if (explicit == null) {
            explicit = mapping.value("targetClass");
        }
        if (explicit != null && !explicit.asClass().name().equals(VOID)) {
            return explicit.asClass().name();
        }
        DotName element = SiteIsolationModel.elementType(type);
        return element != null ? element : type.kind() == Type.Kind.PARAMETERIZED_TYPE ? OBJECT : type.name();
    }

    /** A mapped map whose key may be an entity of a site (entity keys always use a key join column or a key class). */
    private boolean mapKeyMayBeScoped(Member member) {
        AnnotationInstance keyClass = member.annotation(MAP_KEY_CLASS);
        if (keyClass != null && !basicKeyName(keyClass.value().asClass().name())) {
            return mayBeScoped(keyClass.value().asClass().name());
        }
        Type type = member.type();
        if (!(member.has(ELEMENT_COLLECTION) || member.has(ONE_TO_MANY) || member.has(MANY_TO_MANY))
                || type.kind() != Type.Kind.PARAMETERIZED_TYPE
                || !isMap(type.name(), 0)) {
            return false;
        }
        List<Type> arguments = type.asParameterizedType().arguments();
        if (arguments.size() != 2) {
            return false;
        }
        Type key = arguments.get(0);
        if (basicKey(key)) {
            return false;
        }
        if (isTypeVariable(key) || mayBeScoped(key.name())) {
            return true;
        }
        ClassInfo keyType = index.getClassByName(key.name());
        return keyType != null && reachesSiteScoped(keyType, new HashSet<>());
    }

    private boolean basicKeyName(DotName name) {
        String text = name.toString();
        if (BASIC_KEYS.contains(text) || text.startsWith("java.time.")) {
            return true;
        }
        ClassInfo type = index.getClassByName(name);
        return type != null && type.isEnum();
    }

    /** Whether the type is a map: a known JDK map, or an indexed type that extends or implements one. */
    private boolean isMap(DotName name, int depth) {
        if (JDK_MAPS.contains(name.toString())) {
            return true;
        }
        ClassInfo type = index.getClassByName(name);
        if (type == null || depth > 32) {
            return false;
        }
        for (DotName parent : type.interfaceNames()) {
            if (isMap(parent, depth + 1)) {
                return true;
            }
        }
        return type.superName() != null && isMap(type.superName(), depth + 1);
    }

    private boolean isEmbeddable(Type type) {
        ClassInfo held = index.getClassByName(heldType(type));
        return held != null && held.hasDeclaredAnnotation(EMBEDDABLE);
    }

    /** The class an embedded member or element collection holds, or null. */
    private ClassInfo embeddableOf(Member member) {
        AnnotationInstance collection = member.annotation(ELEMENT_COLLECTION);
        DotName name = heldType(member.type());
        if (collection != null) {
            AnnotationValue explicit = collection.value("targetClass");
            if (explicit != null && !explicit.asClass().name().equals(VOID)) {
                name = explicit.asClass().name();
            }
        }
        return index.getClassByName(name);
    }

    private static DotName heldType(Type type) {
        Type element = SiteIsolationModel.rawElementType(type);
        if (element != null && element.kind() == Type.Kind.WILDCARD_TYPE) {
            element = element.asWildcardType().extendsBound();
        }
        return element != null ? element.name() : type.name();
    }

    /**
     * Whether an embeddable (with its superclasses, subclasses, fields and getters, and the embeddables nested in it)
     * maps an association that may reach a site-scoped entity.
     */
    private boolean reachesSiteScoped(ClassInfo embeddable, Set<DotName> visited) {
        if (!visited.add(embeddable.name())) {
            return false;
        }
        if (joinTableOverride(
                embeddable.declaredAnnotation(ASSOCIATION_OVERRIDE),
                embeddable.declaredAnnotation(ASSOCIATION_OVERRIDES))) {
            return true;
        }
        for (Member member : members(embeddable)) {
            if (joinTableOverride(member.annotation(ASSOCIATION_OVERRIDE), member.annotation(ASSOCIATION_OVERRIDES))
                    || member.has(ANY)
                    || member.has(MANY_TO_ANY)
                    || member.has(MAP_KEY_JOIN_COLUMN)
                    || member.has(MAP_KEY_JOIN_COLUMNS)) {
                return true;
            }
            AnnotationInstance single = toOne(member);
            if (single != null && mayBeScoped(target(single, member.type()), member.type())) {
                return true;
            }
            for (DotName kind : List.of(MANY_TO_MANY, ONE_TO_MANY)) {
                AnnotationInstance many = member.annotation(kind);
                if (many != null
                        && mayBeScoped(
                                elementTarget(many, member.type()), SiteIsolationModel.rawElementType(member.type()))) {
                    return true;
                }
            }
            if (mapKeyMayBeScoped(member)) {
                return true;
            }
            if (member.has(ELEMENT_COLLECTION)
                    || member.has(EMBEDDED)
                    || member.has(EMBEDDED_ID)
                    || isEmbeddable(member.type())) {
                ClassInfo nested = embeddableOf(member);
                if (opaque(member, nested)) {
                    return true;
                }
                if (nested != null && reachesSiteScoped(nested, visited)) {
                    return true;
                }
            }
        }
        if (embeddable.superName() != null) {
            ClassInfo parent = index.getClassByName(embeddable.superName());
            if (parent != null && reachesSiteScoped(parent, visited)) {
                return true;
            }
        }
        for (ClassInfo child : index.getAllKnownSubclasses(embeddable.name())) {
            if (reachesSiteScoped(child, visited)) {
                return true;
            }
        }
        return false;
    }
}
