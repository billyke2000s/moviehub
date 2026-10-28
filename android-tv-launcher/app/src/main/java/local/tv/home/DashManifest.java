package local.tv.home;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Rewrites DASH's advertised media resources, including inherited templates, into relay grants. */
final class DashManifest {

    interface Grant {
        String url(String target) throws Exception;
    }

    static String rewrite(String xml, URI origin, Grant grant) throws Exception {
        if (
            xml.length() > 1024 * 1024 ||
            xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") ||
            xml.toUpperCase(Locale.ROOT).contains("<!ENTITY")
        ) throw new IOException("Unsafe DASH manifest");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
        Document document = parser.parse(new org.xml.sax.InputSource(new StringReader(xml)));
        if (!"MPD".equals(document.getDocumentElement().getLocalName())) throw new IOException(
            "Invalid DASH manifest"
        );
        walk(
            document.getDocumentElement(),
            origin,
            new LinkedHashMap<>(),
            grant,
            new int[] { 2 * 1024 * 1024, 128 },
            0
        );
        StringWriter output = new StringWriter();
        TransformerFactory.newInstance()
            .newTransformer()
            .transform(new DOMSource(document), new StreamResult(output));
        return output.toString();
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) if (
            child instanceof Element
        ) result.add((Element) child);
        return result;
    }

    private static void walk(
        Element element,
        URI inheritedBase,
        Map<String, Element> inherited,
        Grant grant,
        int[] budget,
        int depth
    ) throws Exception {
        if (depth > 64) throw new IOException("DASH nesting limit");
        URI base = inheritedBase;
        List<Element> children = children(element);
        boolean baseFound = false;
        for (Element child : children)
            if ("BaseURL".equals(child.getLocalName()) && !baseFound) {
                base = inheritedBase.resolve(child.getTextContent().trim());
                baseFound = true;
            }
        Map<String, Element> segments = new LinkedHashMap<>(inherited);
        for (Element child : children) {
            String name = child.getLocalName();
            if ("ContentProtection".equals(name)) throw new IOException("Protected DASH unsupported");
            if (Arrays.asList("SegmentTemplate", "SegmentList", "SegmentBase").contains(name)) {
                // A new addressing mode replaces the inherited mode.
                Element parent = segments.get(name);
                Element merged = copy(child, budget);
                if (parent != null) {
                    NamedNodeMap attrs = parent.getAttributes();
                    for (int i = 0; i < attrs.getLength(); i++) {
                        Node attr = attrs.item(i);
                        if (!merged.hasAttribute(attr.getNodeName())) merged.setAttribute(
                            attr.getNodeName(),
                            attr.getNodeValue()
                        );
                    }
                    if (children(merged).isEmpty()) for (Element part : children(parent))
                        merged.appendChild(copy(part, budget));
                }
                segments.clear();
                segments.put(name, merged);
                element.removeChild(child);
            }
        }
        boolean representation = "Representation".equals(element.getLocalName());
        for (Element child : children) {
            String name = child.getLocalName();
            if ("BaseURL".equals(name)) element.removeChild(child);
            else if (Arrays.asList("UTCTiming", "Location", "PatchLocation").contains(name)) {
                // VOD manifests need no remote clock/manifest redirection outside the grant set.
                element.removeChild(child);
            } else if (child.getParentNode() == element) walk(
                child,
                base,
                segments,
                grant,
                budget,
                depth + 1
            );
        }
        if (representation) {
            if (--budget[1] < 0) throw new IOException("DASH representation limit");
            for (Element segment : segments.values()) {
                Element copy = copy(segment, budget);
                resources(copy, base, grant);
                element.appendChild(copy);
            }
            if (segments.isEmpty() || segments.containsKey("SegmentBase")) {
                Element address = element
                    .getOwnerDocument()
                    .createElementNS(element.getNamespaceURI(), "BaseURL");
                address.setTextContent(grant.url(base.toString()));
                element.appendChild(address);
            }
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) if (
            "http://www.w3.org/1999/xlink".equals(attributes.item(i).getNamespaceURI())
        ) throw new IOException("External DASH references unsupported");
    }

    private static Element copy(Element source, int[] budget) throws IOException {
        budget[0] -= weight(source, 0);
        if (budget[0] < 0) throw new IOException("DASH expansion limit");
        return (Element) source.cloneNode(true);
    }

    private static int weight(Node node, int depth) throws IOException {
        if (depth > 64) throw new IOException("DASH nesting limit");
        int size = 64 + (node.getNodeValue() == null ? 0 : node.getNodeValue().length());
        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null) for (int i = 0; i < attributes.getLength(); i++) size +=
            attributes.item(i).getNodeValue().length() + attributes.item(i).getNodeName().length();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) size += weight(
            child,
            depth + 1
        );
        return size;
    }

    private static void resources(Element element, URI base, Grant grant) throws Exception {
        for (String attr : Arrays.asList("media", "initialization", "sourceURL", "index"))
            if (element.hasAttribute(attr) && !element.getAttribute(attr).isEmpty()) element.setAttribute(
                attr,
                grant.url(base.resolve(element.getAttribute(attr)).toString())
            );
        for (Element child : children(element)) resources(child, base, grant);
    }

    /** Only numeric substitutions or a bounded representation identifier can reach the origin. */
    static final class Template {

        private static final Pattern TOKEN = Pattern.compile(
            "\\$(RepresentationID|Number|Bandwidth|Time)(?:%0[1-9][0-9]?d)?\\$"
        );
        final String local;
        private final String remote;
        private final List<String> tokens = new ArrayList<>();
        private final Pattern values;

        Template(String remote) throws IOException {
            this.remote = remote;
            Matcher matcher = TOKEN.matcher(remote);
            StringBuilder local = new StringBuilder(),
                regex = new StringBuilder();
            int end = 0;
            while (matcher.find()) {
                if (remote.substring(end, matcher.start()).contains("$")) throw new IOException(
                    "Unsupported DASH template"
                );
                String separator = "v" + tokens.size() + "-";
                local.append(separator).append(matcher.group());
                regex
                    .append(Pattern.quote(separator))
                    .append(
                        "RepresentationID".equals(matcher.group(1))
                            ? "([A-Za-z0-9_.-]{1,200})"
                            : "([0-9]{1,20})"
                    );
                tokens.add(matcher.group());
                end = matcher.end();
            }
            if (tokens.isEmpty() || remote.substring(end).contains("$")) throw new IOException(
                "Unsupported DASH template"
            );
            this.local = local.toString();
            values = Pattern.compile(regex.toString());
        }

        String expand(String suffix) throws IOException {
            Matcher match = values.matcher(suffix);
            if (!match.matches()) throw new IOException("Invalid DASH substitution");
            String target = remote;
            for (int i = 0; i < tokens.size(); i++) target = target.replace(
                tokens.get(i),
                match.group(i + 1)
            );
            return target;
        }
    }
}
