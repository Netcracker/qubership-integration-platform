package org.qubership.integration.platform.engine.mapper.atlasmap;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.validate.BaseModuleValidationService;
import io.atlasmap.json.core.JsonFieldWriter;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.Document;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.mapper.atlasmap.json.QipAtlasJsonFieldReader;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipJsonAtlasModuleTest {

    @Mock
    private AtlasInternalSession session;
    @Mock
    private JsonFieldWriter writer;
    @Mock
    private QipJsonAtlasModuleOptions options;

    private TestableQipJsonAtlasModule module;

    @BeforeEach
    void setUp() {
        module = new TestableQipJsonAtlasModule("doc1", "atlas:cip:json");
    }

    @Test
    void shouldCreateValidationService() {
        BaseModuleValidationService<?> validationService = module.exposedValidationService();

        assertNotNull(validationService);
        assertInstanceOf(QipJsonValidationService.class, validationService);
    }

    @Test
    void shouldInspectJsonDocumentAndConvertComplexTypesToFieldGroups() {
        BiFunction<AtlasInternalSession, String, Document> inspectionService = module.exposedInspectionService();

        Document document = inspectionService.apply(session, "{\"name\":\"Alex\",\"customer\":{\"id\":10}}");

        assertNotNull(document);
        assertNotNull(document.getFields());
        assertEquals(2, document.getFields().getField().size());

        Field first = document.getFields().getField().get(0);
        Field second = document.getFields().getField().get(1);

        assertInstanceOf(JsonField.class, first);
        assertEquals("/name", first.getPath());

        assertInstanceOf(FieldGroup.class, second);
        FieldGroup customer = (FieldGroup) second;
        assertEquals("/customer", customer.getPath());
        assertEquals(1, customer.getField().size());

        Field child = customer.getField().get(0);
        assertInstanceOf(JsonField.class, child);
        assertEquals("/customer/id", child.getPath());
    }

    @Test
    void shouldAddAuditAndReturnEmptyDocumentWhenInspectionFails() {
        BiFunction<AtlasInternalSession, String, Document> inspectionService = module.exposedInspectionService();

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            Document document = inspectionService.apply(session, "not-json");

            assertNotNull(document);
            assertNotNull(document.getFields());
            assertTrue(document.getFields().getField().isEmpty());

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq("doc1"),
                    contains("JSON data must begin with either '{' or '['"),
                    eq(AuditStatus.ERROR),
                    eq("")
            ));
        }
    }

    @Test
    void shouldEnableWriteBigDecimalAsPlainInPreTargetExecution() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        when(session.getFieldWriter("doc1", JsonFieldWriter.class)).thenReturn(writer);
        when(writer.getObjectMapper()).thenReturn(objectMapper);

        module.processPreTargetExecution(session);

        assertTrue(objectMapper.isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN));
    }

    @Test
    void shouldSetJsonFieldReaderInPreSourceExecutionWhenSourceDocumentIsString() throws Exception {
        when(session.getSourceDocument("doc1")).thenReturn("{\"name\":\"Alex\"}");

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            module.processPreSourceExecution(session);
        }

        verify(session).setFieldReader(eq("doc1"), any(QipAtlasJsonFieldReader.class));
    }

    @Test
    void shouldSerializeTargetDocumentToStringWhenOptionEnabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ContainerNode<?> rootNode = objectMapper.createObjectNode().put("a", 1);

        when(session.getFieldWriter("doc1", JsonFieldWriter.class)).thenReturn(writer);
        when(writer.getObjectMapper()).thenReturn(objectMapper);
        doReturn(rootNode).when(writer).getRootNode();

        try (MockedStatic<QipJsonAtlasModuleOptionsDecoder> decoder = mockStatic(QipJsonAtlasModuleOptionsDecoder.class)) {
            decoder.when(() -> QipJsonAtlasModuleOptionsDecoder.decode("atlas:cip:json")).thenReturn(options);
            when(options.isSerializeTargetDocument()).thenReturn(true);

            module.processPostTargetExecution(session);
        }

        verify(session).setTargetDocument("doc1", "{\"a\":1}");
        verify(session).removeFieldWriter("doc1");
    }

    @Test
    void shouldStoreTargetDocumentAsJsonNodeWhenOptionDisabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ContainerNode<?> rootNode = objectMapper.createObjectNode().put("a", 1);

        when(session.getFieldWriter("doc1", JsonFieldWriter.class)).thenReturn(writer);
        when(writer.getObjectMapper()).thenReturn(objectMapper);
        doReturn(rootNode).when(writer).getRootNode();

        try (MockedStatic<QipJsonAtlasModuleOptionsDecoder> decoder = mockStatic(QipJsonAtlasModuleOptionsDecoder.class)) {
            decoder.when(() -> QipJsonAtlasModuleOptionsDecoder.decode("atlas:cip:json")).thenReturn(options);
            when(options.isSerializeTargetDocument()).thenReturn(false);

            module.processPostTargetExecution(session);
        }

        verify(session).setTargetDocument("doc1", rootNode);
        verify(session).removeFieldWriter("doc1");
    }

    @Test
    void shouldAddWarnAuditWhenNoWriterExistsInPostTargetExecution() throws Exception {
        when(session.getFieldWriter("doc1", JsonFieldWriter.class)).thenReturn(null);

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            module.processPostTargetExecution(session);

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq("doc1"),
                    contains("No target document created for DataSource"),
                    eq(AuditStatus.WARN),
                    eq(null)
            ));
        }

        verify(session).removeFieldWriter("doc1");
    }

    @Test
    void shouldAddWarnAuditWhenRootNodeIsNullInPostTargetExecution() throws Exception {
        when(session.getFieldWriter("doc1", JsonFieldWriter.class)).thenReturn(writer);
        doReturn(null).when(writer).getRootNode();

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            module.processPostTargetExecution(session);

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq("doc1"),
                    contains("No target document created for DataSource"),
                    eq(AuditStatus.WARN),
                    eq(null)
            ));
        }

        verify(session).removeFieldWriter("doc1");
    }

    @Test
    void shouldAddErrorAuditWhenSerializationFailsInPostTargetExecution() throws Exception {
        ObjectMapper objectMapper = spy(new ObjectMapper());
        ObjectNode rootNode = new ObjectMapper().createObjectNode().put("a", 1);

        when(session.getFieldWriter("doc1", JsonFieldWriter.class)).thenReturn(writer);
        when(writer.getObjectMapper()).thenReturn(objectMapper);
        doReturn(rootNode).when(writer).getRootNode();

        try (MockedStatic<QipJsonAtlasModuleOptionsDecoder> decoder = mockStatic(QipJsonAtlasModuleOptionsDecoder.class);
             MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            decoder.when(() -> QipJsonAtlasModuleOptionsDecoder.decode("atlas:cip:json")).thenReturn(options);
            when(options.isSerializeTargetDocument()).thenReturn(true);
            doThrow(new JsonProcessingException("boom") { })
                    .when(objectMapper).writeValueAsString(rootNode);

            module.processPostTargetExecution(session);

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq("doc1"),
                    eq("boom"),
                    eq(AuditStatus.ERROR),
                    eq(null)
            ));
        }

        verify(session, never()).removeFieldWriter("doc1");
    }

    @AtlasModuleDetail(
            name = "QipJsonAtlasModule",
            uri = "atlas:cip:json",
            modes = { "SOURCE", "TARGET" },
            dataFormats = { "json" },
            configPackages = { "org.qubership.integration.platform.engine.mapper.atlasmap" }
    )
    private static class TestableQipJsonAtlasModule extends QipJsonAtlasModule {
        private final String docId;
        private final String uri;

        TestableQipJsonAtlasModule(String docId, String uri) {
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
