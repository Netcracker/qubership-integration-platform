package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.CustomResourceBuildError;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.cr.sources.IntegrationSourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.sources.IntegrationSourceBuilderFactory;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;

@Slf4j
@Component
public class SourceConfigMapBuilder implements ResourceBuilder<Snapshot> {
    public static final String CONTENT_KEY = "content";
    public static final String CHAIN_ID_LABEL = "org.qubership.integration.platform/chainId";
    public static final String SNAPSHOT_ID_LABEL = "org.qubership.integration.platform/snapshotId";

    private final YAMLMapper yamlMapper;
    private final IntegrationSourceBuilderFactory integrationSourceBuilderFactory;
    private final NamingStrategy<ResourceBuildContext<Snapshot>> configMapNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;

    @Autowired
    public SourceConfigMapBuilder(
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,

            IntegrationSourceBuilderFactory integrationSourceBuilderFactory,

            @Qualifier("sourceDslConfigMapNamingStrategy")
            NamingStrategy<ResourceBuildContext<Snapshot>> configMapNamingStrategy,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy
    ) {
        this.yamlMapper = yamlMapper;
        this.integrationSourceBuilderFactory = integrationSourceBuilderFactory;
        this.configMapNamingStrategy = configMapNamingStrategy;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
    }

    @Override
    public boolean enabled(ResourceBuildContext<Snapshot> context) {
        return true;
    }

    @Override
    public String build(ResourceBuildContext<Snapshot> context) throws Exception {
        Snapshot snapshot = context.getData();
        Chain chain = snapshot.getChain();
        ResourceBuildOptions options = context.getBuildInfo().getOptions();
        String language = options.getLanguage();
        IntegrationSourceBuilder sourceBuilder = integrationSourceBuilderFactory.getBuilder(language);
        SourceBuilderContext sourceBuilderContext = createSourceBuilderContext(context);

        try {
            ObjectNode configMapNode = yamlMapper.createObjectNode();
            configMapNode.set("apiVersion", configMapNode.textNode("v1"));
            configMapNode.set("kind", configMapNode.textNode("ConfigMap"));

            ObjectNode metadataNode = configMapNode.withObjectProperty("metadata");
            metadataNode.set("name", metadataNode.textNode(configMapNamingStrategy.getName(context)));

            String integrationName = integrationResourceNamingStrategy.getName(context.updateTo(Collections.emptyList()));
            ObjectNode labelsNode = metadataNode.withObject("labels");
            labelsNode.set(CAMEL_K_INTEGRATION_LABEL, labelsNode.textNode(integrationName));
            labelsNode.set(CHAIN_ID_LABEL, labelsNode.textNode(chain.getId()));
            labelsNode.set(SNAPSHOT_ID_LABEL, labelsNode.textNode(snapshot.getId()));

            configMapNode.withObjectProperty("data")
                    .set(CONTENT_KEY, configMapNode.textNode(sourceBuilder.build(snapshot, sourceBuilderContext)));

            return yamlMapper.writeValueAsString(configMapNode);
        } catch (Exception e) {
            String message = String.format(
                    "Failed to build integration source ConfigMap for snapshot '%s' (%s) of chain '%s' (%s)",
                    snapshot.getId(),
                    snapshot.getName(),
                    chain.getName(),
                    chain.getId()
            );
            log.error(message, e);
            throw new CustomResourceBuildError(message, e);
        }
    }

    private SourceBuilderContext createSourceBuilderContext(ResourceBuildContext<?> context) {
        return SourceBuilderContext.builder()
                .domainName(context.getBuildInfo().getOptions().getName())
                .buildName(context.getBuildInfo().getName())
                .buildTimestamp(context.getBuildInfo().getTimestamp())
                .build();
    }
}
