package org.quarkpress.lib.wxr;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;

/**
 * The JDK's own StAX implementation, hardened: no DTDs, no external entities or DTD access, namespace aware,
 * not coalescing (text arrives in chunks, so value limits apply while reading), CDATA reported in chunks of at
 * most {@link #CDATA_CHUNK} chars.
 *
 * <p>DTD declarations are never read, so no entity other than the five predefined ones is ever expanded; a
 * reference to any other is a parse error (the pre-filter turns such references into text first). The JDK's
 * count limits on predefined references are lifted: since JDK 24 they stop a document after 100,000 references
 * such as {@code &amp;}, which real exports exceed. The JDK's 1000-char name limit is lifted as well: the
 * pre-filter holds every name to the per-value limit.
 */
final class StaxFactory {

    static final int CDATA_CHUNK = 65_536;
    static final String CDATA_CHUNK_SIZE = "jdk.xml.cdataChunkSize";
    static final String REPORT_CDATA = "http://java.sun.com/xml/stream/properties/report-cdata-event";
    static final String MAX_GENERAL_ENTITY_SIZE = "jdk.xml.maxGeneralEntitySizeLimit";
    static final String TOTAL_ENTITY_SIZE = "jdk.xml.totalEntitySizeLimit";
    static final String MAX_NAME = "jdk.xml.maxXMLNameLimit";
    /** Held to {@link RepairingReader#MAX_ATTRIBUTES} by the pre-filter instead. */
    static final String MAX_ATTRIBUTES = "jdk.xml.elementAttributeLimit";
    /** The JDK's value for "no limit". */
    private static final int UNLIMITED = 0;

    private StaxFactory() {}

    static XMLInputFactory create() {
        return create(false);
    }

    /** {@code reportCdata}: report CDATA sections as CDATA events (tests compare the form WordPress writes). */
    static XMLInputFactory create(boolean reportCdata) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.TRUE);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(CDATA_CHUNK_SIZE, CDATA_CHUNK);
        factory.setProperty(MAX_GENERAL_ENTITY_SIZE, UNLIMITED);
        factory.setProperty(TOTAL_ENTITY_SIZE, UNLIMITED);
        factory.setProperty(MAX_NAME, UNLIMITED);
        factory.setProperty(MAX_ATTRIBUTES, UNLIMITED);
        if (reportCdata) {
            factory.setProperty(REPORT_CDATA, Boolean.TRUE);
        }
        return factory;
    }
}
