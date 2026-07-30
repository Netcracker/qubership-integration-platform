package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.annotation.JsonTypeName;

// sdl is a QIP-only field: the shared schema carries only operationType, but path holds the printed field AST.
@JsonTypeName("graphql")
public record GraphqlOperation(
        String operationType,
        String sdl
) implements TypedOperation {

    @Override
    public String deriveMethod() {
        return operationType;
    }

    @Override
    public String derivePath() {
        return sdl;
    }
}
