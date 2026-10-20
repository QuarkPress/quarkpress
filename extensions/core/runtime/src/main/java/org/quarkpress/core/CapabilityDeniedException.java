package org.quarkpress.core;

/**
 * The caller lacks a capability a QuarkPress service requires. {@link #anonymous()} says whether nobody was
 * authenticated, so that a REST layer can challenge (401) an anonymous caller and refuse (403) everyone else.
 */
public class CapabilityDeniedException extends SecurityException {

    private static final long serialVersionUID = 1L;

    private final String capability;

    private final boolean anonymous;

    /** A refusal of an authenticated caller, or of a caller the check does not know. */
    public CapabilityDeniedException(String capability) {
        this(capability, false);
    }

    /** A refusal of a caller who is anonymous ({@code anonymous}) or authenticated. */
    public CapabilityDeniedException(String capability, boolean anonymous) {
        super("the caller lacks the network capability " + capability);
        this.capability = capability;
        this.anonymous = anonymous;
    }

    public String capability() {
        return capability;
    }

    /** Whether the refused caller was anonymous (nobody authenticated). */
    public boolean anonymous() {
        return anonymous;
    }
}
