package org.qubership.integration.platform.camelk.locations;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

@Component("libraryLocationFromCatalogGetter")
public class LibraryLocationFromCatalogGetter implements Function<ResourceBuildContext<String>, String> {
    @Value("${spring.application.cloud_service_name}")
    private String cloudServiceName;

    @Override
    public String apply(ResourceBuildContext<String> context) {
        String specificationId = context.getData();
        String encodedSpecificationId = UriUtils.encodePathSegment(specificationId, StandardCharsets.UTF_8);
        // FIXME specify schema in application properties
        return String.format("http://%s:8080/v1/models/%s/dto/jar", cloudServiceName, encodedSpecificationId);
    }
}
