package org.qubership.integration.platform.runtime.catalog.service.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.findInput;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.readInput;

/**
 * Covers the edges of {@link OperationSchemaExtractor} that the corpus cannot express: a missing
 * operation, an unparseable source, protocols with no schemas to extract, and protobuf (which the
 * corpus does not carry, because no {@code .proto} source is persisted).
 *
 * <p>Corpus parity for every protocol, OpenAPI included, is asserted once in
 * {@link OperationSchemaExtractorParityTest}. Keep golden-file comparisons there; this class stays
 * behavioral.
 */
class OperationSchemaExtractorTest {

    private final OperationSchemaExtractor extractor = ExtractorTestParsers.extractor();

    @Test
    void throwsWhenOperationIsMissing() throws Exception {
        Path caseDir = corpusRoot().resolve("openapi30-orders");
        String rawSource = Files.readString(findInput(caseDir));

        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(rawSource, OperationProtocol.HTTP, "/does-not-exist", "GET"));
    }

    @Test
    void throwsOnUnparseableSource() {
        assertThrows(SpecificationImportException.class,
                () -> extractor.extract("this is not a specification", OperationProtocol.HTTP, "/x", "GET"));
    }

    @Test
    void blankSourceDegradesToEmptySchemasWithoutError() {
        // No content to parse: degrades to empty schemas instead of handing a blank string to the parser.
        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract("   ", OperationProtocol.HTTP, "/x", "GET");

        assertNull(result.specification());
        assertNull(result.requestSchema());
        assertNull(result.responseSchemas());
    }

    @Test
    void metamodelProtocolDegradesToEmptySchemasWithoutError() {
        // METAMODEL carries no request/response schemas, like SOAP.
        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract("{}", OperationProtocol.METAMODEL, "/x", "GET");

        assertNull(result.specification());
        assertNull(result.requestSchema());
        assertNull(result.responseSchemas());
    }

    /**
     * The conformance corpus has no protobuf case (no {@code .proto} source is persisted), so this
     * inline spec is the extractor's protobuf coverage. It also pins the match key: the path is the
     * {@code java_package}-qualified {@code package.Service}, not the raw proto package.
     */
    private static final String PROTO_SOURCE = """
            syntax = "proto3";
            package demo.orders;
            option java_package = "com.acme.orders";

            message CreateOrderRequest {
              string customer_id = 1;
            }
            message CreateOrderResponse {
              string order_id = 1;
            }
            service OrderService {
              rpc CreateOrder(CreateOrderRequest) returns (CreateOrderResponse);
            }
            """;

    @Test
    void extractsProtobufSchemasByJavaPackageQualifiedService() {
        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract(PROTO_SOURCE, OperationProtocol.GRPC, "com.acme.orders.OrderService", "CreateOrder");

        assertNotNull(result.requestSchema());
        assertTrue(result.requestSchema().containsKey("application/json"));
        assertNotNull(result.responseSchemas());
        assertTrue(result.responseSchemas().containsKey("200"));
        assertNotNull(result.specification());
        assertEquals("OrderService.CreateOrder", result.specification().get("operationId").asText());
    }

    @Test
    void protobufMatchRequiresJavaPackageNotRawPackage() {
        // The path must honor java_package (com.acme.orders), so the raw proto package fails to match.
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(PROTO_SOURCE, OperationProtocol.GRPC, "demo.orders.OrderService", "CreateOrder"));
    }

    // A gRPC spec split across files: service.proto references a message declared in types.proto.
    private static final String PROTO_SERVICE = """
            syntax = "proto3";
            package demo;
            import "types.proto";

            message GetUserRequest {
              string id = 1;
            }
            message GetUserResponse {
              Address address = 1;
            }
            service UserService {
              rpc GetUser(GetUserRequest) returns (GetUserResponse);
            }
            """;

    private static final String PROTO_TYPES = """
            syntax = "proto3";
            package demo;

            message Address {
              string city = 1;
              string country = 2;
            }
            """;

    @Test
    void protobufResolvesTypesAcrossMultipleSources() {
        SpecificationSource service = protoSource("service.proto", PROTO_SERVICE);
        SpecificationSource types = protoSource("types.proto", PROTO_TYPES);

        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract(List.of(service, types), OperationProtocol.GRPC, "demo.UserService", "GetUser");

        JsonNode definitions = result.responseSchemas().get("200").get("application/json").get("definitions");
        assertTrue(definitions.has("demo.GetUserResponse"));
        assertTrue(definitions.has("demo.Address"),
                "the cross-file message type must be resolved into the schema definitions");
        assertEquals("#/definitions/demo.Address",
                definitions.get("demo.GetUserResponse").get("properties").get("address").get("$ref").asText());
    }

    @Test
    void protobufSingleSourceLeavesCrossFileTypeUnresolved() {
        // Passing only the main source (the pre-fix read path) drops the referenced type: the regression.
        SpecificationSource service = protoSource("service.proto", PROTO_SERVICE);

        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract(List.of(service), OperationProtocol.GRPC, "demo.UserService", "GetUser");

        JsonNode definitions = result.responseSchemas().get("200").get("application/json").get("definitions");
        assertFalse(definitions.has("demo.Address"));
    }

    private static SpecificationSource protoSource(String name, String content) {
        return SpecificationSource.builder()
                .name(name)
                .isMainSource("service.proto".equals(name))
                .source(content)
                .build();
    }

    // Two single-file specs for the main-source selection tests: only MAIN_SPEC carries /orders POST.
    private static final String MAIN_SPEC = """
            openapi: 3.0.3
            info:
              title: Main
              version: "1.0.0"
            paths:
              /orders:
                post:
                  operationId: createOrder
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              orderId:
                                type: string
            """;

    private static final String DECOY_SPEC = """
            openapi: 3.0.3
            info:
              title: Decoy
              version: "1.0.0"
            paths:
              /other:
                get:
                  operationId: getOther
                  responses:
                    "200":
                      description: ok
            """;

    @Test
    void httpSelectsFlaggedMainSourceRegardlessOfPosition() {
        // The flagged main sits SECOND behind a decoy that lacks /orders. Selection must honor the flag,
        // not the list position, so the operation is found.
        SpecificationSource decoy = httpSource(DECOY_SPEC, false);
        SpecificationSource main = httpSource(MAIN_SPEC, true);

        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract(List.of(decoy, main), OperationProtocol.HTTP, "/orders", "POST");

        assertNotNull(result.specification());
        assertEquals("createOrder", result.specification().get("operationId").asText());
    }

    @Test
    void httpFallsBackToFirstSourceWhenNoMainFlagged() {
        // No source flags itself main (legacy rows): the fallback takes the first of the list. On the read
        // path that order is fixed by SystemModel.specificationSources @OrderBy("id"), so the pick is stable.
        SpecificationSource first = httpSource(MAIN_SPEC, false);
        SpecificationSource second = httpSource(DECOY_SPEC, false);

        OperationSchemaExtractor.ExtractedSchemas result =
                extractor.extract(List.of(first, second), OperationProtocol.HTTP, "/orders", "POST");

        assertNotNull(result.specification());
        assertEquals("createOrder", result.specification().get("operationId").asText());
    }

    @Test
    void httpFallbackIgnoresTargetInNonFirstSource() {
        // The mirror of the case above: with no flag, the decoy is first and MAIN_SPEC second, so /orders is
        // unreachable — confirming only the first source is parsed, never a later one.
        SpecificationSource first = httpSource(DECOY_SPEC, false);
        SpecificationSource second = httpSource(MAIN_SPEC, false);
        List<SpecificationSource> sources = List.of(first, second);

        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(sources, OperationProtocol.HTTP, "/orders", "POST"));
    }

    private static SpecificationSource httpSource(String content, boolean main) {
        return SpecificationSource.builder()
                .isMainSource(main)
                .source(content)
                .build();
    }

    /**
     * The bulk seam the legacy export takes. One parse must cover every operation of the document and agree, field for
     * field, with what {@code extract} returns for the same operation after its own parse.
     */
    @Test
    void extractAllCoversEveryOperationAndAgreesWithExtract() {
        List<SpecificationSource> sources =
                List.of(httpSource(readInput(corpusRoot().resolve("openapi30-orders")), true));

        Map<OperationSchemaExtractor.OperationKey, OperationSchemaExtractor.ExtractedSchemas> all =
                extractor.extractAll(sources, OperationProtocol.HTTP, true);

        // Two of the three share /orders, so the key has to carry the method as well as the path.
        assertEquals(3, all.size());
        assertTrue(all.containsKey(OperationSchemaExtractor.OperationKey.of("/orders", "GET")));
        assertTrue(all.containsKey(OperationSchemaExtractor.OperationKey.of("/orders", "POST")));
        assertTrue(all.containsKey(OperationSchemaExtractor.OperationKey.of("/orders/{orderId}", "GET")));

        for (var entry : all.entrySet()) {
            OperationSchemaExtractor.OperationKey key = entry.getKey();
            // Lowercase on purpose: the key normalizes the method, as matchOperation compares it case-insensitively.
            OperationSchemaExtractor.ExtractedSchemas single = extractor.extract(
                    sources, OperationProtocol.HTTP, key.path(), key.method().toLowerCase(Locale.ROOT));

            assertEquals(single.specification(), entry.getValue().specification(), () -> "specification for " + key);
            assertEquals(single.requestSchema(), entry.getValue().requestSchema(), () -> "requestSchema for " + key);
            assertEquals(single.responseSchemas(), entry.getValue().responseSchemas(),
                    () -> "responseSchemas for " + key);
            assertFalse(entry.getValue().responseSchemas().isEmpty(), () -> "no response schemas for " + key);
        }
    }

    @Test
    void extractAllDegradesToAnEmptyMapWhenThereIsNothingToExtract() {
        SpecificationSource source = httpSource(MAIN_SPEC, true);

        // SOAP and METAMODEL carry no schemas by design, and a blank source has nothing to parse.
        assertTrue(extractor.extractAll(List.of(source), OperationProtocol.SOAP, true).isEmpty());
        assertTrue(extractor.extractAll(List.of(httpSource("   ", true)), OperationProtocol.HTTP, true).isEmpty());
    }

    @Test
    void extractAllPropagatesAParseFailure() {
        // The caller decides how to degrade, exactly as it does for the per-operation extract.
        List<SpecificationSource> sources = List.of(httpSource("this is not a specification", true));

        assertThrows(SpecificationImportException.class,
                () -> extractor.extractAll(sources, OperationProtocol.HTTP, true));
    }
}
