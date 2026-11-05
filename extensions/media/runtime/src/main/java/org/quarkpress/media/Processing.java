package org.quarkpress.media;

/** How far the generated files of an attachment are made. */
public enum Processing {
    /** Nothing to make: the file is not an image the processor can make sizes of. */
    NONE,
    /** Stored; its sizes and metadata are still to be made. */
    PENDING,
    /** Some sizes could not be made; processing it again makes the missing ones. */
    PARTIAL,
    /** Every size is made. */
    DONE,
    /** The image could not be processed at all. */
    FAILED
}
