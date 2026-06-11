package org.qubership.integration.platform.engine.camel.listeners;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.k.Runtime;
import org.apache.camel.k.SourceDefinition;
import org.apache.camel.k.support.SourcesSupport;
import org.apache.camel.support.ResourceHelper;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.chains.ExtendedSourceDefinition;
import org.qubership.integration.platform.engine.model.chains.IntegrationChainsConfiguration;
import org.qubership.integration.platform.engine.model.chains.LibraryDefinition;
import org.qubership.integration.platform.engine.service.ExternalLibraryService;
import org.qubership.integration.platform.engine.state.SourceLoadStateTracker;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RoutesConfigurerTest {

    private static final String CONFIGURATION_LOCATION = "classpath:chains.yaml";
    private static final String LIBRARY_LOCATION = "classpath:libraries/test-library.jar";
    private static final String SPECIFICATION_ID = "specification-id";

    @Mock
    private Runtime runtime;

    @Mock
    private CamelContext camelContext;

    @Mock
    private Config config;

    @Mock
    private CDI<Object> cdiContainer;

    @Mock
    private Instance<YAMLMapper> yamlMapperInstance;

    @Mock
    private Instance<ExternalLibraryService> externalLibraryServiceInstance;

    @Mock
    private Instance<SourceLoadStateTracker> sourceLoadStateTrackerInstance;

    @Mock
    private YAMLMapper yamlMapper;

    @Mock
    private ExternalLibraryService externalLibraryService;

    @Mock
    private SourceLoadStateTracker sourceLoadStateTracker;

    @Mock
    private IntegrationChainsConfiguration integrationChainsConfiguration;

    @Mock
    private LibraryDefinition libraryDefinition;

    @Mock
    private ExtendedSourceDefinition extendedSourceDefinition;

    private RoutesConfigurer routesConfigurer;

    @BeforeEach
    void setUp() {
        routesConfigurer = new RoutesConfigurer();
    }

    @Test
    void shouldDoNothingWhenConfigurationLocationIsMissing() {
        try (MockedStatic<ConfigProvider> configProvider = mockStatic(ConfigProvider.class)) {
            stubConfigurationLocation(configProvider, Optional.empty());

            routesConfigurer.accept(runtime);

            verifyNoInteractions(runtime, yamlMapper, externalLibraryService, sourceLoadStateTracker);
        }
    }

    @Test
    void shouldDoNothingWhenConfigurationLocationIsBlank() {
        try (MockedStatic<ConfigProvider> configProvider = mockStatic(ConfigProvider.class)) {
            stubConfigurationLocation(configProvider, Optional.of(" "));

            routesConfigurer.accept(runtime);

            verifyNoInteractions(runtime, yamlMapper, externalLibraryService, sourceLoadStateTracker);
        }
    }

    @Test
    void shouldLoadLibrariesAndSourcesWhenConfigurationLocationIsPresent() throws Exception {
        byte[] libraryBytes = "library-data".getBytes(StandardCharsets.UTF_8);
        InputStream configurationInputStream = inputStream("configuration");
        InputStream libraryInputStream = new ByteArrayInputStream(libraryBytes);

        when(runtime.getCamelContext()).thenReturn(camelContext);
        when(yamlMapper.readValue(configurationInputStream, IntegrationChainsConfiguration.class))
            .thenReturn(integrationChainsConfiguration);
        when(integrationChainsConfiguration.getLibraries()).thenReturn(List.of(libraryDefinition));
        when(integrationChainsConfiguration.getSources()).thenReturn(List.of(extendedSourceDefinition));
        when(libraryDefinition.getLocation()).thenReturn(LIBRARY_LOCATION);
        when(libraryDefinition.getSpecificationId()).thenReturn(SPECIFICATION_ID);

        try (MockedStatic<ConfigProvider> configProvider = mockStatic(ConfigProvider.class);
             MockedStatic<CDI> cdi = mockStatic(CDI.class);
             MockedStatic<ResourceHelper> resourceHelper = mockStatic(ResourceHelper.class);
             MockedStatic<SourcesSupport> sourcesSupport = mockStatic(SourcesSupport.class)) {
            stubConfigurationLocation(configProvider, Optional.of(CONFIGURATION_LOCATION));
            stubCurrentCdi(cdi);
            stubYamlMapperSelection();
            stubExternalLibraryServiceSelection();
            stubSourceLoadStateTrackerSelection();

            resourceHelper.when(() -> ResourceHelper.resolveMandatoryResourceAsInputStream(
                    camelContext,
                    CONFIGURATION_LOCATION
                ))
                .thenReturn(configurationInputStream);
            resourceHelper.when(() -> ResourceHelper.resolveMandatoryResourceAsInputStream(
                    camelContext,
                    LIBRARY_LOCATION
                ))
                .thenReturn(libraryInputStream);

            routesConfigurer.accept(runtime);

            verify(externalLibraryService).addLibrary(SPECIFICATION_ID, libraryBytes);
            verify(sourceLoadStateTracker).addSourceDefinitions(List.of(extendedSourceDefinition));
            sourcesSupport.verify(() -> SourcesSupport.loadSources(
                eq(runtime),
                eq(new SourceDefinition[]{extendedSourceDefinition})
            ));
        }
    }

    @Test
    void shouldLoadConfigurationAndLibrariesButSkipSourcesWhenSourcesListIsEmpty() throws Exception {
        InputStream configurationInputStream = inputStream("configuration");

        when(runtime.getCamelContext()).thenReturn(camelContext);
        when(yamlMapper.readValue(configurationInputStream, IntegrationChainsConfiguration.class))
            .thenReturn(integrationChainsConfiguration);
        when(integrationChainsConfiguration.getLibraries()).thenReturn(List.of());
        when(integrationChainsConfiguration.getSources()).thenReturn(List.of());

        try (MockedStatic<ConfigProvider> configProvider = mockStatic(ConfigProvider.class);
             MockedStatic<CDI> cdi = mockStatic(CDI.class);
             MockedStatic<ResourceHelper> resourceHelper = mockStatic(ResourceHelper.class);
             MockedStatic<SourcesSupport> sourcesSupport = mockStatic(SourcesSupport.class)) {
            stubConfigurationLocation(configProvider, Optional.of(CONFIGURATION_LOCATION));
            stubCurrentCdi(cdi);
            stubYamlMapperSelection();
            stubExternalLibraryServiceSelection();

            resourceHelper.when(() -> ResourceHelper.resolveMandatoryResourceAsInputStream(
                    camelContext,
                    CONFIGURATION_LOCATION
                ))
                .thenReturn(configurationInputStream);

            routesConfigurer.accept(runtime);

            verifyNoInteractions(externalLibraryService);
            verify(sourceLoadStateTracker, never()).addSourceDefinitions(any());
            sourcesSupport.verifyNoInteractions();
        }
    }

    @Test
    void shouldWrapExceptionWhenConfigurationResourceCannotBeResolved() {
        IOException exception = new IOException("Configuration resource is missing");

        when(runtime.getCamelContext()).thenReturn(camelContext);

        try (MockedStatic<ConfigProvider> configProvider = mockStatic(ConfigProvider.class);
             MockedStatic<ResourceHelper> resourceHelper = mockStatic(ResourceHelper.class)) {
            stubConfigurationLocation(configProvider, Optional.of(CONFIGURATION_LOCATION));

            resourceHelper.when(() -> ResourceHelper.resolveMandatoryResourceAsInputStream(
                    camelContext,
                    CONFIGURATION_LOCATION
                ))
                .thenThrow(exception);

            RuntimeException result = assertThrows(RuntimeException.class, () -> routesConfigurer.accept(runtime));

            assertSame(exception, result.getCause());
        }
    }

    @Test
    void shouldWrapExceptionWhenYamlConfigurationCannotBeParsed() throws Exception {
        InputStream configurationInputStream = inputStream("invalid configuration");
        IOException exception = new IOException("Invalid YAML configuration");

        when(runtime.getCamelContext()).thenReturn(camelContext);
        when(yamlMapper.readValue(configurationInputStream, IntegrationChainsConfiguration.class)).thenThrow(exception);

        try (MockedStatic<ConfigProvider> configProvider = mockStatic(ConfigProvider.class);
             MockedStatic<CDI> cdi = mockStatic(CDI.class);
             MockedStatic<ResourceHelper> resourceHelper = mockStatic(ResourceHelper.class)) {
            stubConfigurationLocation(configProvider, Optional.of(CONFIGURATION_LOCATION));
            stubCurrentCdi(cdi);
            stubYamlMapperSelection();

            resourceHelper.when(() -> ResourceHelper.resolveMandatoryResourceAsInputStream(
                    camelContext,
                    CONFIGURATION_LOCATION
                ))
                .thenReturn(configurationInputStream);

            RuntimeException result = assertThrows(RuntimeException.class, () -> routesConfigurer.accept(runtime));

            assertSame(exception, result.getCause());
        }
    }

    private void stubConfigurationLocation(
        MockedStatic<ConfigProvider> configProvider,
        Optional<String> configurationLocation
    ) {
        configProvider.when(ConfigProvider::getConfig).thenReturn(config);
        when(config.getOptionalValue("qip.chains.configuration.location", String.class))
            .thenReturn(configurationLocation);
    }

    private void stubCurrentCdi(MockedStatic<CDI> cdi) {
        cdi.when(CDI::current).thenReturn(cdiContainer);
    }

    private void stubYamlMapperSelection() {
        when(cdiContainer.select(eq(YAMLMapper.class), any(Annotation.class))).thenReturn(yamlMapperInstance);
        when(yamlMapperInstance.get()).thenReturn(yamlMapper);
    }

    private void stubExternalLibraryServiceSelection() {
        when(cdiContainer.select(ExternalLibraryService.class)).thenReturn(externalLibraryServiceInstance);
        when(externalLibraryServiceInstance.get()).thenReturn(externalLibraryService);
    }

    private void stubSourceLoadStateTrackerSelection() {
        when(cdiContainer.select(SourceLoadStateTracker.class)).thenReturn(sourceLoadStateTrackerInstance);
        when(sourceLoadStateTrackerInstance.get()).thenReturn(sourceLoadStateTracker);
    }

    private static InputStream inputStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
