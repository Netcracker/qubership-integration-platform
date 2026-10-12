package org.qubership.integration.platform.camelk.locations;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;

import java.util.function.Function;

public interface LibraryLocationGetterProvider {
    Function<ResourceBuildContext<String>, String> get(ResourceBuildContext<?> context);
}
