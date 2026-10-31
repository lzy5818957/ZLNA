package app.zlna.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

public final class Xml {
    private Xml() {}
    public static String escape(String value) {
        StringBuilder clean = new StringBuilder();
        value.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF).forEach(clean::appendCodePoint);
        return clean.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    public static Document parse(byte[] data) throws Exception {
        // UTF-8 only, reject DTDs before parsing even on Android parsers without feature switches.
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0 || text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY")) throw new SAXException("DTD is not supported");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true); f.setExpandEntityReferences(false);
        for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { f.setFeature(feature, false); } catch (ParserConfigurationException ignored) { /* Android has a different parser. */ }
        }
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are disabled"); });
        return b.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    public static String value(Element element, String name) {
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element && name.equals(n.getLocalName())) return n.getTextContent();
        throw new IllegalArgumentException("Missing " + name);
    }
    public static String tag(String name, String value) { return "<" + name + ">" + escape(value) + "</" + name + ">"; }
}
