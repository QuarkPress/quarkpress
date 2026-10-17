package org.quarkpress.persistence.deployment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/**
 * Checks the mapping of site-scoped entities (the site filter on entities and collections, no caching) and the SQL and
 * HQL that mapping annotations of QuarkPress classes carry.
 */
final class SiteIsolationModel {

    static final DotName SITE_SCOPED = DotName.createSimple(SiteScopedEntity.class);
    static final DotName NATIVE_SQL_ALLOWED = DotName.createSimple(SiteScopedNativeSql.class);
    static final DotName ENTITY = DotName.createSimple("jakarta.persistence.Entity");
    static final DotName CACHEABLE = DotName.createSimple("jakarta.persistence.Cacheable");
    static final DotName HIBERNATE_CACHE = DotName.createSimple("org.hibernate.annotations.Cache");
    static final DotName FILTER = DotName.createSimple("org.hibernate.annotations.Filter");
    static final DotName FILTERS = DotName.createSimple("org.hibernate.annotations.Filters");
    static final List<DotName> TO_MANY = List.of(
            DotName.createSimple("jakarta.persistence.OneToMany"),
            DotName.createSimple("jakarta.persistence.ManyToMany"),
            DotName.createSimple("jakarta.persistence.ElementCollection"));

    /**
     * An annotation member holding SQL or HQL, and the container that holds repeated instances (or null). Nested
     * instances have no target of their own, so they are read through the container.
     */
    private record QueryAnnotation(DotName name, String member, boolean nativeSql, DotName container) {
        QueryAnnotation(String name, String member, boolean nativeSql, String container) {
            this(
                    DotName.createSimple(name),
                    member,
                    nativeSql,
                    container == null ? null : DotName.createSimple(container));
        }
    }

    /** One annotation instance and the element it is declared on. */
    private record Placed(AnnotationInstance annotation, AnnotationTarget target) {}

    private static final String JPA = "jakarta.persistence.";
    private static final String HIBERNATE = "org.hibernate.annotations.";
    private static final List<QueryAnnotation> QUERY_ANNOTATIONS = List.of(
            new QueryAnnotation(JPA + "NamedQuery", "query", false, JPA + "NamedQueries"),
            new QueryAnnotation(JPA + "NamedNativeQuery", "query", true, JPA + "NamedNativeQueries"),
            new QueryAnnotation(HIBERNATE + "NamedQuery", "query", false, HIBERNATE + "NamedQueries"),
            new QueryAnnotation(HIBERNATE + "NamedNativeQuery", "query", true, HIBERNATE + "NamedNativeQueries"),
            new QueryAnnotation(HIBERNATE + "HQLSelect", "query", false, null),
            new QueryAnnotation(HIBERNATE + "Formula", "value", true, null),
            new QueryAnnotation(HIBERNATE + "DiscriminatorFormula", "value", true, null),
            new QueryAnnotation(HIBERNATE + "Subselect", "value", true, null),
            new QueryAnnotation(HIBERNATE + "JoinFormula", "value", true, null),
            new QueryAnnotation(HIBERNATE + "ColumnTransformer", "read", true, HIBERNATE + "ColumnTransformers"),
            new QueryAnnotation(HIBERNATE + "ColumnTransformer", "write", true, HIBERNATE + "ColumnTransformers"),
            new QueryAnnotation(HIBERNATE + "SQLRestriction", "value", true, null),
            new QueryAnnotation(HIBERNATE + "SQLInsert", "sql", true, HIBERNATE + "SQLInserts"),
            new QueryAnnotation(HIBERNATE + "SQLUpdate", "sql", true, HIBERNATE + "SQLUpdates"),
            new QueryAnnotation(HIBERNATE + "SQLDelete", "sql", true, HIBERNATE + "SQLDeletes"),
            new QueryAnnotation(HIBERNATE + "SQLDeleteAll", "sql", true, null),
            new QueryAnnotation(HIBERNATE + "SQLSelect", "sql", true, null));
    private static final DotName JOIN_FORMULA = DotName.createSimple(HIBERNATE + "JoinFormula");
    private static final DotName JOIN_COLUMN_OR_FORMULA = DotName.createSimple(HIBERNATE + "JoinColumnOrFormula");
    private static final DotName JOIN_COLUMNS_OR_FORMULAS = DotName.createSimple(HIBERNATE + "JoinColumnsOrFormulas");

    private SiteIsolationModel() {}

    static Set<ClassInfo> siteScopedEntities(IndexView index) {
        Set<ClassInfo> entities = new LinkedHashSet<>();
        for (ClassInfo type : index.getAllKnownSubclasses(SITE_SCOPED)) {
            if (type.hasDeclaredAnnotation(ENTITY)) {
                entities.add(type);
            }
        }
        return entities;
    }

