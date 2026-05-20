package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.json.v2.JsonDataSource;
import io.atlasmap.v2.*;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.spi.Registry;
import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.mapper.atlasmap.CustomAtlasContext;
import org.qubership.integration.platform.engine.mapper.atlasmap.ValidationResult;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MapperProcessorTest {

    private static final String ROUTE_ID = "dde821b7-c8d5-4262-afa0-b9c56dc17ca3";
    private static final String SESSION_ID = "24d4ebb6-41e0-4a6b-840b-4bd5a2cb47af";
    private static final String DEFAULT_BODY = "{\"x\":1}";
    private static final String DEFAULT_MAPPING_CONFIG = "{}";

    @Mock
    Message message;
    @Mock
    CamelContext camelContext;
    @Mock
    AtlasSession session;

    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.basic();

        when(exchange.getMessage()).thenReturn(message);
        when(exchange.getContext()).thenReturn(camelContext);
        when(exchange.getFromRouteId()).thenReturn(ROUTE_ID);
    }

    @Test
    void shouldProcessWhenCacheDisabledAndNoErrors() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingWithPropsAndExtraTarget();
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        Map<String, Object> exchangeProps = new HashMap<>();
        Map<String, Object> headers = new HashMap<>();
        exchangeProps.put("p1", "p1_value");
        headers.put("h1", "h1_value");

        stubExchangeState(exchangeProps, headers, DEFAULT_BODY);
        stubMappingProperties(false);
        wireExchangeSetPropertyToMap(exchangeProps);

        Map<String, Object> sourceProps = new HashMap<>();
        Map<String, Object> targetProps = new HashMap<>();
        targetProps.put("tp1", "tp1_new");
        targetProps.put("th1", "th1_new");

        when(session.getMapping()).thenReturn(mapping);
        when(session.getAudits()).thenReturn(new Audits());
        when(session.getSourceProperties()).thenReturn(sourceProps);
        when(session.getTargetProperties()).thenReturn(targetProps);
        when(session.getTargetDocument("target")).thenReturn("{\"ok\":true}");
        when(session.getTargetDocument("outProp")).thenReturn("OUT_VAL");

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            processor.process(exchange);
        }

        assertEquals("p1_value", sourceProps.get("p1"));
        assertEquals("h1_value", sourceProps.get("h1"));
        assertEquals("tp1_new", exchangeProps.get("tp1"));
        assertEquals("th1_new", headers.get("th1"));

        verify(message).setHeader("Content-Type", "application/json");
        verify(message).setBody("{\"ok\":true}");
        assertEquals("OUT_VAL", exchangeProps.get("outProp"));
    }

    @Test
    void shouldParseMappingWhenCacheEnabledAndMappingIdMissingAndNotTouchCamelRegistry() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingBasicJsonTarget();
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, true);

        stubExchangeState();
        stubMappingProperties(DEFAULT_MAPPING_CONFIG, "", false);
        stubHappySession(mapping, "{\"ok\":true}");

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            processor.process(exchange);
        }

        verify(camelContext, never()).getRegistry();
        verify(message).setHeader("Content-Type", "application/json");
        verify(message).setBody("{\"ok\":true}");
    }

    @Test
    void shouldUseCachedMappingAndCachedValidationAndSkipRegistryHelperBinds() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        MapperProcessor processor = processor(objectMapper, true);

        Registry camelRegistry = mock(Registry.class);
        AtlasMapping cachedMapping = mappingBasicJsonTarget();
        ValidationResult cachedValidation = ValidationResult.builder()
                .validations(List.of(new Validation()))
                .build();

        stubExchangeState();
        stubMappingProperties(DEFAULT_MAPPING_CONFIG, "m1", false);
        stubHappySession(cachedMapping, "{\"ok\":true}");

        when(camelContext.getRegistry()).thenReturn(camelRegistry);
        when(camelRegistry.lookupByNameAndType("m1", AtlasMapping.class)).thenReturn(cachedMapping);
        when(camelRegistry.lookupByNameAndType("m1", ValidationResult.class)).thenReturn(cachedValidation);

        try (MockedConstruction<CustomAtlasContext> ctor = mockAtlasContext()) {

            processor.process(exchange);

            CustomAtlasContext created = ctor.constructed().getFirst();
            verify(created).setCachedValidationResult(same(cachedValidation));
        }

        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldBindMappingAndValidationWhenCacheEnabledAndNothingCached() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping parsedMapping = mappingBasicJsonTarget();
        doReturn(parsedMapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, true);

        Registry camelRegistry = mock(Registry.class);
        ValidationResult validationResult = ValidationResult.builder()
                .validations(List.of(new Validation()))
                .build();

        stubExchangeState();
        stubMappingProperties(DEFAULT_MAPPING_CONFIG, "m1", false);
        stubHappySession(parsedMapping, "{\"ok\":true}");

        when(camelContext.getRegistry()).thenReturn(camelRegistry);
        when(camelRegistry.lookupByNameAndType("m1", AtlasMapping.class)).thenReturn(null);
        when(camelRegistry.lookupByNameAndType("m1", ValidationResult.class)).thenReturn(null);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext(validationResult)) {
            processor.process(exchange);

            verify(camelRegistry).bind("m1", AtlasMapping.class, parsedMapping);
            verify(camelRegistry).bind("m1", ValidationResult.class, validationResult);
        }
    }

    @Test
    void shouldBindMappingButNotValidationWhenDeploymentIdNullAndContextValidationNull() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping parsedMapping = mappingBasicJsonTarget();
        doReturn(parsedMapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, true);

        Registry camelRegistry = mock(Registry.class);

        stubExchangeState();
        stubMappingProperties(DEFAULT_MAPPING_CONFIG, "m1", false);
        stubHappySession(parsedMapping, "{\"ok\":true}");

        when(camelContext.getRegistry()).thenReturn(camelRegistry);
        when(camelRegistry.lookupByNameAndType("m1", AtlasMapping.class)).thenReturn(null);
        when(camelRegistry.lookupByNameAndType("m1", ValidationResult.class)).thenReturn(null);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext((ValidationResult) null)) {
            processor.process(exchange);

            verify(camelRegistry).bind("m1", AtlasMapping.class, parsedMapping);
            verify(camelRegistry, never()).bind(eq("m1"), eq(ValidationResult.class), any());
        }
    }

    @Test
    void shouldSetXmlContentTypeWhenTargetDataSourceIsNotJson() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingNonJsonTarget();
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(false);
        stubHappySession(mapping, "<ok/>");

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            processor.process(exchange);
        }

        verify(message).setHeader("Content-Type", "application/xml");
        verify(message).setBody("<ok/>");
    }

    @Test
    void shouldThrowExceptionWhenThrowExceptionEnabledAndTransformationErrorsPresent() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingBasicJsonTarget();
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(true);
        stubSessionId();

        Audits audits = new Audits();
        audits.getAudit().add(audit("Failed to apply field action: X", "/a"));
        audits.getAudit().add(audit("Failed to convert field value Y", "/b"));
        audits.getAudit().add(audit("Expression processing error Z", "/c"));

        when(session.getMapping()).thenReturn(mapping);
        when(session.getAudits()).thenReturn(audits);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            Exception ex = assertThrows(Exception.class, () -> processor.process(exchange));
            String msg = ex.getMessage();

            assertNotNull(msg);
            assertTrue(msg.contains("Failed to perform mapping:"), msg);
            assertTrue(msg.contains("Failed to apply field action:"), msg);
            assertTrue(msg.contains("Failed to convert field value"), msg);
            assertTrue(msg.contains("Expression processing error"), msg);
        }
    }

    @Test
    void shouldNotThrowOnTransformationErrorsWhenThrowExceptionDisabled() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingBasicJsonTarget();
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(false);
        stubSessionId();

        Audits audits = new Audits();
        audits.getAudit().add(audit("Failed to apply field action: X", "/a"));
        audits.getAudit().add(audit("Failed to convert field value Y", "/b"));
        audits.getAudit().add(audit("Expression processing error Z", "/c"));
        audits.getAudit().add(audit("Some other error", "/d"));

        stubSession(mapping, audits, "{\"ok\":true}");

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            assertDoesNotThrow(() -> processor.process(exchange));
        }
    }

    @Test
    void shouldThrowUnsupportedOperationExceptionWhenUnsupportedErrorPresent() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingBasicJsonTarget();
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(false);
        stubSessionId();

        Audits audits = new Audits();
        audits.getAudit().add(audit("Nested JSON array is not supported in XYZ", "/arr"));

        when(session.getMapping()).thenReturn(mapping);
        when(session.getAudits()).thenReturn(audits);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            UnsupportedOperationException ex =
                    assertThrows(UnsupportedOperationException.class, () -> processor.process(exchange));

            assertTrue(ex.getMessage().contains("Failed to perform mapping:"));
            assertTrue(ex.getMessage().contains("Nested JSON array is not supported"));
        }
    }

    @Test
    void shouldSerializeVariablesAndFallbackToTextNodeWhenInvalidJson() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingWithExtraSource("variables");
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));
        doThrow(new JsonProcessingException("bad") { }).when(objectMapper).readTree("abc");

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(false);
        stubHappySession(mapping, "{\"ok\":true}");

        HashMap<String, String> vars = new HashMap<>();
        vars.put("plainVal", "abc");
        vars.put("jsonVal", "{\"x\":2}");
        when(exchange.getProperty("variables")).thenReturn(vars);

        ArgumentCaptor<String> docCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            processor.process(exchange);
        }

        verify(session).setSourceDocument(eq("variables"), docCaptor.capture());

        JsonNode doc = ObjectMappers.getObjectMapper().readTree(docCaptor.getValue());
        assertEquals("abc", doc.get("plainVal").asText());
        assertEquals(2, doc.get("jsonVal").get("x").asInt());
    }

    @Test
    void shouldSetNullForMissingExtraSourcePropertyDocument() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingWithExtraSource("missingProp");
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(false);
        stubHappySession(mapping, "{\"ok\":true}");

        when(exchange.getProperty("missingProp")).thenReturn(null);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            processor.process(exchange);
        }

        verify(session).setSourceDocument(eq("missingProp"), isNull());
    }

    @Test
    void shouldThrowRuntimeExceptionWhenUnableToSerializeComplexProperty() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping mapping = mappingWithExtraSource("badProp");
        doReturn(mapping).when(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingConfigOnly(DEFAULT_MAPPING_CONFIG);

        Object bad = new Object();
        when(exchange.getProperty("badProp")).thenReturn(bad);
        doThrow(new JsonProcessingException("boom") { }).when(objectMapper).writeValueAsString(same(bad));

        when(session.getMapping()).thenReturn(mapping);

        try (MockedConstruction<CustomAtlasContext> ignored = mockAtlasContext()) {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> processor.process(exchange));

            assertTrue(ex.getMessage().startsWith("Unable to read complex property: "));
            assertTrue(ex.getMessage().contains("badProp"));
            assertNotNull(ex.getCause());
        }
    }

    @Test
    void shouldPropagateIOExceptionWhenMappingCannotBeParsed() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.readValue(any(Reader.class), eq(AtlasMapping.class)))
                .thenThrow(new IOException("bad mapping"));

        MapperProcessor processor = processor(objectMapper, false);

        stubMappingConfigOnly(DEFAULT_MAPPING_CONFIG);

        IOException ex = assertThrows(IOException.class, () -> processor.process(exchange));
        assertEquals("bad mapping", ex.getMessage());
    }

    @Test
    void shouldParseAtlasMappingFromMappingConfigPropertyWhenCacheDisabled() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping expectedMapping = mappingBasicJsonTarget();
        String mappingConfig = ObjectMappers.getObjectMapper().writeValueAsString(expectedMapping);

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(mappingConfig, false);
        stubHappySession(expectedMapping, "{\"ok\":true}");

        AtomicReference<AtlasMapping> parsedMappingRef = new AtomicReference<>();

        try (MockedConstruction<CustomAtlasContext> ignored =
                     mockConstruction(CustomAtlasContext.class, (ctx, context) -> {
                         parsedMappingRef.set(extractAtlasMappingArgument(context));
                         when(ctx.createSession()).thenReturn(session);
                     })) {

            processor.process(exchange);
        }

        verify(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        AtlasMapping parsedMapping = parsedMappingRef.get();
        assertNotNull(parsedMapping);
        assertEquals(List.of("source", "target"),
                parsedMapping.getDataSource().stream().map(DataSource::getId).toList());

        verify(message).setHeader("Content-Type", "application/json");
        verify(message).setBody("{\"ok\":true}");
    }

    @Test
    void shouldParseEscapedAtlasMappingFromMappingConfigPropertyWhenCacheDisabled() throws Exception {
        ObjectMapper objectMapper = spy(ObjectMappers.getObjectMapper());
        AtlasMapping expectedMapping = mappingBasicJsonTarget();
        String rawMappingConfig = ObjectMappers.getObjectMapper().writeValueAsString(expectedMapping);
        String escapedMappingConfig = StringEscapeUtils.escapeXml11(rawMappingConfig);

        MapperProcessor processor = processor(objectMapper, false);

        stubExchangeState();
        stubMappingProperties(escapedMappingConfig, false);
        stubHappySession(expectedMapping, "{\"ok\":true}");

        AtomicReference<AtlasMapping> parsedMappingRef = new AtomicReference<>();

        try (MockedConstruction<CustomAtlasContext> ignored =
                     mockConstruction(CustomAtlasContext.class, (ctx, context) -> {
                         parsedMappingRef.set(extractAtlasMappingArgument(context));
                         when(ctx.createSession()).thenReturn(session);
                     })) {

            processor.process(exchange);
        }

        verify(objectMapper).readValue(any(Reader.class), eq(AtlasMapping.class));

        AtlasMapping parsedMapping = parsedMappingRef.get();
        assertNotNull(parsedMapping);
        assertEquals(List.of("source", "target"),
                parsedMapping.getDataSource().stream().map(DataSource::getId).toList());

        verify(message).setHeader("Content-Type", "application/json");
        verify(message).setBody("{\"ok\":true}");
    }

    private MapperProcessor processor(ObjectMapper objectMapper, boolean cacheEnabled) {
        MapperProcessor processor = new MapperProcessor(objectMapper);
        processor.cacheEnabled = cacheEnabled;
        return processor;
    }

    private Map<String, Object> stubExchangeState() {
        return stubExchangeState(new HashMap<>(), new HashMap<>(), DEFAULT_BODY);
    }

    private Map<String, Object> stubExchangeState(
            Map<String, Object> exchangeProps,
            Map<String, Object> headers,
            String body
    ) {
        when(exchange.getProperties()).thenReturn(exchangeProps);
        when(message.getHeaders()).thenReturn(headers);
        when(message.getBody(String.class)).thenReturn(body);
        return exchangeProps;
    }

    private void stubSessionId() {
        when(exchange.getProperty(Properties.SESSION_ID, String.class)).thenReturn(SESSION_ID);
    }

    private void stubMappingConfigOnly(String mappingConfig) {
        when(exchange.getProperty(Properties.MAPPING_CONFIG, String.class)).thenReturn(mappingConfig);
    }

    private void stubMappingProperties(boolean throwException) {
        stubMappingProperties(DEFAULT_MAPPING_CONFIG, throwException);
    }

    private void stubMappingProperties(String mappingConfig, boolean throwException) {
        when(exchange.getProperty(Properties.MAPPING_CONFIG, String.class)).thenReturn(mappingConfig);
        when(exchange.getProperty(Properties.MAPPING_THROW_EXCEPTION, false, Boolean.class)).thenReturn(throwException);
    }

    private void stubMappingProperties(String mappingConfig, String mappingId, boolean throwException) {
        when(exchange.getProperty(Properties.MAPPING_CONFIG, String.class)).thenReturn(mappingConfig);
        when(exchange.getProperty(Properties.MAPPING_ID, String.class)).thenReturn(mappingId);
        when(exchange.getProperty(Properties.MAPPING_THROW_EXCEPTION, false, Boolean.class)).thenReturn(throwException);
    }

    private void stubHappySession(AtlasMapping mapping, String targetDocument) {
        when(session.getMapping()).thenReturn(mapping);
        when(session.getAudits()).thenReturn(new Audits());
        when(session.getTargetDocument("target")).thenReturn(targetDocument);
    }

    private void stubSession(AtlasMapping mapping, Audits audits, String targetDocument) {
        when(session.getMapping()).thenReturn(mapping);
        when(session.getAudits()).thenReturn(audits);
        when(session.getTargetDocument("target")).thenReturn(targetDocument);
    }

    private MockedConstruction<CustomAtlasContext> mockAtlasContext() {
        return mockConstruction(CustomAtlasContext.class, (ctx, c) -> when(ctx.createSession()).thenReturn(session));
    }

    private MockedConstruction<CustomAtlasContext> mockAtlasContext(ValidationResult validationResult) {
        return mockConstruction(CustomAtlasContext.class, (ctx, c) -> {
            when(ctx.createSession()).thenReturn(session);
            when(ctx.getCachedValidationResult()).thenReturn(validationResult);
        });
    }

    private void wireExchangeSetPropertyToMap(Map<String, Object> exchangeProps) {
        doAnswer(invocation -> {
            exchangeProps.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(exchange).setProperty(anyString(), any());
    }

    private AtlasMapping extractAtlasMappingArgument(MockedConstruction.Context context) {
        return context.arguments().stream()
                .filter(AtlasMapping.class::isInstance)
                .map(AtlasMapping.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static AtlasMapping mappingBasicJsonTarget() {
        AtlasMapping mapping = new AtlasMapping();

        JsonDataSource source = new JsonDataSource();
        source.setId("source");
        source.setName("source");
        source.setDataSourceType(DataSourceType.SOURCE);

        JsonDataSource target = new JsonDataSource();
        target.setId("target");
        target.setName("target");
        target.setDataSourceType(DataSourceType.TARGET);

        mapping.getDataSource().add(source);
        mapping.getDataSource().add(target);
        mapping.setProperties(new io.atlasmap.v2.Properties());

        return mapping;
    }

    private static AtlasMapping mappingNonJsonTarget() {
        AtlasMapping mapping = new AtlasMapping();

        JsonDataSource source = new JsonDataSource();
        source.setId("source");
        source.setName("source");
        source.setDataSourceType(DataSourceType.SOURCE);

        DataSource target = new DataSource();
        target.setId("target");
        target.setName("target");
        target.setDataSourceType(DataSourceType.TARGET);

        mapping.getDataSource().add(source);
        mapping.getDataSource().add(target);
        mapping.setProperties(new io.atlasmap.v2.Properties());

        return mapping;
    }

    private static AtlasMapping mappingWithExtraSource(String name) {
        AtlasMapping mapping = mappingBasicJsonTarget();

        DataSource extra = new DataSource();
        extra.setId(name);
        extra.setName(name);
        extra.setDataSourceType(DataSourceType.SOURCE);

        mapping.getDataSource().add(extra);
        return mapping;
    }

    private static AtlasMapping mappingWithPropsAndExtraTarget() {
        AtlasMapping mapping = new AtlasMapping();

        JsonDataSource source = new JsonDataSource();
        source.setId("source");
        source.setName("source");
        source.setDataSourceType(DataSourceType.SOURCE);

        JsonDataSource target = new JsonDataSource();
        target.setId("target");
        target.setName("target");
        target.setDataSourceType(DataSourceType.TARGET);

        DataSource outProp = new DataSource();
        outProp.setId("outProp");
        outProp.setName("outProp");
        outProp.setDataSourceType(DataSourceType.TARGET);

        mapping.getDataSource().addAll(List.of(source, target, outProp));

        io.atlasmap.v2.Properties properties = new io.atlasmap.v2.Properties();
        properties.getProperty().add(property("p1", DataSourceType.SOURCE, "camelExchangeProperty"));
        properties.getProperty().add(property("h1", DataSourceType.SOURCE, "current"));
        properties.getProperty().add(property("tp1", DataSourceType.TARGET, "camelExchangeProperty"));
        properties.getProperty().add(property("th1", DataSourceType.TARGET, "current"));
        mapping.setProperties(properties);

        return mapping;
    }

    private static Property property(String name, DataSourceType type, String scope) {
        Property property = new Property();
        property.setName(name);
        property.setDataSourceType(type);
        property.setScope(scope);
        property.setFieldType(FieldType.STRING);
        return property;
    }

    private static Audit audit(String message, String path) {
        Audit audit = new Audit();
        audit.setStatus(AuditStatus.ERROR);
        audit.setMessage(message);
        audit.setPath(path);
        return audit;
    }
}
