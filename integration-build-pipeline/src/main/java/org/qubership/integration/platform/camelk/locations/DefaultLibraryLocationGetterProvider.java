package org.qubership.integration.platform.camelk.locations;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class DefaultLibraryLocationGetterProvider implements LibraryLocationGetterProvider {
    private final LibraryLocationGetter libraryLocationGetter;

    @Autowired
    public DefaultLibraryLocationGetterProvider(LibraryLocationGetter libraryLocationGetter) {
        this.libraryLocationGetter = libraryLocationGetter;
    }

    public Function<ResourceBuildContext<String>, String> get(ResourceBuildContext<?> context) {
        return libraryLocationGetter;
    }
}
