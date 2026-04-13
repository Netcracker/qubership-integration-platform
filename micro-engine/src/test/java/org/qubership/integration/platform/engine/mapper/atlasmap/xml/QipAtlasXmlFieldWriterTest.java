package org.qubership.integration.platform.engine.mapper.atlasmap.xml;

import io.atlasmap.api.AtlasException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipAtlasXmlFieldWriterTest {

    @Test
    void shouldInitializeNamespacesWhenNullAndCollectNamespaceAwareAttributes() throws Exception {
        TestableQipAtlasXmlFieldWriter writer = new TestableQipAtlasXmlFieldWriter(new LinkedHashMap<>());
        writer.setNamespacesMap(null);

        Document document = parseXml(
                "<root xmlns=\"urn:root\" xmlns:a=\"urn:a\"/>",
                true
        );

        writer.seedNamespaces(document);

        Map<String, String> namespaces = writer.getNamespacesMap();
        assertEquals(2, namespaces.size());
        assertEquals("urn:root", namespaces.get(""));
        assertEquals("urn:a", namespaces.get("a"));
    }

    @Test
    void shouldCollectNamespacesRecursivelyFromChildElements() throws Exception {
        TestableQipAtlasXmlFieldWriter writer = new TestableQipAtlasXmlFieldWriter(new LinkedHashMap<>());
        Document document = parseXml(
                "<root xmlns=\"urn:root\"><child xmlns:b=\"urn:b\"><leaf xmlns:c=\"urn:c\"/></child></root>",
                true
        );

        writer.seedNamespaces(document);

        Map<String, String> namespaces = writer.getNamespacesMap();
        assertEquals(3, namespaces.size());
        assertEquals("urn:root", namespaces.get(""));
        assertEquals("urn:b", namespaces.get("b"));
        assertEquals("urn:c", namespaces.get("c"));
    }

    @Test
    void shouldCollectNamespacesFromNonNamespaceAwareAttributes() throws Exception {
        TestableQipAtlasXmlFieldWriter writer = new TestableQipAtlasXmlFieldWriter(new LinkedHashMap<>());
        Document document = parseXml(
                "<root xmlns=\"urn:root\" xmlns:a=\"urn:a\"><child xmlns:b=\"urn:b\"/></root>",
                false
        );

        writer.seedNamespaces(document);

        Map<String, String> namespaces = writer.getNamespacesMap();
        assertEquals(3, namespaces.size());
        assertEquals("urn:root", namespaces.get(""));
        assertEquals("urn:a", namespaces.get("a"));
        assertEquals("urn:b", namespaces.get("b"));
    }

    @Test
    void shouldPreserveExistingNamespacesAndIgnoreRegularAttributes() throws Exception {
        Map<String, String> initialNamespaces = new LinkedHashMap<>();
        initialNamespaces.put("existing", "urn:existing");

        TestableQipAtlasXmlFieldWriter writer = new TestableQipAtlasXmlFieldWriter(initialNamespaces);
        Document document = parseXml(
                "<root attr=\"value\"><child other=\"123\"/></root>",
                false
        );

        writer.seedNamespaces(document);

        Map<String, String> namespaces = writer.getNamespacesMap();
        assertEquals(1, namespaces.size());
        assertEquals("urn:existing", namespaces.get("existing"));
    }

    private static Document parseXml(String xml, boolean namespaceAware) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static class TestableQipAtlasXmlFieldWriter extends QipAtlasXmlFieldWriter {

        TestableQipAtlasXmlFieldWriter(Map<String, String> namespaces) throws AtlasException {
            super(TestableQipAtlasXmlFieldWriter.class.getClassLoader(), namespaces, "<root/>");
        }

        void seedNamespaces(Document document) {
            seedDocumentNamespaces(document);
        }

        Map<String, String> getNamespacesMap() {
            return namespaces;
        }

        void setNamespacesMap(Map<String, String> namespaces) {
            this.namespaces = namespaces;
        }
    }
}
