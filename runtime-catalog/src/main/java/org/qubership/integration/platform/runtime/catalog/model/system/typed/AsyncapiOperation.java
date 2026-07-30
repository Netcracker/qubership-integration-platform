package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("asyncapi")
public record AsyncapiOperation(
        String summary,
        String channel,
        String method
) implements TypedOperation {

    @Override
    public String deriveMethod() {
        return method;
    }

    @Override
    public String derivePath() {
        return channel;
    }
}
