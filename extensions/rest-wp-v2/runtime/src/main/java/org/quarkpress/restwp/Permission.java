package org.quarkpress.restwp;

/** Whether the caller may use an endpoint; checked after the arguments, before the handler. */
@FunctionalInterface
public interface Permission {

    PermissionResult check(RestRequest request);

    /** Everyone may. */
    Permission PUBLIC = request -> PermissionResult.allowed();
}
