package org.qubership.integration.platform.camelk.builders;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfigurationBuilder;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuildError;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

import static org.qubership.integration.platform.camelk.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class IntegrationsConfigurationConfigMapBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String CONTENT_KEY = "content";

    private final YAMLMapper resourceYamlMapper;
    private final YAMLMapper integrationConfigurationMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final IntegrationsConfigurationBuilder integrationsConfigurationBuilder;
    private final K8sNameValidator k8sNameValidator;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public IntegrationsConfigurationConfigMapBuilder(
            @Qualifier("customResourceYamlMapper")
            YAMLMapper resourceYamlMapper,

            @Qualifier("integrationsConfigurationMapper")
            YAMLMapper integrationConfigurationMapper,

            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> namingStrategy,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            IntegrationsConfigurationBuilder integrationsConfigurationBuilder,

            K8sNameValidator k8sNameValidator
    ) {
        this.resourceYamlMapper = resourceYamlMapper;
        this.integrationConfigurationMapper = integrationConfigurationMapper;
        this.namingStrategy = namingStrategy;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationBuilder = integrationsConfigurationBuilder;
        this.k8sNameValidator = k8sNameValidator;
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
            ObjectNode labelsNode = metadataNode.withObject("labels");
            labelsNode.set(CAMEL_K_INTEGRATION_LABEL, metadataNode.textNode(integrationName));
            labelsNode.set(domainLabel, metadataNode.textNode(k8sNameValidator.validate(context.getBuildInfo().getOptions().getName())));
            labelsNode.set(bgVersionLabel, metadataNode.textNode(bgVersion));


            IntegrationsConfiguration integrationsConfiguration = integrationsConfigurationBuilder.build(context);

            if (context.getBuildCache().containsKey(name)) {
                integrationsConfiguration = ((IntegrationsConfiguration) context.getBuildCache().get(name))
                        .merge(integrationsConfiguration);
            }


            String content = integrationConfigurationMapper.writeValueAsString(integrationsConfiguration);
            configMapNode.withObjectProperty("data")
                    .set(CONTENT_KEY, configMapNode.textNode(content));

            return resourceYamlMapper.writeValueAsString(configMapNode);
        } catch (Exception e) {
            String message = "Failed to build integration source ConfigMap for chains configuration";
            log.error(message, e);
            throw new ResourceBuildError(message, e);
        }
    }
}
