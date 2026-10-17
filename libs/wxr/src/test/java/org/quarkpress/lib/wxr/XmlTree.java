package org.quarkpress.lib.wxr;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * An element tree of an XML file, read through the library's lenient input (so WordPress's malformed output
 * parses the same way as ours), with whether each leaf's text came from CDATA. Comments and whitespace between
 * elements are ignored; leaf text is kept exactly.
 */
final class XmlTree {

    record Node(String name, Map<String, String> attributes, String text, boolean cdata, List<Node> children) {}

    private XmlTree() {}

    static Node parse(byte[] xml) throws Exception {
        ProblemLog log = new ProblemLog(true);
        RepairingReader reader =
                new RepairingReader(XmlInput.decode(new ByteArrayInputStream(xml), WxrReadOptions.LENIENT, log), log);
        XMLStreamReader stax = StaxFactory.create(true).createXMLStreamReader(reader);
        while (stax.next() != XMLStreamConstants.START_ELEMENT) {
            // prolog
        }
        return element(stax);
    }

    private static Node element(XMLStreamReader x) throws XMLStreamException {
        String name = qualified(x.getNamespaceURI(), x.getLocalName());
        Map<String, String> attributes = new TreeMap<>();
        for (int i = 0; i < x.getAttributeCount(); i++) {
            attributes.put(qualified(x.getAttributeNamespace(i), x.getAttributeLocalName(i)), x.getAttributeValue(i));
        }
        StringBuilder text = new StringBuilder();
        boolean cdata = false;
        List<Node> children = new ArrayList<>();
        while (true) {
            switch (x.next()) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> text.append(x.getText());
                case XMLStreamConstants.CDATA -> {
                    cdata = true;
                    text.append(x.getText());
                }
                case XMLStreamConstants.START_ELEMENT -> children.add(element(x));
                case XMLStreamConstants.END_ELEMENT -> {
                    boolean leaf = children.isEmpty();
                    return new Node(
                            name, attributes, leaf ? text.toString() : null, leaf && cdata, List.copyOf(children));
                }
                default -> {}
            }
        }
    }

    private static String qualified(String uri, String local) {
        if (uri == null || uri.isEmpty()) {
            return local;
        }
        String prefix = WxrNames.knownPrefix(uri);
        return prefix == null ? "{" + uri + "}" + local : prefix + ":" + local;
    }

    /** Every element and attribute by path, e.g. "/rss/channel/item[2]/wp:comment[1]/wp:comment_author_url". */
    static Map<String, String> flatten(Node root) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten(root, "/" + root.name(), out);
        return out;
    }

    private static void flatten(Node node, String path, Map<String, String> out) {
        node.attributes().forEach((k, v) -> out.put(path + "/@" + k, v));
        if (node.children().isEmpty()) {
            out.put(path, (node.cdata() ? "cdata: " : "text: ") + node.text());
            return;
        }
        out.put(path, "children: " + node.children().stream().map(Node::name).collect(Collectors.joining(" ")));
        Map<String, Integer> seen = new HashMap<>();
        for (Node child : node.children()) {
            int n = seen.merge(child.name(), 1, Integer::sum);
            flatten(child, path + "/" + child.name() + "[" + n + "]", out);
        }
    }
}
