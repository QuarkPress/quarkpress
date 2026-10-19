package org.quarkpress.security.runtime;

/**
 * Whether the installation is a network of sites (the multisite module is installed) or a single site. Capability
 * rules that WordPress applies only in a network (the network administrator bypass, the multisite restrictions) ask
 * this bean.
 */
public interface InstallMode {

    boolean multisite();
}
