package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.validate.BaseModuleValidationService;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.AtlasMapping;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.DataSourceType;
import io.atlasmap.v2.Document;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.xml.core.XmlFieldWriter;
import io.atlasmap.xml.v2.XmlDataSource;
import io.atlasmap.xml.v2.XmlField;
import io.atlasmap.xml.v2.XmlNamespace;
import io.atlasmap.xml.v2.XmlNamespaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.mapper.atlasmap.xml.QipAtlasXmlFieldWriter;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipXmlAtlasModuleTest {

    @Mock
    private AtlasInternalSession session;

    private TestableQipXmlAtlasModule module;

    @BeforeEach
    void setUp() {
        module = new TestableQipXmlAtlasModule("doc1", "atlas:cip:xml");
    }

    @Test
    void shouldCreateValidationService() {
        BaseModuleValidationService<?> validationService = module.exposedValidationService();

        assertNotNull(validationService);
        assertInstanceOf(QipXmlValidationService.class, validationService);
    }

    @Test
    void shouldInspectXmlDocumentAndConvertComplexTypesToFieldGroups() {
        BiFunction<AtlasInternalSession, String, Document> inspectionService = module.exposedInspectionService();

        Document document = inspectionService.apply(session, "<root><name>Alex</name><customer><id>10</id></customer></root>");

        assertNotNull(document);
        assertNotNull(document.getFields());
        assertEquals(2, document.getFields().getField().size());

        Field first = document.getFields().getField().get(0);
        Field second = document.getFields().getField().get(1);

        assertInstanceOf(XmlField.class, first);
        assertEquals("/root/name", first.getPath());

        assertInstanceOf(FieldGroup.class, second);
        FieldGroup customer = (FieldGroup) second;
        assertEquals("/root/customer", customer.getPath());
        assertEquals(1, customer.getField().size());

        Field child = customer.getField().get(0);
        assertInstanceOf(XmlField.class, child);
        assertEquals("/root/customer/id", child.getPath());
    }

    @Test
    void shouldAddAuditAndReturnEmptyDocumentWhenInspectionFails() {
        BiFunction<AtlasInternalSession, String, Document> inspectionService = module.exposedInspectionService();

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            Document document = inspectionService.apply(session, "<root>");

            assertNotNull(document);
            assertNotNull(document.getFields());
            assertTrue(document.getFields().getField().isEmpty());

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq("doc1"),
                    anyString(),
                    eq(AuditStatus.ERROR),
                    eq("")
            ));
        }
    }

    @Test
    void shouldSetXmlFieldWriterUsingMatchingTargetDataSource() throws Exception {
        AtlasMapping mapping = new AtlasMapping();
        XmlDataSource other = xmlDataSource("other", DataSourceType.TARGET, null, "<other></other>");
        XmlDataSource target = xmlDataSource("doc1", DataSourceType.TARGET, namespaces("ns", "urn:test"), "<root xmlns:ns=\"urn:test\"></root>");
        mapping.getDataSource().add(other);
        mapping.getDataSource().add(target);

        when(session.getMapping()).thenReturn(mapping);

        module.processPreTargetExecution(session);

        ArgumentCaptor<XmlFieldWriter> captor = ArgumentCaptor.forClass(XmlFieldWriter.class);
        verify(session).setFieldWriter(eq("doc1"), captor.capture());

        XmlFieldWriter writer = captor.getValue();
        assertInstanceOf(QipAtlasXmlFieldWriter.class, writer);

        Map<String, String> namespaces = namespacesFrom(writer);
        assertEquals(1, namespaces.size());
        assertEquals("urn:test", namespaces.get("ns"));
    }

    @Test
    void shouldSetXmlFieldWriterUsingTargetDataSourceWithNullId() throws Exception {
        AtlasMapping mapping = new AtlasMapping();
        XmlDataSource target = xmlDataSource(null, DataSourceType.TARGET, namespaces("a", "urn:a"), "<root xmlns:a=\"urn:a\"></root>");
        mapping.getDataSource().add(target);

        when(session.getMapping()).thenReturn(mapping);

        module.processPreTargetExecution(session);

        ArgumentCaptor<XmlFieldWriter> captor = ArgumentCaptor.forClass(XmlFieldWriter.class);
        verify(session).setFieldWriter(eq("doc1"), captor.capture());

        XmlFieldWriter writer = captor.getValue();
        assertInstanceOf(QipAtlasXmlFieldWriter.class, writer);

        Map<String, String> namespaces = namespacesFrom(writer);
        assertEquals(1, namespaces.size());
        assertEquals("urn:a", namespaces.get("a"));
    }

    @Test
    void shouldSetXmlFieldWriterWithEmptyNamespacesWhenMatchingTargetDataSourceHasNoNamespaces() throws Exception {
        AtlasMapping mapping = new AtlasMapping();
        XmlDataSource target = xmlDataSource("doc1", DataSourceType.TARGET, null, "<root></root>");
        mapping.getDataSource().add(target);

        when(session.getMapping()).thenReturn(mapping);

        module.processPreTargetExecution(session);

        ArgumentCaptor<XmlFieldWriter> captor = ArgumentCaptor.forClass(XmlFieldWriter.class);
        verify(session).setFieldWriter(eq("doc1"), captor.capture());

        XmlFieldWriter writer = captor.getValue();
        assertInstanceOf(QipAtlasXmlFieldWriter.class, writer);

        Map<String, String> namespaces = namespacesFrom(writer);
        assertTrue(namespaces.isEmpty());
    }

    private static XmlDataSource xmlDataSource(String id, io.atlasmap.v2.DataSourceType type, XmlNamespaces namespaces, String template) {
        XmlDataSource dataSource = new XmlDataSource();
        dataSource.setId(id);
        dataSource.setDataSourceType(type);
        dataSource.setXmlNamespaces(namespaces);
        dataSource.setTemplate(template);
        return dataSource;
    }

    private static XmlNamespaces namespaces(String alias, String uri) {
        XmlNamespaces namespaces = new XmlNamespaces();
        XmlNamespace namespace = new XmlNamespace();
        namespace.setAlias(alias);
        namespace.setUri(uri);
        namespaces.getXmlNamespace().add(namespace);
        return namespaces;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> namespacesFrom(XmlFieldWriter writer) throws Exception {
        Class<?> type = writer.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("namespaces");
                field.setAccessible(true);
                return (Map<String, String>) field.get(writer);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("namespaces");
    }

    @AtlasModuleDetail(
            name = "QipXmlAtlasModule",
            uri = "atlas:cip:xml",
            modes = { "SOURCE", "TARGET" },
            dataFormats = { "xml" },
            configPackages = { "org.qubership.integration.platform.engine.mapper.atlasmap" }
    )
    private static class TestableQipXmlAtlasModule extends QipXmlAtlasModule {
        private final String docId;
        private final String uri;

        TestableQipXmlAtlasModule(String docId, String uri) {
            this.docId = docId;
            this.uri = uri;
        }

        @Override
        public String getDocId() {
            return docId;
        }

        @Override
        public String getUri() {
            return uri;
        }

        BaseModuleValidationService<?> exposedValidationService() {
            return super.getValidationService();
        }

        BiFunction<AtlasInternalSession, String, Document> exposedInspectionService() {
            return super.getInspectionService();
        }
    }
}
