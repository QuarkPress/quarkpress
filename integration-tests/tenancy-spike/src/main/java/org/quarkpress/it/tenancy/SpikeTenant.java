package org.quarkpress.it.tenancy;

import jakarta.enterprise.context.RequestScoped;

/** The site of the current request: a site id, "-1" for the whole network, "0" for none. */
@RequestScoped
public class SpikeTenant {

    private String current = "0";

    public String get() {
        return current;
    }

    public void set(String tenantId) {
        current = tenantId;
    }
}
