package org.qubership.integration.platform.engine.state;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.k.SourceDefinition;
import org.apache.camel.k.listener.SourcesConfigurer;
import org.apache.camel.k.support.PropertiesSupport;
import org.apache.camel.spi.Resource;
import org.qubership.integration.platform.engine.camel.dsl.notification.SourceProcessingListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.isNull;
import static org.apache.camel.k.listener.SourcesConfigurer.CAMEL_K_PREFIX;
import static org.apache.camel.k.listener.SourcesConfigurer.CAMEL_K_SOURCES_PREFIX;

@Slf4j
@ApplicationScoped
public class SourceLoadStateTracker implements SourceProcessingListener {
    public enum SourceLoadStage {
        UNKNOWN,
        SUCCESS,
        PROCESSING,
        FAILED
    }

    public record SourceLoadState(SourceLoadStage stage, Exception exception) {}

    private final ConcurrentMap<String, SourceLoadState> stateMap = new ConcurrentHashMap<>();

    @Getter
    private final Collection<SourceDefinition> sourceDefinitions;

    @Inject
    CamelContext camelContext;

    public SourceLoadStateTracker() {
        sourceDefinitions = new ArrayList<>();
    }

    @PostConstruct
    public void init() {
        sourceDefinitions.addAll(buildSourceDefinitions());
    }

    public void addSourceDefinitions(Collection<SourceDefinition> sourceDefinitions) {
        this.sourceDefinitions.addAll(sourceDefinitions);
    }

    public SourceLoadState getLoadState(String id) {
        return stateMap.getOrDefault(id, new SourceLoadState(SourceLoadStage.UNKNOWN, null));
    }

    @Override
    public void onSourceProcessingStart(Resource resource) {
        log.debug("Start processing resource {}", resource.getLocation());
        insertState(resource, new SourceLoadState(SourceLoadStage.PROCESSING, null));
    }

    @Override
    public void onSourceLoaded(Resource resource) {
        log.debug("Successfully loaded resource {}", resource.getLocation());
        insertState(resource, new SourceLoadState(SourceLoadStage.SUCCESS, null));
    }

    @Override
    public void onSourceLoadFailed(Resource resource, Exception exception) {
        log.debug("Failed to load resource {}", resource.getLocation(), exception);
        insertState(resource, new SourceLoadState(SourceLoadStage.FAILED, exception));
    }

    private void insertState(Resource resource, SourceLoadState state) {
        stateMap.compute(getKey(resource), (key, value) -> state);
    }

    private String getKey(Resource resource) {
        return getCorrespondingSourceId(resource);
    }

    private String getCorrespondingSourceId(Resource resource) {
        return sourceDefinitions.stream()
                .filter(definition -> definition.getLocation().equals(resource.getLocation()))
                .findFirst()
                .map(SourceDefinition::getId)
                .orElseGet(resource::getLocation);
    }

    private Collection<SourceDefinition> buildSourceDefinitions() {
        SourcesConfigurer sourcesConfigurer = new SourcesConfigurer();
        PropertiesSupport.bindProperties(
                camelContext,
                sourcesConfigurer,
                k -> k.startsWith(CAMEL_K_SOURCES_PREFIX),
                CAMEL_K_PREFIX);
        SourceDefinition[] sourceDefinitions = sourcesConfigurer.getSources();
        return isNull(sourceDefinitions) ? Collections.emptyList() : Arrays.asList(sourceDefinitions);
    }
}
