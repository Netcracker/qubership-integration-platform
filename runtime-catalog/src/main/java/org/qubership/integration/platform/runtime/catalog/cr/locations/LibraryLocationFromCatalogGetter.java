package org.qubership.integration.platform.runtime.catalog.cr.locations;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component("libraryLocationFromCatalogGetter")
public class LibraryLocationFromCatalogGetter implements Function<ResourceBuildContext<String>, String> {
    @Value("${spring.application.cloud_service_name}")
    private String cloudServiceName;

    @Override
    public String apply(ResourceBuildContext<String> context) {
        String specificationId = context.getData();
        // FIXME specify schema in application properties
        return String.format("http://%s:8080/v1/models/%s/dto/jar", cloudServiceName, specificationId);
    }
}
