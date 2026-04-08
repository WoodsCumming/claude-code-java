package com.anthropic.claudecode.util;

/**
 * XML/HTML escaping utilities.
 * Translated from src/utils/xml.ts
 */
public class XmlUtils {

    /**
     * Escape XML/HTML special characters for text content.
     * Translated from escapeXml() in xml.ts
     */
    public static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Escape for XML attribute values.
     * Translated from escapeXmlAttr() in xml.ts
     */
    public static String escapeXmlAttr(String s) {
        return escapeXml(s)
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private XmlUtils() {}
}
