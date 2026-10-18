package org.quarkpress.core;

/** The caller lacks a capability a QuarkPress service requires. */
public class CapabilityDeniedException extends SecurityException {

    private static final long serialVersionUID = 1L;

    private final String capability;

    public CapabilityDeniedException(String capability) {
        super("the caller lacks the network capability " + capability);
        this.capability = capability;
    }

    public String capability() {
        return capability;
    }
}
