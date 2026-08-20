package org.qubership.integration.platform.runtime.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.assertNodeEquals;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.findInput;

/**
 * Verifies the read path serves schemas on demand through the extractor now that {@code requestSchema}
 * / {@code responseSchemas} are {@code @Transient}. The oracle is the shared conformance corpus, so the
 * populated fields (and the granular {@code /schemas/request} and {@code /schemas/response} readers)
 * must reproduce the shape import once materialized.
 */
class OperationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEGENERATE_ASYNCAPI_SOURCE = """
            asyncapi: 2.6.0
            info:
              title: Test
              version: 1.0.0
            channels:
              test/topic:
                publish:
                  operationId: testOp
                  message:
                    oneOf:
                      - ~
            """;

    private final OperationSchemaExtractor extractor = ExtractorTestParsers.extractor();

    @Test
    void rejectsBlankModelId() {
        OperationService service = serviceWith(mock(OperationRepository.class));
        List<String> sortColumns = List.of("name");

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> service.getOperationsByModel("   ", 0, 20, "", sortColumns));

        assertTrue(exception.getMessage().contains("modelId"), exception.getMessage());
    }

    /**
     * {@code Root.get(name)} throws {@code IllegalArgumentException} for an attribute the entity does not map, and
     * Spring translates only {@code PersistenceException}, so an unchecked sort column used to surface as a 500.
     */
    @Test
    void rejectsSortColumnTheEntityDoesNotMap() {
        OperationService service = serviceWith(mock(OperationRepository.class));
        List<String> sortColumns = List.of("name", "active");

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> service.getOperationsByModel("model-1", 0, 20, "", sortColumns));

        assertTrue(exception.getMessage().contains("active"), exception.getMessage());
    }

    /**
     * Spring trims the tokens of a single comma-separated value, but repeated parameters bind element by element and
     * keep their padding. A padded name has to be trimmed before the allowlist check and before it reaches
     * {@code Root.get(" path")}.
     */
    @Test
    void trimsSortColumnsBeforeCheckingAndQuerying() {
        OperationRepository repository = mock(OperationRepository.class);
        when(repository.getOperations("model-1", List.of("name", "path"), 0, 20)).thenReturn(List.of());
        OperationService service = serviceWith(repository);

        service.getOperationsByModel("model-1", 0, 20, "", List.of("name", " path"));

        verify(repository).getOperations("model-1", List.of("name", "path"), 0, 20);
    }

    /**
     * The listing used to call {@code findBySystemAndOperationId} once per row — one query per operation, per
     * table render. The page must cost a single lookup whatever its size.
     */
    @Test
    void listLooksChainsUpOncePerPageNotOncePerOperation() {
        ElementHelperService elementHelperService = mock(ElementHelperService.class);
        OperationService service = serviceWithPage(elementHelperService,
                operationWithId("op-1"), operationWithId("op-2"), operationWithId("op-3"));

        service.getOperationsByModel("model-1", 0, 20, "", List.of());

        verify(elementHelperService).findChainsGroupedByOperationId(Set.of("op-1", "op-2", "op-3"));
        verify(elementHelperService, never()).findBySystemAndOperationId(any(), any());
    }

    @Test
    void listAssignsEachOperationTheChainsOfItsOwnId() {
        Chain first = Chain.builder().id("chain-1").build();
        Chain second = Chain.builder().id("chain-2").build();
        ElementHelperService elementHelperService = mock(ElementHelperService.class);
        when(elementHelperService.findChainsGroupedByOperationId(anySet()))
                .thenReturn(Map.of("op-1", List.of(first), "op-2", List.of(first, second)));
        OperationService service = serviceWithPage(elementHelperService,
                operationWithId("op-1"), operationWithId("op-2"), operationWithId("op-3"));

        List<Operation> result = service.getOperationsByModel("model-1", 0, 20, "", List.of());

        assertEquals(List.of("chain-1"), chainIds(result.get(0)));
        assertEquals(List.of("chain-1", "chain-2"), chainIds(result.get(1)));
        assertTrue(result.get(2).getChains().isEmpty(), "an unused operation must carry no chains");
    }

    @Test
    void emptyPageStillIssuesNoPerOperationLookup() {
        ElementHelperService elementHelperService = mock(ElementHelperService.class);
        OperationService service = serviceWithPage(elementHelperService);

        service.getOperationsByModel("model-1", 0, 20, "", List.of());

        verify(elementHelperService).findChainsGroupedByOperationId(Set.of());
        verify(elementHelperService, never()).findBySystemAndOperationId(any(), any());
    }

    @Test
    void infoAndFullSchemasReproduceExpectedSchemas() throws Exception {
        JsonNode expected = loadExpected("openapi31-aperture-dam", "createAsset.expected.json");
        Operation operation = buildOperation("openapi31-aperture-dam", expected, OperationProtocol.HTTP);
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNodeEquals(expected.get("requestSchema"), result.getRequestSchema(), "requestSchema");
        assertNodeEquals(expected.get("responseSchemas"), result.getResponseSchemas(), "responseSchemas");
    }

    @Test
    void requestSchemaReaderReturnsExpectedContentTypeSchema() throws Exception {
        JsonNode expected = loadExpected("openapi31-aperture-dam", "createAsset.expected.json");
        Operation operation = buildOperation("openapi31-aperture-dam", expected, OperationProtocol.HTTP);
        OperationService service = serviceFor(operation);

        JsonNode result = service.getRequestSchema(operation.getId(), "application/json");

        assertNodeEquals(expected.get("requestSchema").get("application/json"), result, "requestSchema[application/json]");
    }

    @Test
    void responseSchemaReaderReturnsExpectedCodeAndContentTypeSchema() throws Exception {
        JsonNode expected = loadExpected("openapi31-aperture-dam", "getAssetById.expected.json");
        Operation operation = buildOperation("openapi31-aperture-dam", expected, OperationProtocol.HTTP);
        OperationService service = serviceFor(operation);

        JsonNode result = service.getResponseSchema(operation.getId(), "application/json", "200");

        assertNodeEquals(expected.get("responseSchemas").get("200").get("application/json"), result,
                "responseSchemas[200][application/json]");
    }

    @Test
    void lightModeReturnsKeysOnly() throws Exception {
        JsonNode expected = loadExpected("openapi31-aperture-dam", "createAsset.expected.json");
        Operation operation = buildOperation("openapi31-aperture-dam", expected, OperationProtocol.HTTP);
        OperationService service = serviceFor(operation);

        Operation light = service.getOperationLight(operation.getId());

        assertEquals(fieldNames(expected.get("requestSchema")), light.getRequestSchema().keySet());
        light.getRequestSchema().values()
                .forEach(value -> assertTrue(value.isObject() && value.isEmpty(),
                        "light request schema value must be an empty object"));
        assertEquals(fieldNames(expected.get("responseSchemas")), light.getResponseSchemas().keySet());
        light.getResponseSchemas().values().forEach(byCode -> {
            assertTrue(byCode.isObject(), "light response schema must be an object");
            byCode.fields().forEachRemaining(contentType ->
                    assertTrue(contentType.getValue().isObject() && contentType.getValue().isEmpty(),
                            "light response content-type value must be an empty object"));
        });
    }

    @Test
    void graphqlOperationYieldsNullSchemasWithoutError() throws Exception {
        JsonNode expected = loadExpected("graphql-catalog", "createCustomer.expected.json");
        Operation operation = buildOperation("graphql-catalog", expected, OperationProtocol.GRAPHQL);
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void wsdlOperationYieldsNullSchemasWithoutError() throws Exception {
        JsonNode expected = loadExpected("wsdl-hello-service", "sayHello.expected.json");
        Operation operation = buildOperation("wsdl-hello-service", expected, OperationProtocol.SOAP);
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void noMatchingOperationYieldsNullSchemasWithoutError() throws Exception {
        // The re-parsed source has no operation at this path/method: the match failure degrades to null
        // schemas rather than failing the read.
        String rawSource = Files.readString(findInput(corpusRoot().resolve("openapi30-orders")));
        Operation operation = buildOperationWithSource(rawSource, OperationProtocol.HTTP, "/does-not-exist", "GET");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void unparseableSourceYieldsNullSchemasWithoutError() {
        // A source that fails re-parsing (corrupted, or edited since import) degrades to null schemas
        // rather than failing the read.
        Operation operation = buildOperationWithSource(
                "this is not a specification", OperationProtocol.HTTP, "/x", "GET");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void corruptHttpSourceYieldsNullSchemasWithoutError() {
        // A corrupt HTTP source makes the swagger-parser deserializer throw a raw SnakeException; it must
        // still degrade to null schemas rather than fail the read (regression: this used to escape as an
        // HTTP 500, unlike GraphQL/AsyncAPI/Protobuf which already wrapped their native parse failures).
        Operation operation = buildOperationWithSource(
                "{ \"openapi\": \"3.0.0\"", OperationProtocol.HTTP, "/x", "GET");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void nullSpecificationSliceYieldsNullWithoutError() {
        // An operation whose retained specification slice is null (e.g. WSDL) must read back as null
        // rather than NPE to a 500 in getSpecification.
        Operation operation = buildOperationWithSource("{}", OperationProtocol.SOAP, "/x", "GET");
        operation.setSpecification(null);
        OperationService service = serviceFor(operation);

        assertNull(service.getSpecification(operation.getId()));
    }

    @Test
    void malformedGraphqlSourceYieldsNullSchemasWithoutError() {
        // Malformed SDL throws graphql.parser.InvalidSyntaxException from the parser, neither a
        // SpecificationImportException nor an IllegalArgumentException; it must still degrade to null
        // schemas rather than fail the read (regression: this used to escape as an HTTP 500).
        Operation operation = buildOperationWithSource(
                "this is not a specification", OperationProtocol.GRAPHQL, "/x", "GET");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void malformedGrpcSourceYieldsNullSchemasWithoutError() {
        // Malformed .proto content throws IllegalStateException from Wire's parser, neither a
        // SpecificationImportException nor an IllegalArgumentException; it must still degrade to null
        // schemas rather than fail the read (regression: this used to escape as an HTTP 500).
        Operation operation = buildOperationWithProtoSource(
                "this is not a valid proto file {{{", "/x", "GET");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void malformedAsyncapiSourceYieldsNullSchemasWithoutError() {
        // A source that is not an AsyncAPI document at all must degrade to null schemas rather than fail
        // the read.
        Operation operation = buildOperationWithSource(
                "this is not a specification", OperationProtocol.KAFKA, "test/topic", "publish");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void degenerateAsyncapiSourceYieldsNullSchemasWithoutError() {
        // `oneOf: [~]` is valid YAML that parses into a message list holding a null entry. Resolving it
        // throws a raw runtime exception from the async resolver, neither a SpecificationImportException
        // nor an IllegalArgumentException; the read must still degrade to null schemas, not a 500.
        Operation operation = buildOperationWithSource(
                DEGENERATE_ASYNCAPI_SOURCE, OperationProtocol.KAFKA, "test/topic", "publish");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    @Test
    void metamodelProtocolYieldsNullSchemasWithoutError() {
        // METAMODEL carries no request/response schemas, like SOAP; the read must still succeed.
        Operation operation = buildOperationWithSource("{}", OperationProtocol.METAMODEL, "/x", "GET");
        OperationService service = serviceFor(operation);

        Operation result = service.getOperationWithSchemas(operation.getId());

        assertNull(result.getRequestSchema());
        assertNull(result.getResponseSchemas());
    }

    private OperationService serviceFor(Operation operation) {
        OperationRepository repository = mock(OperationRepository.class);
        when(repository.findById(operation.getId())).thenReturn(Optional.of(operation));
        return serviceWith(repository);
    }

    private OperationService serviceWithPage(ElementHelperService elementHelperService, Operation... page) {
        OperationRepository repository = mock(OperationRepository.class);
        when(repository.getOperations("model-1", List.of(), 0, 20)).thenReturn(new ArrayList<>(List.of(page)));
        return new OperationService(repository, JSON, elementHelperService, extractor);
    }

    private static Operation operationWithId(String id) {
        return Operation.builder().id(id).build();
    }

    private static List<String> chainIds(Operation operation) {
        return operation.getChains().stream().map(Chain::getId).sorted().toList();
    }

    private OperationService serviceWith(OperationRepository repository) {
        return new OperationService(
                repository,
                JSON,
                mock(ElementHelperService.class),
                extractor);
    }

    private Operation buildOperation(String caseDir, JsonNode expected, OperationProtocol protocol) throws IOException {
        String rawSource = Files.readString(findInput(corpusRoot().resolve(caseDir)));
        return buildOperationWithSource(
                rawSource, protocol, expected.get("path").asText(), expected.get("method").asText());
    }

    private Operation buildOperationWithSource(
            String rawSource, OperationProtocol protocol, String path, String method) {
        IntegrationSystem system = IntegrationSystem.builder().protocol(protocol).build();
        ApiGroup group = ApiGroup.builder().system(system).build();
        SystemModel model = SystemModel.builder().apiGroup(group).build();
        SpecificationSource source = SpecificationSource.builder().isMainSource(true).source(rawSource).build();
        model.addProvidedSpecificationSource(source);
        return Operation.builder()
                .path(path)
                .method(method)
                .systemModel(model)
                .build();
    }

    // GRPC needs a source named "*.proto" to be picked up by the parser; other protocols don't care.
    private Operation buildOperationWithProtoSource(String rawSource, String path, String method) {
        IntegrationSystem system = IntegrationSystem.builder().protocol(OperationProtocol.GRPC).build();
        ApiGroup group = ApiGroup.builder().system(system).build();
        SystemModel model = SystemModel.builder().apiGroup(group).build();
        SpecificationSource source = SpecificationSource.builder()
                .name("bad.proto").isMainSource(true).source(rawSource).build();
        model.addProvidedSpecificationSource(source);
        return Operation.builder()
                .path(path)
                .method(method)
                .systemModel(model)
                .build();
    }

    private static JsonNode loadExpected(String caseDir, String expectedFile) throws IOException {
        return JSON.readTree(corpusRoot().resolve(caseDir).resolve(expectedFile).toFile());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

}
