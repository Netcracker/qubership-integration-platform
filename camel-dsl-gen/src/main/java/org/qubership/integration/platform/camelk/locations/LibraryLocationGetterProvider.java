package org.qubership.integration.platform.camelk.locations;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class LibraryLocationGetterProvider {
    private final LibraryLocationFromCatalogGetter libraryLocationFromCatalogGetter;

    @Autowired
    public LibraryLocationGetterProvider(LibraryLocationFromCatalogGetter libraryLocationFromCatalogGetter) {
        this.libraryLocationFromCatalogGetter = libraryLocationFromCatalogGetter;
    }

    public Function<ResourceBuildContext<String>, String> get(ResourceBuildContext<?> context) {
        // TODO add another library location support (f.e. from artifactory)
        return libraryLocationFromCatalogGetter;
    }
}
