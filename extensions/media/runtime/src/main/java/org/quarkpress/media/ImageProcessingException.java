package org.quarkpress.media;

/** An image processor could not decode or encode an image; the message is the processor's reason. */
public class ImageProcessingException extends Exception {

    private static final long serialVersionUID = 1L;

    public ImageProcessingException(String message) {
        super(message);
    }

    public ImageProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
