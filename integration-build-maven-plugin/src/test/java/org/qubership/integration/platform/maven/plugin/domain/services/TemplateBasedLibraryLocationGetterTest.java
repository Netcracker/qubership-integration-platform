package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService.BUILD_CRS_TASK_PARAMETERS;

class TemplateBasedLibraryLocationGetterTest {
    private static final String DEFAULT_TEMPLATE =
        "http://{appPrefix}-runtime-catalog-v1:8080/v1/models/{specificationId}/dto/jar";

    private final TemplateBasedLibraryLocationGetter getter = new TemplateBasedLibraryLocationGetter("qip");

    /** The default must keep pointing where the catalog serves the library, as it did before the template. */
    @Test
    void resolvesTheDefaultTemplateToTheCatalogUrl() {
        assertEquals(
            "http://qip-runtime-catalog-v1:8080/v1/models/spec-1/dto/jar",
            getter.apply(context(DEFAULT_TEMPLATE, "spec-1")));
    }

    @Test
    void fillsEveryPlaceholderOfACustomTemplate() {
        String template = "https://repo.example.com/{appPrefix}/{specificationId}/{specificationId}.jar";

        assertEquals(
            "https://repo.example.com/qip/spec-1/spec-1.jar",
            getter.apply(context(template, "spec-1")));
    }

    @Test
    void leavesAnUnknownPlaceholderInPlace() {
        assertEquals(
            "https://repo.example.com/{specId}.jar",
            getter.apply(context("https://repo.example.com/{specId}.jar", "spec-1")));
    }

    @Test
    void failsWhenTheBuildParametersAreMissing() {
        ResourceBuildContext<String> context = ResourceBuildContext.create(BuildInfo.builder().build()).updateTo("spec-1");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> getter.apply(context));

        assertEquals("Failed to get build parameters", exception.getMessage());
    }

    private static ResourceBuildContext<String> context(String template, String specificationId) {
        ResourceBuildContext<String> context = ResourceBuildContext.create(BuildInfo.builder().build())
            .updateTo(specificationId);
        context.getBuildCache().put(
            BUILD_CRS_TASK_PARAMETERS,
            BuildCRsTaskParameters.builder().libraryUrlTemplate(template).build());
        return context;
    }
}
