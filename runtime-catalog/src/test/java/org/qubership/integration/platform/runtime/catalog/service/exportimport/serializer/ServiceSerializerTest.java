package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.model.exportimport.system.ApiOperationDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.revert.RevertMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ContextServiceDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.MCPServiceDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.TestRevertMigrations;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.readInput;

class ServiceSerializerTest {

    private static final URI SCHEMA_URI = URI.create("http://qubership.org/schemas/product/qip/api.schema.yaml");
    private static final URI SPECIFICATION_SCHEMA_URI =
            URI.create("http://qubership.org/schemas/product/qip/specification.schema.yaml");

    private YAMLMapper yamlMapper;
    private SystemModelDtoMapper systemModelDtoMapper;

    @BeforeEach
    void setUp() {
        yamlMapper = new MapperAutoConfiguration().yamlExportImportMapper();
        systemModelDtoMapper = new SystemModelDtoMapper(SCHEMA_URI, new ApiOperationDtoMapper());
    }

    @Test
    void newFormatExportOmitsOperationSchemas() {
        ExportedSpecification exported = buildSerializer(false).serialize(modelWithMaterializedSchemas());
        ObjectNode node = exported.getObjectNode();

        JsonNode operations = node.path("content").path("operations");
        assertTrue(operations.isArray() && operations.size() == 1, "expected one exported operation");
        JsonNode operation = operations.get(0);

        assertEquals("get", operation.path("method").asText());
        assertEquals("/pets/{id}", operation.path("path").asText());
        assertFalse(operation.has("specification"), "specification must not be exported");
        assertFalse(operation.has("requestSchema"), "requestSchema must not be exported");
        assertFalse(operation.has("responseSchemas"), "responseSchemas must not be exported");
        assertFalse(containsKey(node, "requestSchema"));
        assertFalse(containsKey(node, "responseSchemas"));

        // ApiOperationDto flattens the typed payload into the api-operation shape rather than exporting it verbatim.
        assertFalse(operation.has("typed"), "typed must be flattened, not exported as a nested node");
        assertEquals("openapi", operation.path("type").asText());
        assertEquals("Add a new pet", operation.path("summary").asText());
        assertTrue(operation.has("isDeprecated"), "isDeprecated belongs to the openapi shape");
        // operationKind is the REST discriminator; the file uses "type". Server-only fields must never reach the file.
        assertFalse(operation.has("operationKind"), "operationKind must not leak into the export");
        assertFalse(operation.has("channel"), "channel does not belong to an openapi operation");
        // javaPackage and sdl carry only for protobuf and graphql; an openapi operation has neither.
        assertFalse(containsKey(node, "javaPackage"), "javaPackage belongs to protobuf, not openapi");
        assertFalse(containsKey(node, "sdl"), "sdl belongs to graphql, not openapi");
    }

    /**
     * The schemas are no longer stored, so the legacy file gets them back by re-deriving them from the raw source at
     * export time. Without them an older QIP imports operations with empty schemas.
     */
    @Test
    void legacyExportRestoresOperationSchemasFromTheSource() {
        ObjectNode node = buildSerializer(true).serialize(httpModel()).getObjectNode();

        JsonNode operations = node.path("operations");
        assertTrue(operations.isArray() && operations.size() == 3, "the revert flattens operations onto the root");

        JsonNode createOrder = operationBy(operations, "/orders", "POST");
        assertTrue(createOrder.path("requestSchema").has("application/json"), "the request body schema is restored");
        assertTrue(createOrder.path("responseSchemas").has("201"), "the response schemas are restored");
        assertEquals("stored", createOrder.path("specification").path("origin").asText(),
                "the persisted specification slice is left alone, not replaced by the extracted one");

        // Two operations share /orders, so a restored schema also proves the match key carries the method.
        JsonNode listOrders = operationBy(operations, "/orders", "GET");
        assertTrue(listOrders.path("requestSchema").has("parameters"), "query parameters are the GET request schema");
        assertTrue(listOrders.path("responseSchemas").has("200"));

        JsonNode getOrder = operationBy(operations, "/orders/{orderId}", "GET");
        assertTrue(getOrder.path("responseSchemas").has("404"), "every response code is restored, not just the first");
    }

