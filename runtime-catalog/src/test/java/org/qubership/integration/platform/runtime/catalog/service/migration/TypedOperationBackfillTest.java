package org.qubership.integration.platform.runtime.catalog.service.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.impl.WsdlSpecificationParser;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconstructs {@code typed} from the columns a pre-migration row already carries, then fills the two
 * reparse-only fields ({@code specificationVersion} and WSDL {@code protocol}/{@code binding}). Fixtures
 * follow the plan's Backfill sources table; the derivation assertions are the anti-regression gate that
 * protects deployed chains carrying {@code integrationOperationPath}.
 */
class TypedOperationBackfillTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TypedOperationBackfill backfill;

    @BeforeEach
    void setUp() {
        WsdlVersionParser wsdlVersionParser = new WsdlVersionParser(SAXParserFactory.newDefaultInstance());
        ProtocolExtractionService protocolExtractionService =
                new ProtocolExtractionService(new ObjectMapper(), new YAMLMapper(), wsdlVersionParser);
        WsdlSpecificationParser wsdlSpecificationParser =
                new WsdlSpecificationParser(new WsdlVersionParser(javax.xml.parsers.SAXParserFactory.newInstance()));
        backfill = new TypedOperationBackfill(protocolExtractionService, wsdlSpecificationParser);
    }

    @Test
    void openapiReadsSummaryPathAndLowercasedMethod() {
        Operation operation = operation("/orders", "POST", json("{\"summary\": \"Create an order\"}"));

        TypedOperation typed = backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.HTTP);

        OpenapiOperation openapi = assertInstanceOf(OpenapiOperation.class, typed);
        assertEquals("Create an order", openapi.summary());
        assertEquals("/orders", openapi.path());
        assertEquals("post", openapi.method());
        assertNull(openapi.isDeprecated());
    }

    @Test
    void openapiCarriesDeprecatedFlagFromSpecification() {
        Operation operation = operation("/pets/{id}", "GET", json("{\"summary\": \"Old\", \"deprecated\": true}"));

        OpenapiOperation openapi = assertInstanceOf(OpenapiOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.HTTP));
        assertEquals(Boolean.TRUE, openapi.isDeprecated());
    }

    @Test
    void openapiLeavesSummaryNullWhenSpecificationHasNone() {
        // 1 of 21 HTTP rows on the seeded dataset carries no summary; the field stays null rather than "".
        Operation operation = operation("/health", "GET", json("{}"));

        OpenapiOperation openapi = assertInstanceOf(OpenapiOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.HTTP));
        assertNull(openapi.summary());
        assertEquals("/health", openapi.path());
        assertEquals("get", openapi.method());
    }

    @Test
    void kafkaMapsPathToChannelAndKeepsMethod() {
        Operation operation = operation("shipping.dispatched", "subscribe", json("{\"topic\": \"shipping.dispatched\"}"));

        AsyncapiOperation async = assertInstanceOf(AsyncapiOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.KAFKA));
        assertNull(async.summary());
        assertEquals("shipping.dispatched", async.channel());
        assertEquals("subscribe", async.method());
    }

    @Test
    void amqpMapsPathToChannelAndKeepsMethod() {
        Operation operation = operation("invoice.issued", "receive", json("{\"maasClassifierName\": \"billing\"}"));

        AsyncapiOperation async = assertInstanceOf(AsyncapiOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.AMQP));
        assertNull(async.summary());
        assertEquals("invoice.issued", async.channel());
        assertEquals("receive", async.method());
    }

    @Test
    void graphqlReadsOperationTypeFromMethodAndSdlFromSpecification() {
        String sdl = "createCustomer(input: CustomerInput!): Customer!";
        Operation operation = operation(sdl, "mutation", json("{\"operation\": \"" + sdl + "\"}"));

        GraphqlOperation graphql = assertInstanceOf(GraphqlOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.GRAPHQL));
        assertEquals("mutation", graphql.operationType());
        assertEquals(sdl, graphql.sdl());
    }

    @Test
    void grpcSplitsPathAndReadsPackageFromSpecificationId() {
        Operation operation = operation("payments.PaymentService", "Pay", grpcSpecification("payments.PaymentService.Pay"));

        ProtobufOperation proto = assertInstanceOf(ProtobufOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.GRPC));
        assertEquals("payments", proto.packageName());
        assertEquals("PaymentService", proto.service());
        assertEquals("Pay", proto.rpcMethod());
        assertEquals("payments", proto.javaPackage());
    }

    @Test
    void grpcKeepsJavaPackageDistinctFromProtoPackage() {
        Operation operation = operation("com.acme.payments.grpc.PaymentService", "Authorize",
                grpcSpecification("acme.payments.v1.PaymentService.Authorize"));

        ProtobufOperation proto = assertInstanceOf(ProtobufOperation.class,
                backfill.backfillTyped(operation, operation.getSpecification(), OperationProtocol.GRPC));
        assertEquals("acme.payments.v1", proto.packageName());
        assertEquals("PaymentService", proto.service());
        assertEquals("Authorize", proto.rpcMethod());
        assertEquals("com.acme.payments.grpc", proto.javaPackage());
    }

    @Test
    void specificationTypeMapsProtocolOntoSchemaEnum() {
        assertEquals("openapi", backfill.backfillSpecificationType(OperationProtocol.HTTP));
        assertEquals("asyncapi", backfill.backfillSpecificationType(OperationProtocol.KAFKA));
        assertEquals("asyncapi", backfill.backfillSpecificationType(OperationProtocol.AMQP));
        assertEquals("graphql", backfill.backfillSpecificationType(OperationProtocol.GRAPHQL));
        assertEquals("protobuf", backfill.backfillSpecificationType(OperationProtocol.GRPC));
        assertEquals("wsdl", backfill.backfillSpecificationType(OperationProtocol.SOAP));
        assertNull(backfill.backfillSpecificationType(OperationProtocol.METAMODEL));
        assertNull(backfill.backfillSpecificationType(null));
    }

    @Test
    void backfillLeavesMethodAndPathUnchangedForEveryFixture() {
        assertDerivationUnchanged("/orders", "POST", json("{\"summary\": \"Create an order\"}"), OperationProtocol.HTTP);
        assertDerivationUnchanged("shipping.dispatched", "subscribe",
                json("{\"topic\": \"shipping.dispatched\"}"), OperationProtocol.KAFKA);
        assertDerivationUnchanged("invoice.issued", "receive",
                json("{\"maasClassifierName\": \"billing\"}"), OperationProtocol.AMQP);
        String sdl = "createCustomer(input: CustomerInput!): Customer!";
        assertDerivationUnchanged(sdl, "mutation", json("{\"operation\": \"" + sdl + "\"}"), OperationProtocol.GRAPHQL);
        assertDerivationUnchanged("payments.PaymentService", "Pay",
                grpcSpecification("payments.PaymentService.Pay"), OperationProtocol.GRPC);
        assertDerivationUnchanged("com.acme.payments.grpc.PaymentService", "Authorize",
                grpcSpecification("acme.payments.v1.PaymentService.Authorize"), OperationProtocol.GRPC);
    }

    @Test
    void wsdlProtocolAndBindingFilledByReparsingMainSource() throws IOException {
        String wsdl = readResource("conformance/wsdl-hello-service/source.input.wsdl");
        Operation operation = operation("", "POST", null);
        operation.setName("sayHello");

        WsdlOperation typed = assertInstanceOf(WsdlOperation.class,
                backfill.backfillTyped(operation, null, OperationProtocol.SOAP, wsdl));
        assertEquals("SOAP", typed.protocol());
        assertEquals("HelloBinding", typed.binding());
    }

    @Test
    void wsdlWithoutSourceBackfillsSystemProtocolLeavingBindingNull() {
        Operation operation = operation("", "POST", null);
        operation.setName("sayHello");

        WsdlOperation typed = assertInstanceOf(WsdlOperation.class,
                backfill.backfillTyped(operation, null, OperationProtocol.SOAP, null));
        assertEquals("SOAP", typed.protocol());
        assertNull(typed.binding());
        assertEquals("POST", typed.deriveMethod());
        assertEquals("", typed.derivePath());
    }

    @Test
    void specificationVersionReadFromRootDocumentPerProtocol() throws IOException {
        assertEquals("3.0.3", backfill.backfillSpecificationVersion(OperationProtocol.HTTP, "openapi: 3.0.3"));
        assertEquals("2.0", backfill.backfillSpecificationVersion(OperationProtocol.HTTP, "swagger: \"2.0\""));
        assertEquals("2.6.0", backfill.backfillSpecificationVersion(OperationProtocol.KAFKA, "asyncapi: 2.6.0"));
        assertEquals("3.0.0", backfill.backfillSpecificationVersion(OperationProtocol.AMQP, "asyncapi: 3.0.0"));
        assertNull(backfill.backfillSpecificationVersion(OperationProtocol.GRAPHQL, "type Query { field: String }"));
        assertNull(backfill.backfillSpecificationVersion(OperationProtocol.GRPC, "syntax = \"proto3\";"));

        String wsdl = readResource("conformance/wsdl-hello-service/source.input.wsdl");
        assertEquals("1.1", backfill.backfillSpecificationVersion(OperationProtocol.SOAP, wsdl));
    }

    @Test
    void specificationVersionDegradesToNullWithoutSource() {
        assertNull(backfill.backfillSpecificationVersion(OperationProtocol.HTTP, null));
        assertNull(backfill.backfillSpecificationVersion(null, "openapi: 3.0.3"));
    }

    @Test
    void modelWithoutSourceIsReportedNotThrown() {
        SystemModel sourceless = model("payments-grpc");
        sourceless.addProvidedOperation(operation("payments.PaymentService", "Pay",
                grpcSpecification("payments.PaymentService.Pay")));
        SystemModel withSource = model("orders-http");

        List<String> incompleteModelIds = backfill.backfillReparseOnlyFields(List.of(
                new TypedOperationBackfill.ModelReparse(sourceless, OperationProtocol.GRPC, null),
                new TypedOperationBackfill.ModelReparse(withSource, OperationProtocol.HTTP, "openapi: 3.0.3")));

        assertEquals(List.of("payments-grpc"), incompleteModelIds);
        assertEquals("3.0.3", withSource.getSpecificationVersion());
        assertNull(sourceless.getSpecificationVersion());
    }

    @Test
    void wsdlReparseFillsOperationTypedAndVersionForModel() throws IOException {
        String wsdl = readResource("conformance/wsdl-hello-service/source.input.wsdl");
        SystemModel model = model("hello-soap");
        Operation operation = operation("", "POST", null);
        operation.setName("sayHello");
        model.addProvidedOperation(operation);

        List<String> incompleteModelIds = backfill.backfillReparseOnlyFields(List.of(
                new TypedOperationBackfill.ModelReparse(model, OperationProtocol.SOAP, wsdl)));

        assertTrue(incompleteModelIds.isEmpty());
        assertEquals("1.1", model.getSpecificationVersion());
        WsdlOperation typed = assertInstanceOf(WsdlOperation.class, operation.getTyped());
        assertEquals("SOAP", typed.protocol());
        assertEquals("HelloBinding", typed.binding());
        assertEquals("POST", operation.getMethod());
        assertEquals("", operation.getPath());
    }

    private void assertDerivationUnchanged(String path, String method, JsonNode specification, OperationProtocol protocol) {
        Operation operation = operation(path, method, specification);
        TypedOperation typed = backfill.backfillTyped(operation, specification, protocol);
        assertEquals(method, typed.deriveMethod(), "method must survive backfill for " + protocol);
        assertEquals(path, typed.derivePath(), "path must survive backfill for " + protocol);
    }

    private static Operation operation(String path, String method, JsonNode specification) {
        Operation operation = new Operation();
        operation.setPath(path);
        operation.setMethod(method);
        operation.setSpecification(specification);
        return operation;
    }

    private static SystemModel model(String id) {
        SystemModel model = SystemModel.builder().build();
        model.setId(id);
        return model;
    }

    // Minimal gRPC operation specification: the $id nested under the request schema, as ProtobufSpecificationParser writes it.
    private static JsonNode grpcSpecification(String qualifiedName) {
        return json("{\"requestBody\": {\"content\": {\"application/json\": {\"schema\": {"
                + "\"$id\": \"http://system.catalog/schemas/requests/" + qualifiedName + "\"}}}}}");
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
