package org.qubership.integration.platform.engine.routes.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public final class SnapshotValueAssertions {
    private static final String MATCH_FIELD = "$match";
    private static final String FORMAT_FIELD = "format";
    private static final String VALUE_FIELD = "value";

    private SnapshotValueAssertions() {
    }

    public static void assertMatches(JsonNode expected, JsonNode actual, String description) {
        assertNode(expected, actual, "", description);
    }

    public static void assertMapValues(
            Map<String, ?> expected,
            Map<String, ?> actual,
            String description
    ) {
        assertValues(expected, name -> actual.get(name), description);
    }

    public static void assertValues(
            Map<String, ?> expected,
            Function<String, ?> actualValue,
            String description
    ) {
        expected.forEach((name, expectedValue) -> assertEquals(
                expectedValue,
                actualValue.apply(name),
                () -> description + " '" + name + "'."
        ));
    }

    private static void assertNode(
            JsonNode expected,
            JsonNode actual,
            String pointer,
            String description
    ) {
        if (isMatcher(expected)) {
            assertMatcher(expected, actual, pointer, description);
            return;
        }
        if (expected.isObject()) {
            assertTrue(
                    actual != null && actual.isObject(),
                    () -> typeMismatch(description, pointer, "an object", actual)
            );
            Set<String> expectedFields = fieldNames(expected);
            Set<String> actualFields = fieldNames(actual);
            assertEquals(
                    expectedFields,
                    actualFields,
                    () -> description + " Object fields at '" + displayPointer(pointer)
                            + "' differ from the expectation."
            );
            expectedFields.forEach(fieldName -> assertNode(
                    expected.get(fieldName),
                    actual.get(fieldName),
                    childPointer(pointer, fieldName),
                    description
            ));
            return;
        }
        if (expected.isArray()) {
            assertTrue(
                    actual != null && actual.isArray(),
                    () -> typeMismatch(description, pointer, "an array", actual)
            );
            assertEquals(
                    expected.size(),
                    actual.size(),
                    () -> description + " Array length at '" + displayPointer(pointer)
                            + "' differs from the expectation."
            );
            for (int index = 0; index < expected.size(); index++) {
                assertNode(
                        expected.get(index),
                        actual.get(index),
                        childPointer(pointer, String.valueOf(index)),
                        description
                );
            }
            return;
        }
        assertEquals(
                expected,
                actual,
                () -> description + " Value at '" + displayPointer(pointer)
                        + "' differs from the expectation."
        );
    }

    public static boolean isMatcher(JsonNode expected) {
        return expected.isObject() && expected.has(MATCH_FIELD);
    }

    private static void assertMatcher(
            JsonNode matcher,
            JsonNode actual,
            String pointer,
            String description
    ) {
        JsonNode matcherNameNode = matcher.get(MATCH_FIELD);
        if (matcherNameNode == null || !matcherNameNode.isTextual() || matcherNameNode.textValue().isBlank()) {
            throw invalidMatcher(pointer, "'" + MATCH_FIELD + "' must be a nonblank string");
        }

        String matcherName = matcherNameNode.textValue();
        switch (matcherName) {
            case "uuid" -> {
                requireFields(matcher, pointer, matcherName, Set.of(MATCH_FIELD));
                assertUuid(actual, pointer, description);
            }
            case "date-time" -> {
                requireFields(matcher, pointer, matcherName, Set.of(MATCH_FIELD, FORMAT_FIELD));
                assertDateTime(matcher, actual, pointer, description);
            }
            case "xml" -> {
                requireFields(matcher, pointer, matcherName, Set.of(MATCH_FIELD, VALUE_FIELD));
                assertXml(matcher, actual, pointer, description);
            }
            default -> throw invalidMatcher(pointer, "unsupported matcher '" + matcherName + "'");
        }
    }

    private static void assertUuid(JsonNode actual, String pointer, String description) {
        assertTrue(
                actual != null && actual.isTextual(),
                () -> typeMismatch(description, pointer, "a canonical UUID string", actual)
        );

        UUID uuid;
        try {
            uuid = UUID.fromString(actual.textValue());
        } catch (IllegalArgumentException exception) {
            fail(typeMismatch(description, pointer, "a canonical UUID string", actual));
            return;
        }
        assertEquals(
                uuid.toString(),
                actual.textValue(),
                () -> typeMismatch(description, pointer, "a canonical UUID string", actual)
        );
    }

    private static void assertDateTime(
            JsonNode matcher,
            JsonNode actual,
            String pointer,
            String description
    ) {
        JsonNode formatNode = matcher.get(FORMAT_FIELD);
        if (formatNode == null || !formatNode.isTextual() || formatNode.textValue().isBlank()) {
            throw invalidMatcher(pointer, "matcher 'date-time' requires a nonblank string 'format'");
        }

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(formatNode.textValue(), Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException exception) {
            throw invalidMatcher(pointer, "matcher 'date-time' has invalid format '"
                    + formatNode.textValue() + "'");
        }

        assertTrue(
                actual != null && actual.isTextual(),
                () -> typeMismatch(description, pointer, "a date-time string matching format '"
                        + formatNode.textValue() + "'", actual)
        );
        try {
            LocalDateTime.parse(actual.textValue(), formatter);
        } catch (DateTimeParseException exception) {
            fail(typeMismatch(description, pointer, "a date-time string matching format '"
                    + formatNode.textValue() + "'", actual));
        }
    }

    private static void assertXml(
            JsonNode matcher,
            JsonNode actual,
            String pointer,
            String description
    ) {
        JsonNode expectedXmlNode = matcher.get(VALUE_FIELD);
        if (expectedXmlNode == null
                || !expectedXmlNode.isTextual()
                || expectedXmlNode.textValue().isBlank()) {
            throw invalidMatcher(pointer, "matcher 'xml' requires a nonblank string 'value'");
        }
        assertTrue(
                actual != null && actual.isTextual(),
                () -> typeMismatch(description, pointer, "an XML string", actual)
        );

        Document expectedDocument;
        try {
            expectedDocument = parseXml(expectedXmlNode.textValue());
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw invalidMatcher(pointer, "matcher 'xml' value must be well-formed XML");
        }

        Document actualDocument;
        try {
            actualDocument = parseXml(actual.textValue());
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            fail(typeMismatch(description, pointer, "a well-formed XML string", actual));
            return;
        }

        assertTrue(
                xmlNodesEqual(
                        expectedDocument.getDocumentElement(),
                        actualDocument.getDocumentElement(),
                        false,
                        false
                ),
                () -> description + " XML document at '" + displayPointer(pointer)
                        + "' differs from the expectation."
        );
    }

    private static Document parseXml(
            String xml
    ) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setCoalescing(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        Document document = factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(xml))
        );
        document.normalizeDocument();
        return document;
    }

    private static boolean xmlNodesEqual(
            Node expected,
            Node actual,
            boolean expectedPreserveSpace,
            boolean actualPreserveSpace
    ) {
        if (isTextNode(expected)) {
            return isTextNode(actual)
                    && Objects.equals(expected.getNodeValue(), actual.getNodeValue());
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return false;
        }
        if (expected.getNodeType() != Node.ELEMENT_NODE) {
            return expected.isEqualNode(actual);
        }

        Element expectedElement = (Element) expected;
        Element actualElement = (Element) actual;
        if (!expandedName(expectedElement).equals(expandedName(actualElement))
                || !attributes(expectedElement).equals(attributes(actualElement))) {
            return false;
        }

        boolean expectedChildPreserveSpace = preservesSpace(
                expectedElement,
                expectedPreserveSpace
        );
        boolean actualChildPreserveSpace = preservesSpace(
                actualElement,
                actualPreserveSpace
        );
        List<Node> expectedChildren = significantChildren(
                expectedElement,
                expectedChildPreserveSpace
        );
        List<Node> actualChildren = significantChildren(
                actualElement,
                actualChildPreserveSpace
        );
        if (expectedChildren.size() != actualChildren.size()) {
            return false;
        }
        for (int index = 0; index < expectedChildren.size(); index++) {
            if (!xmlNodesEqual(
                    expectedChildren.get(index),
                    actualChildren.get(index),
                    expectedChildPreserveSpace,
                    actualChildPreserveSpace
            )) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTextNode(Node node) {
        return node.getNodeType() == Node.TEXT_NODE
                || node.getNodeType() == Node.CDATA_SECTION_NODE;
    }

    private static Map<ExpandedName, String> attributes(Element element) {
        Map<ExpandedName, String> attributes = new LinkedHashMap<>();
        NamedNodeMap attributeNodes = element.getAttributes();
        for (int index = 0; index < attributeNodes.getLength(); index++) {
            Node attribute = attributeNodes.item(index);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                attributes.put(expandedName(attribute), attribute.getNodeValue());
            }
        }
        return attributes;
    }

    private static ExpandedName expandedName(Node node) {
        String localName = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        return new ExpandedName(Objects.toString(node.getNamespaceURI(), ""), localName);
    }

    private static boolean preservesSpace(Element element, boolean inheritedValue) {
        if (!element.hasAttributeNS(XMLConstants.XML_NS_URI, "space")) {
            return inheritedValue;
        }
        return switch (element.getAttributeNS(XMLConstants.XML_NS_URI, "space")) {
            case "preserve" -> true;
            case "default" -> false;
            default -> inheritedValue;
        };
    }

    private static List<Node> significantChildren(Element element, boolean preserveSpace) {
        List<Node> children = new ArrayList<>();
        Node child = element.getFirstChild();
        while (child != null) {
            if (preserveSpace || !isTextNode(child) || !child.getTextContent().isBlank()) {
                children.add(child);
            }
            child = child.getNextSibling();
        }
        return children;
    }

    private static void requireFields(
            JsonNode matcher,
            String pointer,
            String matcherName,
            Set<String> supportedFields
    ) {
        Set<String> configuredFields = fieldNames(matcher);
        if (!configuredFields.equals(supportedFields)) {
            throw invalidMatcher(
                    pointer,
                    "matcher '" + matcherName + "' supports fields " + supportedFields
                            + ", but found " + configuredFields
            );
        }
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> result = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String childPointer(String pointer, String fieldName) {
        return pointer + "/" + fieldName.replace("~", "~0").replace("/", "~1");
    }

    private static String displayPointer(String pointer) {
        return pointer.isEmpty() ? "<root>" : pointer;
    }

    private static String typeMismatch(
            String description,
            String pointer,
            String expectedType,
            JsonNode actual
    ) {
        return description + " Value at '" + displayPointer(pointer) + "' must be " + expectedType
                + ", but was " + (actual == null ? "missing" : actual) + ".";
    }

    private static IllegalArgumentException invalidMatcher(String pointer, String reason) {
        return new IllegalArgumentException(
                "Invalid snapshot matcher at '" + displayPointer(pointer) + "': " + reason + "."
        );
    }

    private record ExpandedName(String namespaceUri, String localName) {
    }
}