    @Test
    void newFormatExportOfTheSameModelStillOmitsOperationSchemas() {
        ObjectNode node = buildSerializer(false).serialize(httpModel()).getObjectNode();

        assertTrue(containsKey(node, "path"), "the new format still carries structural operation data");
        assertFalse(containsKey(node, "requestSchema"), "the new format keeps schemas out of operations");
        assertFalse(containsKey(node, "responseSchemas"), "the new format keeps schemas out of operations");
    }

    /**
     * The archive ships the whole specification source, so the per-operation slice is redundant payload in the new
     * format. Import re-derives it from that source.
     */
    @Test
    void newFormatExportStripsTheOperationSpecification() {
        ObjectNode node = buildSerializer(false).serialize(httpModel()).getObjectNode();

        JsonNode operations = node.path("content").path("operations");
        assertTrue(operations.isArray() && operations.size() == 3, "every operation is still exported");
        for (JsonNode operation : operations) {
            assertFalse(operation.has("specification"),
                    "no operation carries a specification: " + operation.path("path").asText());
        }
        assertFalse(containsKey(node, "specification"), "the field is gone from the whole document");
    }

    /**
     * Nothing re-derives the slice for an older QIP, so the legacy file keeps carrying the stored value verbatim.
     * Regression guard for the legacy export fidelity work.
     */
    @Test
    void legacyExportStillCarriesTheStoredOperationSpecification() {
        ObjectNode node = buildSerializer(true).serialize(httpModel()).getObjectNode();

        JsonNode operations = node.path("operations");
        assertTrue(operations.isArray() && operations.size() == 3, "the revert flattens operations onto the root");
        for (JsonNode operation : operations) {
            assertEquals(yamlMapper.createObjectNode().put("origin", "stored"), operation.path("specification"),
                    "the stored value is exported as is for " + operation.path("path").asText());
        }
    }

    @Test
    void legacyExportOfASoapModelAddsNoSchemas() {
        // SOAP carries no request/response schemas by design, so the export must simply add nothing.
        ObjectNode node = assertDoesNotThrow(() -> buildSerializer(true).serialize(soapModel()).getObjectNode());

        assertTrue(containsKey(node, "method"), "legacy export must still carry structural operation data");
        assertFalse(containsKey(node, "requestSchema"));
        assertFalse(containsKey(node, "responseSchemas"));
    }

    /**
     * SOAP has no extraction path, so import cannot rebuild the slice from the source. The new format therefore has
     * to keep the stored value instead of stripping it the way it does for a protocol that can be rebuilt.
     */
    @Test
    void newFormatExportOfASoapModelKeepsTheStoredOperationSpecification() {
        ObjectNode node = buildSerializer(false).serialize(soapModel()).getObjectNode();

        JsonNode operations = node.path("content").path("operations");
        assertTrue(operations.isArray() && operations.size() == 1, "the operation is still exported");
        assertEquals(yamlMapper.createObjectNode().put("origin", "stored"), operations.get(0).path("specification"),
                "a protocol with no extraction path keeps its stored specification");
    }

