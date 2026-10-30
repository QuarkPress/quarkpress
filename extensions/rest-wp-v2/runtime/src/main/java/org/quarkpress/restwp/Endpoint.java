package org.quarkpress.restwp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One group of methods of a route with its arguments, permission and handler (a WordPress route handler).
 *
 * @param methods the HTTP methods it answers, upper-case
 * @param showInIndex whether the route indexes list it
 * @param allowBatch whether batch requests may call it
 */
public record Endpoint(
        List<String> methods,
        List<Arg> args,
        Permission permission,
        Handler handler,
        boolean showInIndex,
        boolean allowBatch) {

    public Endpoint {
        Objects.requireNonNull(methods, "methods");
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("an endpoint needs at least one method");
        }
        List<String> upper = new ArrayList<>(methods.size());
        for (String method : methods) {
            upper.add(Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT));
        }
        methods = List.copyOf(upper);
        args = List.copyOf(args);
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(handler, "handler");
    }

    /** An endpoint answering {@code methods}; the permission defaults to {@link Permission#PUBLIC}. */
    public static Builder of(String... methods) {
        return new Builder(List.of(methods));
    }

    public boolean declares(String method) {
        return methods.contains(method);
    }

    public static final class Builder {

        private final List<String> methods;
        private final List<Arg> args = new ArrayList<>();
        private Permission permission = Permission.PUBLIC;
        private Handler handler;
        private boolean showInIndex = true;
        private boolean allowBatch;

        private Builder(List<String> methods) {
            this.methods = methods;
        }

        public Builder arg(Arg arg) {
            args.add(Objects.requireNonNull(arg, "arg"));
            return this;
        }

        public Builder args(List<Arg> more) {
            more.forEach(this::arg);
            return this;
        }

        public Builder permission(Permission permission) {
            this.permission = Objects.requireNonNull(permission, "permission");
            return this;
        }

        public Builder handler(Handler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public Builder showInIndex(boolean showInIndex) {
            this.showInIndex = showInIndex;
            return this;
        }

        public Builder allowBatch(boolean allowBatch) {
            this.allowBatch = allowBatch;
            return this;
        }

        public Endpoint build() {
            return new Endpoint(methods, args, permission, handler, showInIndex, allowBatch);
        }
    }
}
