package org.qubership.integration.platform.runtime.catalog.model.system.typed;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.Locale;

@JsonTypeName("openapi")
public record OpenapiOperation(
        String summary,
        String path,
        String method,
        Boolean isDeprecated
) implements TypedOperation {

    @Override
    public String deriveMethod() {
        return method == null ? null : method.toUpperCase(Locale.ROOT);
    }

    @Override
    public String derivePath() {
        return path;
    }

    // The interface cannot name this accessor after the component: an is-prefixed method returning Boolean is a
    // bean getter, and the inherited default would serialize into every other record.
    @Override
    public Boolean deprecated() {
        return isDeprecated;
    }
}
