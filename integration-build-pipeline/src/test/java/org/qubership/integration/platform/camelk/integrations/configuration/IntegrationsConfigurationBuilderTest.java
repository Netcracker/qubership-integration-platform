package org.qubership.integration.platform.camelk.integrations.configuration;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.builders.SourceDefinitionBuilder;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.IntegrationsConfigurationOptions;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.library.constants.CamelOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationsConfigurationBuilderTest {
    private final SourceDefinitionBuilder sourceDefinitionBuilder = mock(SourceDefinitionBuilder.class);
    private final IntegrationsConfigurationBuilder builder = new IntegrationsConfigurationBuilder(
        sourceDefinitionBuilder,
        context -> libraryContext -> "https://repo.example.com/" + libraryContext.getData() + ".jar");

    @Test
    void listsALibraryPerSpecificationByDefault() {
        IntegrationsConfiguration configuration = builder.build(context(IntegrationsConfigurationOptions.builder().build()));

        assertEquals(
            List.of(new LibraryDefinition("spec-1", "https://repo.example.com/spec-1.jar")),
            configuration.getLibraries());
    }

    /** Off, the engine is expected to get the DTO classes elsewhere, so no library and no location is listed. */
    @Test
    void listsNoLibraryWhenLibraryDefinitionsAreDisabled() {
        IntegrationsConfigurationOptions options = IntegrationsConfigurationOptions.builder()
            .libraryDefinitionsEnabled(false)
            .build();

        IntegrationsConfiguration configuration = builder.build(context(options));

        assertTrue(configuration.getLibraries().isEmpty());
        assertEquals(1, configuration.getSources().size(), "the chain sources are listed either way");
    }

    /** runtime-catalog and the plugin both create the options without the builder, so the default must hold there too. */
    @Test
    void enablesLibraryDefinitionsWhenCreatedWithoutTheBuilder() {
        assertTrue(new IntegrationsConfigurationOptions().isLibraryDefinitionsEnabled());
    }

    private ResourceBuildContext<List<Snapshot>> context(IntegrationsConfigurationOptions integrations) {
        Element element = mock(Element.class);
        when(element.getProperties()).thenReturn(Map.of(CamelOptions.SPECIFICATION_ID, "spec-1"));
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getElements()).thenReturn(List.of(element));
        when(sourceDefinitionBuilder.build(any())).thenReturn(new SourceDefinition());
        BuildInfo buildInfo = BuildInfo.builder()
            .options(ResourceBuildOptions.builder().integrations(integrations).build())
            .build();
        return ResourceBuildContext.create(buildInfo).updateTo(List.of(snapshot));
    }
}
