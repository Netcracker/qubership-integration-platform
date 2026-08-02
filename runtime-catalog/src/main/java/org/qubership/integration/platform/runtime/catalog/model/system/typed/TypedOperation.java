package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

// Source of truth for an operation's protocol-specific data, stored in the operations.typed jsonb column.
// method and path are derived from it. @JsonTypeName on each record is required: the jsonb layer serializes
// by runtime type, so @JsonSubTypes alone never reaches the write side.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpenapiOperation.class, name = "openapi"),
        @JsonSubTypes.Type(value = AsyncapiOperation.class, name = "asyncapi"),
        @JsonSubTypes.Type(value = WsdlOperation.class, name = "wsdl"),
        @JsonSubTypes.Type(value = GraphqlOperation.class, name = "graphql"),
        @JsonSubTypes.Type(value = ProtobufOperation.class, name = "protobuf")
})
@JsonIgnoreProperties(ignoreUnknown = true)
// Serializable because Operation holds this as a field and AbstractEntity declares Serializable:
// without it, serializing an operation that carries typed data fails at runtime.
public sealed interface TypedOperation extends Serializable
        permits OpenapiOperation, AsyncapiOperation, WsdlOperation, GraphqlOperation, ProtobufOperation {

    String deriveMethod();

    String derivePath();

    // Flat accessors the Operation entity re-exposes for name-based DTO mapping. A record that declares the field
    // as a component implements the accessor for free; the rest keep the null default, so a caller reads any field
    // without knowing the protocol. None of them may be a bean getter, or Jackson serializes it into every record
    // that inherits the default — which is why the deprecation flag is `deprecated()` here and OpenapiOperation
    // overrides it rather than the interface naming it after the `isDeprecated` component. TypedOperationTest pins it.
    default String summary() {
        return null;
    }

    default String channel() {
        return null;
    }

    default Boolean deprecated() {
        return null;
    }

    default String operationType() {
        return null;
    }

    default String binding() {
        return null;
    }

    default String rpcMethod() {
        return null;
    }

    default String packageName() {
        return null;
    }

    default String service() {
        return null;
    }
}