    @Test
    void legacyExportDegradesAndReportsAnUnparseableSource() {
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceSerializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ObjectNode node = assertDoesNotThrow(
                    () -> buildSerializer(true).serialize(modelWithUnparseableSource()).getObjectNode());

            assertTrue(containsKey(node, "method"), "the operation itself still exports");
            assertFalse(containsKey(node, "requestSchema"), "a failed parse adds no schemas");
            assertFalse(containsKey(node, "responseSchemas"), "a failed parse adds no schemas");
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("sm-broken")),
                    "the export reports the offending model id rather than failing");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * Every parser core wraps its failures as {@code SpecificationImportException} with one fixed message, so the
     * cause is the only thing that says what actually broke. It has to reach the log.
     */
    @Test
    void legacyExportLogsTheCauseOfAParseFailure() {
        List<ILoggingEvent> events = capture(ServiceSerializer.class,
                () -> buildSerializer(true).serialize(modelWithMalformedSource()));

        ILoggingEvent event = onlyEventContaining(events,
                "Cannot derive operation schemas for the legacy export of specification sm-malformed");
        assertNotNull(event.getThrowableProxy(), "the throwable is attached, so the stack trace survives");
        assertEquals(SpecificationImportException.class.getName(), event.getThrowableProxy().getClassName());
        assertNotNull(event.getThrowableProxy().getCause(), "the wrapped cause is what names the real failure");
    }

    /**
     * A key miss is reachable — a source edited after its operations were created, a parser change altering
     * normalization — and costs exactly the schemas these exports exist to carry. One line per model, not per
     * operation.
     */
    @Test
    void legacyExportReportsUnmatchedOperationsOnce() {
        List<ILoggingEvent> events = capture(ServiceSerializer.class,
                () -> buildSerializer(true).serialize(modelWithUnmatchedOperations()));

        ILoggingEvent event = onlyEventContaining(events, "Legacy export of specification sm-unmatched: "
                + "1 of 3 operations did not match the parsed source and carry no request or response schemas");
        assertTrue(event.getFormattedMessage().contains("GET /gone"),
                "the unmatched key is named, not just counted: " + event.getFormattedMessage());
    }

    /**
     * The legacy file is the one that still carries schemas, so its export is where they have to be rebuilt.
     * The library parsers always produce them, so the observable guarantee is the document, not the call.
     */
    @Test
    void legacyExportCarriesTheRebuiltSchemas() {
        ObjectNode legacy = buildSerializer(true, ExtractorTestParsers.extractor()).serialize(httpModel())
                .getObjectNode();

        ObjectNode operation = (ObjectNode) legacy.path("operations").get(0);
        assertFalse(operation.path("requestSchema").isMissingNode(),
                "the reverted document states the request schema it was exported with");
        assertFalse(operation.path("responseSchemas").isMissingNode(),
                "and the response schemas alongside it");
    }

    /**
     * The serializer no longer holds a second copy of {@code qip.export.legacy-format}: it asks the migration
     * service, which owns the revert decision, so the branch and the node shape cannot disagree.
     */
    @Test
    void serializerFollowsTheLegacyFlagOfTheMigrationService() {
        ObjectNode legacy = buildSerializer(true).serialize(httpModel()).getObjectNode();
        ObjectNode current = buildSerializer(false).serialize(httpModel()).getObjectNode();

        assertTrue(legacy.path("operations").isArray(), "the reverted document flattens operations onto the root");
        assertTrue(current.path("content").path("operations").isArray(), "the api document keeps them under content");
    }

    @Test
    void legacyExportOfAModelWithoutSourceOmitsOperationSchemas() {
        // Nothing to derive from: the operation exports without schemas instead of failing.
        ObjectNode node = buildSerializer(true).serialize(modelWithMaterializedSchemas()).getObjectNode();

        assertTrue(containsKey(node, "method"), "legacy export must still carry structural operation data");
        assertFalse(containsKey(node, "requestSchema"), "legacy export must not carry requestSchema");
        assertFalse(containsKey(node, "responseSchemas"), "legacy export must not carry responseSchemas");
    }

    /**
     * A source that yields nothing at all costs every operation its schemas, and the per-operation report cannot see
     * it: with an empty extraction there is no key to miss. Losing a source is exactly how that happens.
     */
    @Test
    void legacyExportReportsASourceThatYieldedNoSchemas() {
        List<ILoggingEvent> events = capture(ServiceSerializer.class,
                () -> buildSerializer(true).serialize(modelWithMaterializedSchemas()));

        ILoggingEvent event = onlyEventContaining(events,
                "Legacy export of specification sm-1: the source yielded no schemas");
        assertTrue(event.getFormattedMessage().contains("all 1 operations"),
                "the report says how many operations are affected: " + event.getFormattedMessage());
    }

    @Test
    void legacyExportOfASchemalessProtocolReportsNothing() {
        // SOAP carries no schemas by design, so an empty extraction there is not worth a warning.
        List<ILoggingEvent> events = capture(ServiceSerializer.class,
                () -> buildSerializer(true).serialize(soapModel()));

        assertTrue(events.stream().noneMatch(event -> event.getFormattedMessage().contains("yielded no schemas")),
                "a protocol with no schema extraction must not be reported: "
                        + events.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void importDropsOperationSchemasFromLegacyFile() throws Exception {
        ObjectNode root = yamlMapper.createObjectNode();
        root.put("id", "sm-1");
        root.put("name", "Model 1");
        ObjectNode content = root.putObject("content");
        ArrayNode operations = content.putArray("operations");
        ObjectNode operation = operations.addObject();
        operation.put("id", "op-1");
        operation.put("name", "getPet");
        operation.put("method", "get");
        operation.put("path", "/pets/{id}");
        operation.set("specification", yamlMapper.createObjectNode().put("type", "object"));
        operation.set("requestSchema", yamlMapper.createObjectNode()
                .set("application/json", yamlMapper.createObjectNode().put("type", "object")));
        operation.set("responseSchemas", yamlMapper.createObjectNode()
                .set("200", yamlMapper.createObjectNode().put("type", "object")));

        SystemModelDto dto = yamlMapper.treeToValue(root, SystemModelDto.class);

        assertNotNull(dto.getContent());
        assertEquals(1, dto.getContent().getOperations().size());
        // ApiOperationDto has no requestSchema / responseSchemas fields, so a legacy file cannot carry them through.
        ApiOperationDto imported = dto.getContent().getOperations().get(0);
        assertEquals("get", imported.getMethod());
        assertEquals("/pets/{id}", imported.getPath());
        assertNotNull(imported.getSpecification());

        SystemModel model = systemModelDtoMapper.toInternalEntity(dto);
        assertEquals(1, model.getOperations().size());
        assertNull(model.getOperations().get(0).getRequestSchema());
        assertNull(model.getOperations().get(0).getResponseSchemas());
    }

    /**
     * A model with no source that has content exports a degraded, schema-invalid file rather than failing. The seeded
     * {@code Payments gRPC} model has this shape, so a full catalog export must not throw on it. The empty list is
     * reported with the model id, since it is what violates {@code minItems: 1} at the schema level.
     */
    @Test
    void exportDegradesAndReportsAModelWithNoUsableSource() {
        Logger logger = (Logger) LoggerFactory.getLogger(SystemModelDtoMapper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SystemModelDto dto = assertDoesNotThrow(
                    () -> systemModelDtoMapper.toExternalEntity(modelWithoutUsableSource()));

            assertTrue(dto.getContent().getSpecificationSources().isEmpty(),
                    "a source with no content is filtered out, so the export degrades to an empty list");
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("sm-empty")),
                    "the export reports the offending model id rather than dropping it silently");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void newFormatSpecificationFileNameUsesTheApiPostfix() {
        assertEquals("sm-1.api.qip.yaml",
                ExportImportUtils.generateSpecificationFileExportName("sm-1", "qip", false));
        assertEquals("specification-sm-1.yaml",
                ExportImportUtils.generateSpecificationFileExportName("sm-1", "qip", true));
    }

    @Test
    void newFormatExportCarriesTheApiShape() {
        ObjectNode node = yamlMapper.valueToTree(systemModelDtoMapper.toExternalEntity(apiModel()));

        assertEquals("http://qubership.org/schemas/product/qip/api.schema.yaml", node.path("$schema").asText());

        JsonNode content = node.path("content");
        assertEquals("openapi", content.path("specificationType").asText());
        assertFalse(content.has("specificationSources"), "the pre-api specificationSources field is not written");

        JsonNode specifications = content.path("specifications");
        assertTrue(specifications.isArray() && specifications.size() == 1, "one specification resource is exported");
        JsonNode resource = specifications.get(0);
        assertEquals("source-sm-1/api.yaml", resource.path("filePath").asText());
        assertTrue(resource.path("isRoot").asBoolean(), "the root source carries isRoot");
        assertFalse(resource.has("fileName"), "the pre-api fileName field is not written");
        assertFalse(resource.has("mainSource"), "the pre-api mainSource field is not written");

        JsonNode operation = content.path("operations").get(0);
        assertEquals("openapi", operation.path("type").asText());
        assertEquals("get", operation.path("method").asText());
        assertEquals("/pets/{id}", operation.path("path").asText());
    }

    /**
     * The hash describes the source file the archive already ships, and import recomputes it from that file, so
     * exporting it only adds a key that can disagree with the content next to it.
     */
    @Test
    void exportedSpecificationSourceCarriesNoSourceHash() {
        SystemModel model = apiModel();
        assertNotNull(model.getSpecificationSources().get(0).getSourceHash(), "the entity does hold a hash");

        SystemModelDto dto = systemModelDtoMapper.toExternalEntity(model);

        assertNull(dto.getContent().getSpecificationSources().get(0).getSourceHash(),
                "the mapper must not put the hash on the export DTO");

        JsonNode resource = yamlMapper.valueToTree(dto).path("content").path("specifications").get(0);
        Set<String> keys = new HashSet<>();
        resource.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "name", "filePath", "isRoot"), keys,
                "the exported resource keeps its four keys and gains none");
    }

    @Test
    void eachExportedEntityCarriesItsSchemaId() {
        ApplicationJsonSchemaProperties props = new ApplicationJsonSchemaProperties();

        assertEquals("http://qubership.org/schemas/product/qip/chain.schema.yaml", props.getChain());
        assertEquals("http://qubership.org/schemas/product/qip/service.schema.yaml", props.getService());
        assertEquals("http://qubership.org/schemas/product/qip/external-service.schema.yaml", props.getExternalService());
        assertEquals("http://qubership.org/schemas/product/qip/internal-service.schema.yaml", props.getInternalService());
        assertEquals("http://qubership.org/schemas/product/qip/implemented-service.schema.yaml",
                props.getImplementedService());
        assertEquals("http://qubership.org/schemas/product/qip/context-service.schema.yaml", props.getContextService());
        assertEquals("http://qubership.org/schemas/product/qip/mcp-service.schema.yaml", props.getMcpService());
        assertEquals("http://qubership.org/schemas/product/qip/specification-group.schema.yaml",
                props.getSpecificationGroup());
        assertEquals("http://qubership.org/schemas/product/qip/api-group.schema.yaml", props.getApiGroup());
        assertEquals("http://qubership.org/schemas/product/qip/specification.schema.yaml", props.getSpecification());
        assertEquals("http://qubership.org/schemas/product/qip/api.schema.yaml", props.getApi());

        // A service stamps the schema of its own type: since #553 that is where the exported document states it.
        IntegrationSystem system = IntegrationSystem.builder()
                .id("s1").name("Service").integrationSystemType(IntegrationSystemType.EXTERNAL).build();
        assertEquals(props.getExternalService(),
                new IntegrationSystemDtoMapper(new ServiceTypeFiles(props), List.of())
                        .toExternalEntity(system).getSchema().toString());

        ApiGroup group = ApiGroup.builder().id("g1").name("Group").system(system).build();
        assertEquals(props.getApiGroup(),
                new ApiGroupDtoMapper(URI.create(props.getApiGroup()))
                        .toExternalEntity(group).getSchema().toString());

        MCPSystem mcp = MCPSystem.builder().id("m1").name("MCP").build();
        assertEquals(props.getMcpService(),
                new MCPServiceDtoMapper(URI.create(props.getMcpService()), List.of())
                        .toExternalEntity(mcp).getSchema().toString());

        ContextSystem context = ContextSystem.builder().id("c1").name("Context").build();
        assertEquals(props.getContextService(),
                new ContextServiceDtoMapper(URI.create(props.getContextService()), List.of())
                        .toExternalEntity(context).getSchema().toString());

        assertEquals(props.getApi(),
                new SystemModelDtoMapper(URI.create(props.getApi()), new ApiOperationDtoMapper())
                        .toExternalEntity(apiModel()).getSchema().toString());
    }

    // The group export stamps ApiGroupDtoMapper's injected URI, and Spring resolves it from application.yml with
    // the @Value expression's own literal as the fallback. Three declarations, one value — assert they agree,
    // or a group exports a $schema no consumer dispatches on.
    @Test
    void theApiGroupSchemaIdIsDeclaredIdenticallyEverywhere() {
        String expected = new ApplicationJsonSchemaProperties().getApiGroup();

        assertEquals(expected, valueAnnotationFallback(ApiGroupDtoMapper.class),
                "ApiGroupDtoMapper's @Value fallback must match ApplicationJsonSchemaProperties.apiGroup");
        assertEquals(expected, applicationYamlSchemaDefault("api-group"),
                "application.yml's qip.json.schemas.api-group default must match "
                        + "ApplicationJsonSchemaProperties.apiGroup");
    }

    /** The literal after the {@code :} in the single constructor parameter's {@code @Value} expression. */
    private static String valueAnnotationFallback(Class<?> mapperClass) {
        assertEquals(1, mapperClass.getDeclaredConstructors().length, "expected a single constructor");
        Parameter parameter = mapperClass.getDeclaredConstructors()[0].getParameters()[0];
        Value value = parameter.getAnnotation(Value.class);
        assertNotNull(value, "expected a @Value on the schema URI parameter");
        return placeholderFallback(value.value());
    }

    private String applicationYamlSchemaDefault(String key) {
        try (InputStream stream = ServiceSerializerTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(stream, "application.yml is not on the test classpath");
            return placeholderFallback(yamlMapper.readTree(stream)
                    .path("qip").path("json").path("schemas").path(key).asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code ${NAME:value}} -> {@code value}. */
    private static String placeholderFallback(String placeholder) {
        assertTrue(placeholder.startsWith("${") && placeholder.endsWith("}"),
                () -> "not a property placeholder: " + placeholder);
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }

    @Test
    void exportedGroupListsTheIdsOfAllItsApis() {
        IntegrationSystem system = IntegrationSystem.builder().id("s1").name("Service").build();
        ApiGroup group = ApiGroup.builder()
                .id("g1")
                .name("Group")
                .system(system)
                .systemModels(new ArrayList<>(List.of(
                        SystemModel.builder().id("api-1").name("API 1").build(),
                        SystemModel.builder().id("api-2").name("API 2").build())))
                .build();

        ObjectNode node = yamlMapper.valueToTree(
                new ApiGroupDtoMapper(SCHEMA_URI).toExternalEntity(group));

        JsonNode apis = node.path("content").path("apis");
        assertTrue(apis.isArray() && apis.size() == 2, "both API ids are listed");
        List<String> ids = new ArrayList<>();
        apis.forEach(id -> ids.add(id.asText()));
        assertTrue(ids.contains("api-1") && ids.contains("api-2"), "apis[] lists every model id in the group");
    }

    private ServiceSerializer buildSerializer(boolean legacy) {
        return buildSerializer(legacy, ExtractorTestParsers.extractor());
    }

    // The legacy flag is set on the migration service alone: the serializer reads it from there.
    private ServiceSerializer buildSerializer(boolean legacy, OperationSchemaExtractor extractor) {
        List<RevertMigration> revertMigrations = TestRevertMigrations.all(SPECIFICATION_SCHEMA_URI);
        FileMigrationService fileMigrationService = new FileMigrationService(
                yamlMapper, new VersionsGetterService(List.of()), revertMigrations);
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", legacy);
        return new ServiceSerializer(
                yamlMapper, null, null, systemModelDtoMapper, fileMigrationService, extractor);
    }

    private static List<ILoggingEvent> capture(Class<?> loggerClass, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    // Matches on the message text, not on the model id alone: an unrelated warning can carry the same id.
    private static ILoggingEvent onlyEventContaining(List<ILoggingEvent> events, String text) {
        List<ILoggingEvent> matching = events.stream()
                .filter(event -> event.getFormattedMessage().contains(text))
                .toList();
        assertEquals(1, matching.size(), () -> "expected exactly one log event containing \"" + text + "\", got "
                + events.stream().map(ILoggingEvent::getFormattedMessage).toList());
        return matching.get(0);
    }

    private SystemModel modelWithMaterializedSchemas() {
        Map<String, JsonNode> requestSchema = Map.of(
                "application/json", yamlMapper.createObjectNode().put("type", "object"));
        Map<String, JsonNode> responseSchemas = Map.of(
                "200", yamlMapper.createObjectNode().put("type", "object"));
        Operation operation = Operation.builder()
                .id("op-1")
                .name("getPet")
                .method("get")
                .path("/pets/{id}")
                .typed(new OpenapiOperation("Add a new pet", "/pets/{id}", "get", false))
                .specification(yamlMapper.createObjectNode().put("type", "object"))
                .requestSchema(requestSchema)
                .responseSchemas(responseSchemas)
                .build();
        // The system carries the protocol the strip is gated on, as it does on every real export.
        IntegrationSystem system = IntegrationSystem.builder()
                .id("s-1").name("Service").protocol(OperationProtocol.HTTP).build();
        ApiGroup group = ApiGroup.builder().id("sg-1").name("group").system(system).build();
        return SystemModel.builder()
                .id("sm-1")
                .name("Model 1")
                .version("v1")
                .apiGroup(group)
                .operations(new ArrayList<>(List.of(operation)))
                .build();
    }

    private SystemModel apiModel() {
        Operation operation = Operation.builder()
                .id("op-1")
                .name("getPet")
                .method("get")
                .path("/pets/{id}")
                .typed(new OpenapiOperation("Add a new pet", "/pets/{id}", "get", false))
                .build();
        SpecificationSource source = SpecificationSource.builder()
                .id("src-1")
                .name("api.yaml")
                .isMainSource(true)
                .source("openapi: 3.0.0")
                .build();
        ApiGroup group = ApiGroup.builder().id("sg-1").name("group").build();
        SystemModel model = SystemModel.builder()
                .id("sm-1")
                .name("Model 1")
                .version("v1")
                .specificationType("openapi")
                .specificationVersion("3.0.0")
                .apiGroup(group)
                .operations(new ArrayList<>(List.of(operation)))
                .specificationSources(new ArrayList<>(List.of(source)))
                .build();
        source.setSystemModel(model);
        return model;
    }

    /** The corpus {@code openapi30-orders} case: three operations, two of them sharing the {@code /orders} path. */
    private SystemModel httpModel() {
        return modelWith(OperationProtocol.HTTP, "sm-http", readInput(corpusRoot().resolve("openapi30-orders")),
                List.of(
                        openapiOperation("op-list", "listOrders", "/orders", "get"),
                        openapiOperation("op-create", "createOrder", "/orders", "post"),
                        openapiOperation("op-get", "getOrder", "/orders/{orderId}", "get")));
    }

    private SystemModel soapModel() {
        Operation operation = Operation.builder()
                .id("op-soap")
                .name("SayHello")
                .specification(yamlMapper.createObjectNode().put("origin", "stored"))
                .build();
        operation.setTyped(new WsdlOperation("SOAP 1.1", "HelloBinding"));
        return modelWith(OperationProtocol.SOAP, "sm-soap", "<definitions/>", List.of(operation));
    }

    private SystemModel modelWithUnparseableSource() {
        return modelWith(OperationProtocol.HTTP, "sm-broken", "this is not a specification",
                List.of(openapiOperation("op-1", "getPet", "/pets/{id}", "get")));
    }

    // Truncated YAML: the swagger deserializer throws, and the core wraps that throw as the cause.
    private SystemModel modelWithMalformedSource() {
        return modelWith(OperationProtocol.HTTP, "sm-malformed", "openapi: 3.0.0\ninfo: {title: x",
                List.of(openapiOperation("op-1", "getPet", "/pets/{id}", "get")));
    }

    /** The {@code openapi30-orders} source with one operation the document does not declare. */
    private SystemModel modelWithUnmatchedOperations() {
        return modelWith(OperationProtocol.HTTP, "sm-unmatched", readInput(corpusRoot().resolve("openapi30-orders")),
                List.of(
                        openapiOperation("op-list", "listOrders", "/orders", "get"),
                        openapiOperation("op-create", "createOrder", "/orders", "post"),
                        openapiOperation("op-gone", "goneOrder", "/gone", "get")));
    }

    // setTyped, not the builder: only the setter derives method and path, as the parsers do.
    private Operation openapiOperation(String id, String name, String path, String method) {
        Operation operation = Operation.builder()
                .id(id)
                .name(name)
                .specification(yamlMapper.createObjectNode().put("origin", "stored"))
                .build();
        operation.setTyped(new OpenapiOperation(null, path, method, false));
        return operation;
    }

    private SystemModel modelWith(
            OperationProtocol protocol, String modelId, String rawSource, List<Operation> operations) {
        IntegrationSystem system = IntegrationSystem.builder().id("s-1").name("Service").protocol(protocol).build();
        ApiGroup group = ApiGroup.builder().id("sg-1").name("group").system(system).build();
        SpecificationSource source = SpecificationSource.builder()
                .id("src-1")
                .name("source.yaml")
                .isMainSource(true)
                .source(rawSource)
                .build();
        SystemModel model = SystemModel.builder()
                .id(modelId)
                .name("Model")
                .version("v1")
                .apiGroup(group)
                .operations(new ArrayList<>(operations))
                .specificationSources(new ArrayList<>(List.of(source)))
                .build();
        source.setSystemModel(model);
        return model;
    }

    private static JsonNode operationBy(JsonNode operations, String path, String method) {
        for (JsonNode operation : operations) {
            if (path.equals(operation.path("path").asText()) && method.equals(operation.path("method").asText())) {
                return operation;
            }
        }
        throw new AssertionError("No exported operation for path=" + path + ", method=" + method);
    }

    private SystemModel modelWithoutUsableSource() {
        Operation operation = Operation.builder()
                .id("op-1")
                .name("getPet")
                .typed(new OpenapiOperation("Add a new pet", "/pets/{id}", "get", false))
                .build();
        SpecificationSource sourceWithoutContent = SpecificationSource.builder()
                .id("src-1")
                .name("api.yaml")
                .build();
        ApiGroup group = ApiGroup.builder().id("sg-1").name("group").build();
        return SystemModel.builder()
                .id("sm-empty")
                .name("Payments gRPC")
                .version("v1")
                .apiGroup(group)
                .operations(new ArrayList<>(List.of(operation)))
                .specificationSources(new ArrayList<>(List.of(sourceWithoutContent)))
                .build();
    }

    private static boolean containsKey(JsonNode node, String key) {
        if (node.isObject()) {
            if (node.has(key)) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsKey(child, key)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsKey(child, key)) {
                    return true;
                }
            }
        }
        return false;
    }
}
