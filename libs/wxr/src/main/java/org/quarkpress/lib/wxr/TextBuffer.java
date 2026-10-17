package org.quarkpress.lib.wxr;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamReader;

/** Accumulates one value's text chunks and enforces the per-value limit before each append. */
final class TextBuffer {

    private final int max;
    private final StringBuilder text = new StringBuilder();

    TextBuffer(int max) {
        this.max = max;
    }

    void append(XMLStreamReader xml) {
        int length = xml.getTextLength();
        if ((long) text.length() + length > max) {
            Location at = xml.getLocation();
            throw new WxrException(
                    WxrProblem.Kind.VALUE_TOO_LARGE,
                    at.getLineNumber(),
                    at.getColumnNumber(),
                    "a value is longer than " + max + " characters");
        }
        text.append(xml.getTextCharacters(), xml.getTextStart(), length);
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
