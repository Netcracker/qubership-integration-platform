package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("wsdl")
public record WsdlOperation(
        String protocol,
        String binding
) implements TypedOperation {

    @Override
    public String deriveMethod() {
        return "POST";
    }

    @Override
    public String derivePath() {
        return "";
    }
}
