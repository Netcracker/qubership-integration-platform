package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationDerivationTest {

    @Test
    void openapiDerivesUppercaseMethodAndPath() {
        Operation operation = new Operation();
        operation.setTyped(new OpenapiOperation("Add a new pet", "/pet", "post", true));

        assertEquals("POST", operation.getMethod());
        assertEquals("/pet", operation.getPath());
    }

    @Test
    void asyncapiDerivesMethodAndChannel() {
        Operation operation = new Operation();
        operation.setTyped(new AsyncapiOperation("Order placed", "orders", "publish"));

        assertEquals("publish", operation.getMethod());
        assertEquals("orders", operation.getPath());
    }

    @Test
    void protobufDerivesRpcMethodAndJavaPackagePath() {
        Operation operation = new Operation();
        operation.setTyped(new ProtobufOperation(
                "acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc"));

        assertEquals("Authorize", operation.getMethod());
        assertEquals("com.acme.payments.grpc.PaymentService", operation.getPath());
    }

    @Test
    void protobufFallsBackToProtoPackageWhenJavaPackageIsNull() {
        Operation operation = new Operation();
        operation.setTyped(new ProtobufOperation(
                "acme.payments.v1", "PaymentService", "Authorize", null));

        assertEquals("Authorize", operation.getMethod());
        assertEquals("acme.payments.v1.PaymentService", operation.getPath());
    }

    @Test
    void graphqlDerivesOperationTypeAndSdl() {
        Operation operation = new Operation();
        operation.setTyped(new GraphqlOperation("query", "customer(id: ID!): Customer"));

        assertEquals("query", operation.getMethod());
        assertEquals("customer(id: ID!): Customer", operation.getPath());
    }

    @Test
    void wsdlDerivesPostMethodAndEmptyPath() {
        Operation operation = new Operation();
        operation.setTyped(new WsdlOperation("http", "PetBinding"));

        assertEquals("POST", operation.getMethod());
        assertEquals("", operation.getPath());
    }

    @Test
    void nullTypedLeavesMethodAndPathUntouched() {
        // Old archives import without a typed payload and must keep the values they carry.
        Operation operation = Operation.builder().method("GET").path("/legacy").build();
        operation.setTyped(null);

        assertEquals("GET", operation.getMethod());
        assertEquals("/legacy", operation.getPath());
    }

    @Test
    void changingTypedRecomputesBothDerivedValuesImmediately() {
        Operation operation = new Operation();
        operation.setTyped(new OpenapiOperation("Add a new pet", "/pet", "post", true));
        assertEquals("POST", operation.getMethod());
        assertEquals("/pet", operation.getPath());

        operation.setTyped(new AsyncapiOperation("Order placed", "orders", "subscribe"));
        assertEquals("subscribe", operation.getMethod());
        assertEquals("orders", operation.getPath());
    }

    @Test
    void operationBuiltThenGivenTypedReportsDerivedValuesWithoutPersistence() {
        // SystemModelBaseService.save returns the caller's detached instance rather than the merge
        // result, so the derived values must be present on the object the parser hands back.
        Operation operation = Operation.builder().build();
        operation.setTyped(new OpenapiOperation("Add a new pet", "/pet", "get", false));

        assertEquals("GET", operation.getMethod());
        assertEquals("/pet", operation.getPath());
    }

    @Test
    void lifecycleCallbackDerivesForTypedSetThroughTheBuilder() {
        // @SuperBuilder writes typed directly and bypasses setTyped, so method and path stay null.
        // The @PrePersist / @PreUpdate callback is the net that fills them before a write.
        Operation operation = Operation.builder()
                .typed(new WsdlOperation("http", "PetBinding"))
                .build();

        operation.deriveMethodAndPath();

        assertEquals("POST", operation.getMethod());
        assertEquals("", operation.getPath());
    }

    @Test
    void lifecycleCallbackIsAnnotatedAndDoesNotOverrideTheAuditPreUpdate() throws NoSuchMethodException {
        // The net must fire on both persist and update, and it must not be named preUpdate: AbstractEntity
        // already declares a preUpdate audit callback that an override would silently cancel.
        Method callback = Operation.class.getDeclaredMethod("deriveMethodAndPath");

        assertTrue(callback.isAnnotationPresent(PrePersist.class), "@PrePersist must be present");
        assertTrue(callback.isAnnotationPresent(PreUpdate.class), "@PreUpdate must be present");
        assertFalse(Arrays.stream(Operation.class.getDeclaredMethods())
                        .anyMatch(method -> "preUpdate".equals(method.getName())),
                "Operation must not declare preUpdate, which would override the audit callback");
    }
}
