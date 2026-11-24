package org.quarkword.front;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The public query variables of a front-end request, in the order WordPress's request parser sets them: an ordered
 * map of names to string values (a value may be empty), remembering which names came from the query string and which
 * from the matched rewrite rule. Immutable; every change returns a new instance.
 */
public final class QueryVars {

    private static final QueryVars EMPTY = new QueryVars(Map.of(), Set.of(), Set.of());

    private final Map<String, String> vars;
    private final Set<String> queryString;
    private final Set<String> rewrite;

    private QueryVars(Map<String, String> vars, Set<String> queryString, Set<String> rewrite) {
        this.vars = Collections.unmodifiableMap(new LinkedHashMap<>(vars));
        this.queryString = Collections.unmodifiableSet(new LinkedHashSet<>(queryString));
        this.rewrite = Collections.unmodifiableSet(new LinkedHashSet<>(rewrite));
    }

    /** No variables: the home page. */
    public static QueryVars empty() {
        return EMPTY;
    }

    /** The variables of {@code vars}, in its iteration order, with no source recorded. */
    public static QueryVars of(Map<String, String> vars) {
        Objects.requireNonNull(vars, "vars");
        vars.forEach((name, value) -> {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value of " + name);
        });
        return new QueryVars(vars, Set.of(), Set.of());
    }

    /** The value of {@code name}; present when the variable is set, even to an empty string. */
    public Optional<String> get(String name) {
        return Optional.ofNullable(vars.get(Objects.requireNonNull(name, "name")));
    }

    public boolean has(String name) {
        return vars.containsKey(Objects.requireNonNull(name, "name"));
    }

    /** These variables with {@code name} set to {@code value}; an existing variable keeps its position. */
    public QueryVars with(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Map<String, String> changed = new LinkedHashMap<>(vars);
        changed.put(name, value);
        return new QueryVars(changed, queryString, rewrite);
    }

    /** These variables without {@code names}. */
    public QueryVars without(String... names) {
        Map<String, String> changed = new LinkedHashMap<>(vars);
        Set<String> fromQuery = new LinkedHashSet<>(queryString);
        Set<String> fromRules = new LinkedHashSet<>(rewrite);
        for (String name : names) {
            changed.remove(Objects.requireNonNull(name, "name"));
            fromQuery.remove(name);
            fromRules.remove(name);
        }
        return new QueryVars(changed, fromQuery, fromRules);
    }

    /** The variables as an unmodifiable ordered map. */
    public Map<String, String> asMap() {
        return vars;
    }

    public boolean isEmpty() {
        return vars.isEmpty();
    }

    /** These variables, with {@code names} recorded as the ones that came from the query string. */
    public QueryVars fromQueryString(Set<String> names) {
        return new QueryVars(vars, Objects.requireNonNull(names, "names"), rewrite);
    }

    /** The names that came from the query string. */
    public Set<String> fromQueryString() {
        return queryString;
    }

    /** These variables, with {@code names} recorded as the ones that came from the matched rewrite rule. */
    public QueryVars fromRewrite(Set<String> names) {
        return new QueryVars(vars, queryString, Objects.requireNonNull(names, "names"));
    }

    /** The names that came from the matched rewrite rule. */
    public Set<String> fromRewrite() {
        return rewrite;
    }

    /** Whether the parser marked the request as not found: {@code error} is {@code 404}. */
    public boolean error404() {
        return "404".equals(vars.get("error"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QueryVars that
                && vars.equals(that.vars)
                && queryString.equals(that.queryString)
                && rewrite.equals(that.rewrite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vars, queryString, rewrite);
    }

    @Override
    public String toString() {
        return "QueryVars" + vars;
    }
}
