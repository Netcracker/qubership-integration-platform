package org.qubership.integration.platform.runtime.catalog.db.migration.postgresql.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The migration runs against a live database, so this suite drives its pure, database-free parts: the
 * class-name-to-version contract Flyway keys on, the idempotent WHERE clauses, the column-to-typed transform,
 * and one Mockito pass over {@code migrate} that verifies the JDBC read-transform-write wiring.
 */
@SuppressWarnings("checkstyle:TypeName")
class V112_001BackfillTypedOperationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void versionAndDescriptionParseFromClassName() {
        V112_001__BackfillTypedOperations migration = new V112_001__BackfillTypedOperations();

        assertEquals("112.001", migration.getVersion().getVersion());
        assertEquals("BackfillTypedOperations", migration.getDescription());
        assertInstanceOf(ConfigsJavaMigration.class, migration);
    }

    @Test
    void isComponentScannedAndAConfigsJavaMigrationSoFlywayInitializerRegistersIt() {
        // FlywayInitializer feeds the injected List<ConfigsJavaMigration> beans to setJavaMigrations, and Spring
        // Boot's own Flyway autoconfiguration is off, so a missing @Component silently drops this backfill from
        // every migration run. Default filters make the scan return only stereotype-annotated classes.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        boolean componentScanned = scanner.findCandidateComponents(
                        "org.qubership.integration.platform.runtime.catalog.db.migration.postgresql.configs").stream()
                .map(BeanDefinition::getBeanClassName)
                .anyMatch(V112_001__BackfillTypedOperations.class.getName()::equals);

        assertTrue(componentScanned,
                "V112_001__BackfillTypedOperations must be a @Component so FlywayInitializer registers it");
        assertInstanceOf(ConfigsJavaMigration.class, new V112_001__BackfillTypedOperations());
    }

    @Test
    void queriesSelectOnlyRowsMissingTheValueSoAnotherRunIsANoOp() {
        assertTrue(V112_001__BackfillTypedOperations.SELECT_OPERATIONS.toLowerCase().contains("where o.typed is null"));
        assertTrue(V112_001__BackfillTypedOperations.SELECT_MODELS.toLowerCase().contains("specification_type is null"));
        assertTrue(V112_001__BackfillTypedOperations.SELECT_OPERATIONS.toLowerCase().contains("integration_system"));
        // Both queries join through the renamed table and column; V112_000 leaves no specification_group behind.
        assertTrue(V112_001__BackfillTypedOperations.SELECT_OPERATIONS.toLowerCase().contains("api_group"));
        assertTrue(V112_001__BackfillTypedOperations.SELECT_OPERATIONS.toLowerCase().contains("api_group_id"));
        assertTrue(V112_001__BackfillTypedOperations.SELECT_MODELS.toLowerCase().contains("api_group"));
        assertTrue(V112_001__BackfillTypedOperations.SELECT_MODELS.toLowerCase().contains("api_group_id"));
        assertFalse(V112_001__BackfillTypedOperations.SELECT_OPERATIONS.toLowerCase().contains("specification_group"));
        assertFalse(V112_001__BackfillTypedOperations.SELECT_MODELS.toLowerCase().contains("specification_group"));
    }

    @Test
    void protocolMapsFromTheStoredEnumNameAndDegradesToNull() {
        assertEquals(OperationProtocol.HTTP, V112_001__BackfillTypedOperations.protocolOf("HTTP"));
        assertEquals(OperationProtocol.GRPC, V112_001__BackfillTypedOperations.protocolOf("GRPC"));
        assertNull(V112_001__BackfillTypedOperations.protocolOf(null));
        assertNull(V112_001__BackfillTypedOperations.protocolOf("NOT_A_PROTOCOL"));
    }

    @Test
    void openapiRowSerializesToTypedThatRoundTripsAndKeepsMethodAndPath() throws Exception {
        String typedJson = V112_001__BackfillTypedOperations.typedJson(
                "/orders", "POST", json("{\"summary\": \"Create an order\", \"deprecated\": true}"), OperationProtocol.HTTP);

        assertTrue(typedJson.contains("\"type\":\"openapi\""));
        OpenapiOperation typed = assertInstanceOf(OpenapiOperation.class, roundTrip(typedJson));
        assertEquals("Create an order", typed.summary());
        assertEquals("post", typed.method());
        assertEquals(Boolean.TRUE, typed.isDeprecated());
        assertEquals("POST", typed.deriveMethod());
        assertEquals("/orders", typed.derivePath());
    }

    @Test
    void asyncapiRowSerializesToTypedThatRoundTripsAndKeepsMethodAndPath() throws Exception {
        String typedJson = V112_001__BackfillTypedOperations.typedJson(
                "shipping.dispatched", "subscribe", json("{}"), OperationProtocol.KAFKA);

        AsyncapiOperation typed = assertInstanceOf(AsyncapiOperation.class, roundTrip(typedJson));
        assertEquals("shipping.dispatched", typed.channel());
        assertEquals("subscribe", typed.method());
        assertEquals("subscribe", typed.deriveMethod());
        assertEquals("shipping.dispatched", typed.derivePath());
    }

    @Test
    void graphqlRowSerializesToTypedThatRoundTripsAndKeepsMethodAndPath() throws Exception {
        String sdl = "createCustomer(input: CustomerInput!): Customer!";
        String typedJson = V112_001__BackfillTypedOperations.typedJson(
                sdl, "mutation", json("{\"operation\": \"" + sdl + "\"}"), OperationProtocol.GRAPHQL);

        GraphqlOperation typed = assertInstanceOf(GraphqlOperation.class, roundTrip(typedJson));
        assertEquals("mutation", typed.operationType());
        assertEquals(sdl, typed.sdl());
        assertEquals("mutation", typed.deriveMethod());
        assertEquals(sdl, typed.derivePath());
    }

    @Test
    void protobufRowSerializesToTypedThatRoundTripsAndKeepsMethodAndPath() throws Exception {
        String typedJson = V112_001__BackfillTypedOperations.typedJson(
                "com.acme.payments.grpc.PaymentService", "Authorize",
                json("{\"requestBody\": {\"content\": {\"application/json\": {\"schema\": {"
                        + "\"$id\": \"http://c/schemas/requests/acme.payments.v1.PaymentService.Authorize\"}}}}}"),
                OperationProtocol.GRPC);

        ProtobufOperation typed = assertInstanceOf(ProtobufOperation.class, roundTrip(typedJson));
        assertEquals("acme.payments.v1", typed.packageName());
        assertEquals("PaymentService", typed.service());
        assertEquals("Authorize", typed.rpcMethod());
        assertEquals("com.acme.payments.grpc", typed.javaPackage());
        assertEquals("Authorize", typed.deriveMethod());
        assertEquals("com.acme.payments.grpc.PaymentService", typed.derivePath());
    }

    @Test
    void wsdlBackfillsProtocolWhileBindingAndApiLessTypesStayNull() throws Exception {
        // WSDL method/path are constants; the migration fills protocol from the system protocol (SOAP) and leaves
        // binding for the next import. METAMODEL never reaches the API level, so it alone keeps a null typed.
        WsdlOperation wsdl = assertInstanceOf(WsdlOperation.class,
                roundTrip(V112_001__BackfillTypedOperations.typedJson("", "POST", json("{}"), OperationProtocol.SOAP)));
        assertEquals("POST", wsdl.deriveMethod());
        assertEquals("", wsdl.derivePath());
        assertEquals("SOAP", wsdl.protocol());
        assertNull(wsdl.binding());
        assertNull(V112_001__BackfillTypedOperations.typedJson("x", "y", json("{}"), OperationProtocol.METAMODEL));
        assertNull(V112_001__BackfillTypedOperations.typedJson("x", "y", json("{}"), null));
    }

    @Test
    void migrateReadsRowsAndWritesTypedAndSpecificationType() throws Exception {
        Statement operationSelect = mock(Statement.class);
        ResultSet operationRows = mock(ResultSet.class);
        when(operationRows.next()).thenReturn(true, false);
        when(operationRows.getString("id")).thenReturn("op-1");
        when(operationRows.getString("path")).thenReturn("/orders");
        when(operationRows.getString("method")).thenReturn("POST");
        when(operationRows.getString("specification")).thenReturn("{\"summary\":\"Create an order\"}");
        when(operationRows.getString("protocol")).thenReturn("HTTP");
        when(operationSelect.executeQuery(V112_001__BackfillTypedOperations.SELECT_OPERATIONS)).thenReturn(operationRows);

        Statement modelSelect = mock(Statement.class);
        ResultSet modelRows = mock(ResultSet.class);
        when(modelRows.next()).thenReturn(true, false);
        when(modelRows.getString("id")).thenReturn("model-1");
        when(modelRows.getString("protocol")).thenReturn("HTTP");
        when(modelSelect.executeQuery(V112_001__BackfillTypedOperations.SELECT_MODELS)).thenReturn(modelRows);

        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(operationSelect, modelSelect);
        PreparedStatement operationUpdate = mock(PreparedStatement.class);
        PreparedStatement modelUpdate = mock(PreparedStatement.class);
        when(connection.prepareStatement(V112_001__BackfillTypedOperations.UPDATE_OPERATION_TYPED))
                .thenReturn(operationUpdate);
        when(connection.prepareStatement(V112_001__BackfillTypedOperations.UPDATE_MODEL_SPECIFICATION_TYPE))
                .thenReturn(modelUpdate);

        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);

        new V112_001__BackfillTypedOperations().migrate(context);

        ArgumentCaptor<String> typedJson = ArgumentCaptor.forClass(String.class);
        verify(operationUpdate).setString(eq(1), typedJson.capture());
        verify(operationUpdate).setString(2, "op-1");
        verify(operationUpdate).addBatch();
        verify(operationUpdate).executeBatch();
        assertInstanceOf(OpenapiOperation.class, roundTrip(typedJson.getValue()));

        verify(modelUpdate).setString(1, "openapi");
        verify(modelUpdate).setString(2, "model-1");
        verify(modelUpdate).addBatch();
        verify(modelUpdate).executeBatch();
    }

    @Test
    void migrateWithNoUnfilledRowsWritesNothingSoASecondRunIsANoOp() throws Exception {
        // Both selects filter on IS NULL, so a re-run on an already-backfilled database returns zero rows.
        Statement operationSelect = mock(Statement.class);
        ResultSet operationRows = mock(ResultSet.class);
        when(operationRows.next()).thenReturn(false);
        when(operationSelect.executeQuery(V112_001__BackfillTypedOperations.SELECT_OPERATIONS)).thenReturn(operationRows);

        Statement modelSelect = mock(Statement.class);
        ResultSet modelRows = mock(ResultSet.class);
        when(modelRows.next()).thenReturn(false);
        when(modelSelect.executeQuery(V112_001__BackfillTypedOperations.SELECT_MODELS)).thenReturn(modelRows);

        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(operationSelect, modelSelect);
        PreparedStatement operationUpdate = mock(PreparedStatement.class);
        PreparedStatement modelUpdate = mock(PreparedStatement.class);
        when(connection.prepareStatement(V112_001__BackfillTypedOperations.UPDATE_OPERATION_TYPED))
                .thenReturn(operationUpdate);
        when(connection.prepareStatement(V112_001__BackfillTypedOperations.UPDATE_MODEL_SPECIFICATION_TYPE))
                .thenReturn(modelUpdate);

        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);

        new V112_001__BackfillTypedOperations().migrate(context);

        verify(operationUpdate, never()).setString(anyInt(), any());
        verify(operationUpdate, never()).addBatch();
        verify(operationUpdate, never()).executeBatch();
        verify(modelUpdate, never()).setString(anyInt(), any());
        verify(modelUpdate, never()).addBatch();
        verify(modelUpdate, never()).executeBatch();
    }

    private static TypedOperation roundTrip(String json) throws Exception {
        return MAPPER.readValue(json, TypedOperation.class);
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
