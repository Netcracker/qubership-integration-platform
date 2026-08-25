package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

// javaPackage is a QIP-only field: path is built from the java_package option, falling back to the proto package.
@JsonTypeName("protobuf")
public record ProtobufOperation(
        @JsonProperty("package") String packageName,
        String service,
        String rpcMethod,
        String javaPackage
) implements TypedOperation {

    @Override
    public String deriveMethod() {
        return rpcMethod;
    }

    @Override
    public String derivePath() {
        String pkg = javaPackage != null ? javaPackage : packageName;
        // A package-less proto3 file is legal; without this it would join to the literal "null.<service>".
        return pkg != null ? pkg + "." + service : service;
    }
}
