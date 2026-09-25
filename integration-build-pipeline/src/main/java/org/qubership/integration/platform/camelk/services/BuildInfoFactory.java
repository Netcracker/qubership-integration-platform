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

    public BuildInfo createBuildInfo(ResourceBuildOptions options, String createdBy) {
        return createBuildInfo(options, createdBy, Instant.now());
    }

    /**
     * Builds with a caller-supplied timestamp, for a host that needs the same input to produce the
     * same output. The timestamp reaches the generated resources as the build name and the
     * {@code DeploymentInfo} timestamp, so a clock reading makes every build differ.
     */
    public BuildInfo createBuildInfo(ResourceBuildOptions options, String createdBy, Instant timestamp) {
        String id = UUID.randomUUID().toString();
        BuildNamingContext buildNamingContext = BuildNamingContext.builder()
            .id(id)
            .timestamp(timestamp)
            .build();
        return BuildInfo.builder()
            .id(id)
            .timestamp(timestamp)
            .name(buildNamingStrategy.getName(buildNamingContext))
            .options(options)
            .createdBy(createdBy)
            .build();
    }
}
