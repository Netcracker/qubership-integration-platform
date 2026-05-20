package org.qubership.integration.platform.runtime.catalog.cr.builders;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.CustomResourceBuildError;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationsConfigurationBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class IntegrationsConfigurationConfigMapBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String CONTENT_KEY = "content";

    private final YAMLMapper resourceYamlMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final IntegrationsConfigurationBuilder integrationsConfigurationBuilder;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;

    @Autowired
    public IntegrationsConfigurationConfigMapBuilder(
            @Qualifier("customResourceYamlMapper")
            YAMLMapper resourceYamlMapper,

            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            IntegrationsConfigurationBuilder integrationsConfigurationBuilder,
            IntegrationConfigurationSerdes integrationConfigurationSerdes
    ) {
        this.resourceYamlMapper = resourceYamlMapper;
        this.namingStrategy = namingStrategy;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationBuilder = integrationsConfigurationBuilder;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return context.getBuildInfo().getOptions().getIntegrations().isConfigurationConfigMapNeeded();
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        try {
            ObjectNode configMapNode = resourceYamlMapper.createObjectNode();
            configMapNode.set("apiVersion", configMapNode.textNode("v1"));
            configMapNode.set("kind", configMapNode.textNode("ConfigMap"));

            String name = namingStrategy.getName(context);
            ObjectNode metadataNode = configMapNode.withObjectProperty("metadata");
            metadataNode.set("name", metadataNode.textNode(name));

            String integrationName = integrationResourceNamingStrategy.getName(context.updateTo(Collections.emptyList()));
            metadataNode.withObject("labels")
                    .set(CAMEL_K_INTEGRATION_LABEL, metadataNode.textNode(integrationName));


            IntegrationsConfiguration integrationsConfiguration = integrationsConfigurationBuilder.build(context);

            if (context.getBuildCache().containsKey(name)) {
                integrationsConfiguration = ((IntegrationsConfiguration) context.getBuildCache().get(name))
                        .merge(integrationsConfiguration);
            }

            String content = integrationConfigurationSerdes.toYaml(integrationsConfiguration);
            configMapNode.withObjectProperty("data")
                    .set(CONTENT_KEY, configMapNode.textNode(content));

            return resourceYamlMapper.writeValueAsString(configMapNode);
        } catch (Exception e) {
            String message = "Failed to build integration source ConfigMap for chains configuration";
            log.error(message, e);
            throw new CustomResourceBuildError(message, e);
        }
    }
}
