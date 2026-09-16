package org.qubership.integration.platform.camelk.services;

import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.strategies.BuildNamingContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class BuildInfoFactory {
    private final NamingStrategy<BuildNamingContext> buildNamingStrategy;

    @Autowired
    public BuildInfoFactory(NamingStrategy<BuildNamingContext> buildNamingStrategy) {
        this.buildNamingStrategy = buildNamingStrategy;
    }

    public BuildInfo createBuildInfo(ResourceBuildOptions options) {
        String id = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        BuildNamingContext buildNamingContext = BuildNamingContext.builder()
            .id(id)
            .timestamp(timestamp)
            .build();
        return BuildInfo.builder()
            .id(id)
            .timestamp(timestamp)
            .name(buildNamingStrategy.getName(buildNamingContext))
            .options(options)
            .build();
    }
}