    /** The HQL names of the site-scoped entities ({@code @Entity(name)} or the simple class name). */
    static Set<String> siteScopedEntityNames(IndexView index) {
        Set<String> names = new TreeSet<>();
        for (ClassInfo type : siteScopedEntities(index)) {
            AnnotationValue name = type.declaredAnnotation(ENTITY).value("name");
            names.add(name == null || name.asString().isEmpty() ? type.simpleName() : name.asString());
        }
        return names;
    }

    static List<String> problems(IndexView index) {
        List<String> problems = new ArrayList<>();
        Set<DotName> siteScoped = new TreeSet<>();
        for (ClassInfo type : siteScopedEntities(index)) {
            siteScoped.add(type.name());
            if (!filteredHere(type) && !filteredByEntityAncestor(index, type)) {
                problems.add("site-scoped entity " + type.name() + " must carry @Filter(name = \"qp_site\")");
            }
            if (isCached(index, type)) {
                problems.add("site-scoped entity " + type.name()
                        + " must not be cached: cached rows bypass the site filter");
            }
        }
        for (ClassInfo type : index.getKnownClasses()) {
            if (!type.name().toString().startsWith("org.quarkpress.")) {
                continue;
            }
            for (FieldInfo field : type.fields()) {
                checkCollection(type, field.name(), field.type(), field, siteScoped, problems);
            }
            for (MethodInfo method : type.methods()) {
                if (method.parametersCount() == 0) {
                    checkCollection(type, method.name(), method.returnType(), method, siteScoped, problems);
                }
            }
        }
        return problems;
    }

    /**
     * SQL and HQL in mapping annotations of QuarkPress classes: native SQL naming a site-scoped table (allowed in
     * classes annotated {@code @SiteScopedNativeSql}), HQL inserts into site-scoped entities and assignments to
     * {@code siteId}.
     */
    static List<SiteIsolationScanner.Finding> queryAnnotations(
            IndexView index, Set<String> siteTables, Set<String> siteEntities) {
        List<SiteIsolationScanner.Finding> findings = new ArrayList<>();
        for (QueryAnnotation kind : QUERY_ANNOTATIONS) {
            for (Placed placed : instances(index, kind)) {
                AnnotationInstance annotation = placed.annotation();
                ClassInfo owner = declaringClass(placed.target());
                if (owner == null || !owner.name().toString().startsWith("org.quarkpress.")) {
                    continue;
                }
                AnnotationValue value = annotation.value(kind.member());
                if (value == null) {
                    continue;
                }
                String text = value.asString();
                String where = memberName(placed.target());
                String label = " in @" + kind.name().withoutPackagePrefix();
                if (kind.nativeSql()) {
                    if (!owner.hasDeclaredAnnotation(NATIVE_SQL_ALLOWED)) {
                        for (String table : siteTables) {
                            if (SiteIsolationScanner.mentionsTable(text, table)) {
                                findings.add(new SiteIsolationScanner.Finding(
                                        owner.name().toString(),
                                        where,
                                        "native SQL on site-scoped table " + table + label));
                            }
                        }
                    }
                } else {
                    for (String entity : siteEntities) {
                        if (SiteIsolationScanner.insertsInto(text, entity)) {
                            findings.add(new SiteIsolationScanner.Finding(
                                    owner.name().toString(),
                                    where,
                                    "inserts into site-scoped entity " + entity + label));
                        }
                    }
                    if (SiteIsolationScanner.assignsSiteId(text, false)) {
                        findings.add(new SiteIsolationScanner.Finding(
                                owner.name().toString(), where, "assigns siteId in a query" + label));
                    }
                }
            }
        }
        return findings;
    }

