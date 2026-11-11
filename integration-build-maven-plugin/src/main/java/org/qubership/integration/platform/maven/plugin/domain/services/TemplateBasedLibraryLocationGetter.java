package org.qubership.integration.platform.maven.plugin.domain.services;

import org.apache.commons.text.StringSubstitutor;
import org.qubership.integration.platform.camelk.locations.LibraryLocationGetter;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService.BUILD_CRS_TASK_PARAMETERS;

@Primary
@Component
public class TemplateBasedLibraryLocationGetter implements LibraryLocationGetter {
    @Override
    public String apply(ResourceBuildContext<String> context) {
        BuildCRsTaskParameters parameters = Optional.ofNullable(context.getBuildCache().get(BUILD_CRS_TASK_PARAMETERS))
            .map(BuildCRsTaskParameters.class::cast)
            .orElseThrow(() -> new RuntimeException("Failed to get build parameters"));
        String specificationId = context.getData();
        String encodedSpecificationId = UriUtils.encodePathSegment(specificationId, StandardCharsets.UTF_8);
        String template = parameters.getLibraryUrlTemplate();
        Map<String, String> values = Map.of(
            "specificationId", encodedSpecificationId
        );
        StringSubstitutor substitutor = new StringSubstitutor(values, "{", "}");
        return substitutor.replace(template);
    }
}
