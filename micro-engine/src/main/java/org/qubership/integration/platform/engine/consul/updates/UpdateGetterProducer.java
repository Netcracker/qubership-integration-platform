package org.qubership.integration.platform.engine.consul.updates;

import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.consul.updates.parsers.ChainRuntimePropertiesUpdateParser;
import org.qubership.integration.platform.engine.consul.updates.parsers.CommonVariablesUpdateParser;
import org.qubership.integration.platform.engine.consul.updates.parsers.DeploymentUpdateParser;
import org.qubership.integration.platform.engine.consul.updates.parsers.LibrariesUpdateParser;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;

import java.util.*;

@Slf4j
@ApplicationScoped
public class UpdateGetterProducer {
    @ConfigProperty(name = "consul.keys.prefix")
    String keyPrefix;

    @ConfigProperty(name = "consul.keys.engine-config-root")
    String keyEngineConfigRoot;

    @ConfigProperty(name = "consul.keys.deployments-update")
    String keyDeploymentsUpdate;

    @ConfigProperty(name = "consul.keys.libraries-update")
    String keyLibrariesUpdate;

    @ConfigProperty(name = "consul.keys.runtime-configurations")
    String keyRuntimeConfigurations;

    @ConfigProperty(name = "consul.keys.chains")
    String keyChains;

    @ConfigProperty(name = "consul.keys.common-variables-v2")
    String keyCommonVariablesV2;

    @Produces
    @Named("deploymentUpdateGetter")
    @ApplicationScoped
    public UpdateGetterHelper<Long> deploymentUpdateGetter(
            ConsulClient consulClient,
            DeploymentUpdateParser valueParser
    ) {
        return new UpdateGetterHelper<>(
                keyPrefix + keyEngineConfigRoot + keyDeploymentsUpdate,
                consulClient,
                valueParser
        );
    }

    @Produces
    @Named("librariesUpdateGetter")
    @ApplicationScoped
    public UpdateGetterHelper<List<CompiledLibraryUpdate>> librariesUpdateGetter(
            ConsulClient consulClient,
            LibrariesUpdateParser valueParser
    ) {
        return new UpdateGetterHelper<>(
                keyPrefix + keyEngineConfigRoot + keyLibrariesUpdate,
                consulClient,
                valueParser
        );
    }

    @Produces
    @Named("chainRuntimePropertiesUpdateGetter")
    @ApplicationScoped
    public UpdateGetterHelper<Map<String, DeploymentRuntimeProperties>> chainRuntimePropertiesUpdateGetter(
            ConsulClient consulClient,
            ChainRuntimePropertiesUpdateParser valueParser
    ) {
        return new UpdateGetterHelper<>(
                keyPrefix + keyEngineConfigRoot + keyRuntimeConfigurations + keyChains,
                consulClient,
                valueParser
        );
    }

    @Produces
    @Named("commonVariablesUpdateGetter")
    @ApplicationScoped
    public UpdateGetterHelper<Map<String, String>> commonVariablesUpdateGetter(
            ConsulClient consulClient,
            CommonVariablesUpdateParser valueParser
    ) {
        return new UpdateGetterHelper<>(
                keyPrefix + keyEngineConfigRoot + keyCommonVariablesV2,
                consulClient,
                valueParser
        );
    }
}