    private static List<Placed> instances(IndexView index, QueryAnnotation kind) {
        List<Placed> placed = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(kind.name())) {
            placed.add(new Placed(annotation, annotation.target()));
        }
        if (kind.container() != null) {
            for (AnnotationInstance container : index.getAnnotations(kind.container())) {
                AnnotationValue nested = container.value();
                if (nested != null) {
                    for (AnnotationInstance annotation : nested.asNestedArray()) {
                        placed.add(new Placed(annotation, container.target()));
                    }
                }
            }
        }
        if (kind.name().equals(JOIN_FORMULA)) {
            for (AnnotationInstance single : index.getAnnotations(JOIN_COLUMN_OR_FORMULA)) {
                addFormula(single, single.target(), placed);
            }
            for (AnnotationInstance several : index.getAnnotations(JOIN_COLUMNS_OR_FORMULAS)) {
                AnnotationValue nested = several.value();
                if (nested != null) {
                    for (AnnotationInstance single : nested.asNestedArray()) {
                        addFormula(single, several.target(), placed);
                    }
                }
            }
        }
        return placed;
    }

    private static void addFormula(AnnotationInstance joinColumnOrFormula, AnnotationTarget target, List<Placed> into) {
        AnnotationValue formula = joinColumnOrFormula.value("formula");
        if (formula != null) {
            into.add(new Placed(formula.asNested(), target));
        }
    }

    private static void checkCollection(
            ClassInfo type,
            String name,
            Type declared,
            AnnotationTarget target,
            Set<DotName> siteScoped,
            List<String> problems) {
        AnnotationInstance toMany = toMany(target);
        if (toMany == null) {
            return;
        }
        DotName element = null;
        AnnotationValue targetEntity = toMany.value("targetEntity");
        if (targetEntity == null) {
            targetEntity = toMany.value("targetClass");
        }
        if (targetEntity != null && !targetEntity.asClass().name().equals(DotName.createSimple("void"))) {
            element = targetEntity.asClass().name();
        }
        if (element == null) {
            element = elementType(declared);
        }
        if (element != null
                && siteScoped.contains(element)
                && !hasSiteFilter(declaredOn(target, FILTER), declaredOn(target, FILTERS))) {
            problems.add("collection " + type.name() + "#" + name + " of site-scoped " + element
                    + " must carry @Filter(name = \"qp_site\")");
        }
    }

    private static AnnotationInstance toMany(AnnotationTarget target) {
        for (DotName annotation : TO_MANY) {
            AnnotationInstance found = declaredOn(target, annotation);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static AnnotationInstance declaredOn(AnnotationTarget target, DotName annotation) {
        return switch (target.kind()) {
            case FIELD -> target.asField().annotation(annotation);
            case METHOD -> target.asMethod().declaredAnnotation(annotation);
            default -> null;
        };
    }

    /** The element type of a collection or array (the value type for maps, the bound of a wildcard), or null. */
    private static DotName elementType(Type type) {
        Type element;
        if (type.kind() == Type.Kind.ARRAY) {
            element = type.asArrayType().elementType();
        } else if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            List<Type> arguments = type.asParameterizedType().arguments();
            if (arguments.isEmpty()) {
                return null;
            }
            element = arguments.get(arguments.size() - 1);
        } else {
            return null;
        }
        if (element.kind() == Type.Kind.WILDCARD_TYPE) {
            element = element.asWildcardType().extendsBound();
        }
        return element == null ? null : element.name();
    }

    private static boolean filteredHere(ClassInfo type) {
        return hasSiteFilter(type.declaredAnnotation(FILTER), type.declaredAnnotation(FILTERS));
    }

    /** Hibernate applies the filter of an entity to its entity subclasses. */
    private static boolean filteredByEntityAncestor(IndexView index, ClassInfo type) {
        ClassInfo current = superclass(index, type);
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (current.hasDeclaredAnnotation(ENTITY) && filteredHere(current)) {
                return true;
            }
            current = superclass(index, current);
        }
        return false;
    }

    /** Whether the entity or one of its superclasses asks for caching. */
    private static boolean isCached(IndexView index, ClassInfo type) {
        ClassInfo current = type;
        for (int depth = 0; current != null && depth < 64; depth++) {
            AnnotationInstance cacheable = current.declaredAnnotation(CACHEABLE);
            boolean cacheableOn = cacheable != null
                    && (cacheable.value() == null || cacheable.value().asBoolean());
            if (cacheableOn || current.hasDeclaredAnnotation(HIBERNATE_CACHE)) {
                return true;
            }
            current = superclass(index, current);
        }
        return false;
    }

    private static ClassInfo superclass(IndexView index, ClassInfo type) {
        DotName superName = type.superName();
        return superName == null ? null : index.getClassByName(superName);
    }

    private static ClassInfo declaringClass(AnnotationTarget target) {
        if (target == null) {
            return null;
        }
        return switch (target.kind()) {
            case CLASS -> target.asClass();
            case FIELD -> target.asField().declaringClass();
            case METHOD -> target.asMethod().declaringClass();
            case METHOD_PARAMETER -> target.asMethodParameter().method().declaringClass();
            case RECORD_COMPONENT -> target.asRecordComponent().declaringClass();
            default -> null;
        };
    }

    private static String memberName(AnnotationTarget target) {
        return switch (target.kind()) {
            case FIELD -> target.asField().name();
            case METHOD -> target.asMethod().name();
            case METHOD_PARAMETER -> target.asMethodParameter().method().name();
            case RECORD_COMPONENT -> target.asRecordComponent().name();
            default -> "<class>";
        };
    }

    private static boolean hasSiteFilter(AnnotationInstance filter, AnnotationInstance filters) {
        if (filter != null && isSiteFilter(filter)) {
            return true;
        }
        if (filters != null) {
            for (AnnotationInstance nested : filters.value().asNestedArray()) {
                if (isSiteFilter(nested)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSiteFilter(AnnotationInstance filter) {
        AnnotationValue name = filter.value("name");
        return name != null && SiteScopedEntity.FILTER.equals(name.asString());
    }
}
