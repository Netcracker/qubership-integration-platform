package org.qubership.integration.platform.engine.camel.listeners;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.inject.spi.CDI;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.k.Runtime;
import org.apache.camel.k.SourceDefinition;
import org.apache.camel.k.listener.AbstractPhaseListener;
import org.apache.camel.k.support.SourcesSupport;
import org.apache.camel.support.ResourceHelper;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.ConfigProvider;
import org.qubership.integration.platform.engine.model.chains.IntegrationChainsConfiguration;
import org.qubership.integration.platform.engine.model.chains.LibraryDefinition;
import org.qubership.integration.platform.engine.service.ExternalLibraryService;
import org.qubership.integration.platform.engine.state.SourceLoadStateTracker;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@Slf4j
public class RoutesConfigurer extends AbstractPhaseListener {
    public RoutesConfigurer() {
        super(Runtime.Phase.ConfigureRoutes);
    }

    @Override
    protected void accept(Runtime runtime) {
        getConfigurationLocation().ifPresentOrElse(
                location -> loadIntegrationChains(location, runtime),
                () -> log.info("Integration chains configuration not specified.")
        );
    }

    private void loadIntegrationChains(String location, Runtime runtime) {
        try {
            log.info("Loading integration chains configuration from {}", location);
            IntegrationChainsConfiguration integrationChainsConfiguration = loadConfiguration(location, runtime);
            loadLibraries(integrationChainsConfiguration.getLibraries(), runtime);
            loadSources(
                    integrationChainsConfiguration.getSources()
                            .stream()
                            .map(SourceDefinition.class::cast)
                            .toList(),
                    runtime
            );
        } catch (Exception exception) {
            log.error("Failed to load integration chains configuration.", exception);
            throw new RuntimeException(exception);
        }
    }

    private void loadLibraries(List<LibraryDefinition> libraries, Runtime runtime) throws IOException {
        ExternalLibraryService externalLibraryService = getExternalLibraryService();
        for (LibraryDefinition library : libraries) {
            log.info("Loading library from {}", library.getLocation());
            InputStream inputStream = ResourceHelper.resolveMandatoryResourceAsInputStream(
                    runtime.getCamelContext(), library.getLocation());
            byte[] data = inputStream.readAllBytes();
            externalLibraryService.addLibrary(library.getSpecificationId(), data);
        }
    }

    private void loadSources(List<SourceDefinition> sources, Runtime runtime) {
        if (sources.isEmpty()) {
            log.warn("Integration chain sources list is empty");
            return;
        }
        getSourceLoadStateTracker().addSourceDefinitions(sources);
        SourcesSupport.loadSources(runtime, sources.toArray(new SourceDefinition[0]));
    }

    private IntegrationChainsConfiguration loadConfiguration(String location, Runtime runtime) throws IOException {
        InputStream inputStream = ResourceHelper.resolveMandatoryResourceAsInputStream(
                runtime.getCamelContext(), location);
        return getYamlMapper().readValue(inputStream, IntegrationChainsConfiguration.class);
    }

    private Optional<String> getConfigurationLocation() {
        return ConfigProvider.getConfig()
                .getOptionalValue("qip.chains.configuration.location", String.class)
                .filter(StringUtils::isNotBlank);
    }

    private YAMLMapper getYamlMapper() {
        return CDI.current()
                .select(YAMLMapper.class, Identifier.Literal.of("chainsConfigurationMapper"))
                .get();
    }

    private SourceLoadStateTracker getSourceLoadStateTracker() {
        return CDI.current().select(SourceLoadStateTracker.class).get();
    }

    private ExternalLibraryService getExternalLibraryService() {
        return CDI.current().select(ExternalLibraryService.class).get();
    }
}
