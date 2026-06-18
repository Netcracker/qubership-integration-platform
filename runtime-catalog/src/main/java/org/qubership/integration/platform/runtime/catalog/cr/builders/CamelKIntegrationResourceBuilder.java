package org.qubership.integration.platform.runtime.catalog.cr.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.CustomResourceBuildError;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.builders.chain.SourceConfigMapBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.SourceDefinition;
import org.qubership.integration.platform.runtime.catalog.cr.locations.SourceMountPointGetter;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ContainerOptions;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.Limits;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class CamelKIntegrationResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final String QIP_CHAINS_CONFIGURATION_PATH = "/etc/integrations-config.yaml";
    private static final String TEMPLATE_NAME = "integration";

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Data
    @Builder
    private static class ContainerData {
        String image;
        String imagePullPolicy;
        Limits limit;
        Limits request;
    }

    @Data
    @Builder
    private static class TemplateData {
        private String name;
        private String domainLabel;
        private String domainName;
        private Integer replicas;
        private ContainerData container;
        private String jvmJar;
        private List<String> jvmArgs;
        private Collection<String> emptyDirs;
        private Collection<String> resources;
        private Collection<String> properties;
        private Collection<String> environment;
        private boolean propertiesEnabled;
        private String serviceAccountName;
    }

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<Snapshot>> sourceDslConfigMapNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy;
    private final SourceMountPointGetter sourceMountPointGetter;
    private final SourceDefinitionBuilder sourceDefinitionBuilder;
    private final K8sNameValidator k8sNameValidator;

    @Autowired
    public CamelKIntegrationResourceBuilder(
            @Qualifier("customResourceTemplates")
            Handlebars templates,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy,

            @Qualifier("sourceDslConfigMapNamingStrategy")
            NamingStrategy<ResourceBuildContext<Snapshot>> sourceDslConfigMapNamingStrategy,

            SourceMountPointGetter sourceMountPointGetter,
            SourceDefinitionBuilder sourceDefinitionBuilder,
            K8sNameValidator k8sNameValidator
    ) {
        this.templates = templates;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.sourceDslConfigMapNamingStrategy = sourceDslConfigMapNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.sourceMountPointGetter = sourceMountPointGetter;
        this.sourceDefinitionBuilder = sourceDefinitionBuilder;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return true;
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        if (context.getData().isEmpty()) {
            throw new CustomResourceBuildError("Chain list is empty");
        }

        TemplateData templateData = buildTemplateData(context);
        Context templateContext = Context.newContext(templateData);
        Template template = templates.compile(TEMPLATE_NAME);
        return template.apply(templateContext);
    }

    private TemplateData buildTemplateData(ResourceBuildContext<List<Snapshot>> context) {
        return TemplateData.builder()
                .name(integrationResourceNamingStrategy.getName(context))
                .domainLabel(domainLabel)
                .domainName(k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()))
                .replicas(context.getBuildInfo().getOptions().getReplicas())
                .container(buildContainerData(context.getBuildInfo().getOptions().getContainer()))
                .jvmJar(context.getBuildInfo().getOptions().getJvm().getJar())
                .jvmArgs(context.getBuildInfo().getOptions().getJvm().getArgs())
                .emptyDirs(context.getBuildInfo().getOptions().getEmptyDirs())
                .resources(buildResources(context))
                .propertiesEnabled(!context.getBuildInfo().getOptions()
                        .getIntegrations().isConfigurationConfigMapNeeded())
                .properties(buildCamelProperties(context))
                .environment(buildEnvironmentVars(context))
                .serviceAccountName(buildServiceAccountName(context))
                .build();
    }

    private ContainerData buildContainerData(ContainerOptions containerOptions) {
        String image = containerOptions.getImage();
        if (StringUtils.isBlank(image)) {
            image = "{{ .Values.container.image }}";
        }
        return ContainerData.builder()
                .image(image)
                .imagePullPolicy(containerOptions.getImagePoolPolicy().name())
                .limit(containerOptions.getLimit())
                .request(containerOptions.getRequest())
                .build();
    }

    private String buildServiceAccountName(ResourceBuildContext<?> context) {
        String serviceAccount = context.getBuildInfo().getOptions().getServiceAccount();
        return StringUtils.isBlank(serviceAccount)
                ? "{{ .Values.serviceAccountName }}"
                : serviceAccount;
    }

    private Collection<String> buildResources(ResourceBuildContext<List<Snapshot>> context) {
        List<String> resources = context.getData()
                .stream()
                .map(snapshot -> {
                    ResourceBuildContext<Snapshot> chainResourceBuildContext = context.updateTo(snapshot);
                    String name = sourceDslConfigMapNamingStrategy.getName(chainResourceBuildContext);
                    return String.format("configmap:%s/%s@%s",
                            name, SourceConfigMapBuilder.CONTENT_KEY, sourceMountPointGetter.apply(chainResourceBuildContext));
                })
                .collect(Collectors.toList());
        if (context.getBuildInfo().getOptions().getIntegrations().isConfigurationConfigMapNeeded()) {
            String name = integrationsConfigurationConfigMapNamingStrategy.getName(context);
            String resource = String.format("configmap:%s/%s@%s", name,
                    IntegrationsConfigurationConfigMapBuilder.CONTENT_KEY, QIP_CHAINS_CONFIGURATION_PATH);
            resources.add(resource);
        }
        Set<String> result = new HashSet<>(context.getBuildInfo().getOptions().getResources());
        result.addAll(resources);
        return result;
    }

    private Collection<String> buildCamelProperties(ResourceBuildContext<List<Snapshot>> context) {
        List<Snapshot> snapshots = context.getData();
        ResourceBuildOptions options = context.getBuildInfo().getOptions();
        return IntStream.range(0, snapshots.size())
                .mapToObj(index -> {
                    Snapshot snapshot = snapshots.get(index);
                    SourceDefinition sourceDefinition = sourceDefinitionBuilder.build(context.updateTo(snapshot));
                    return List.of(
                            String.format("camel.k.sources[%d].language = %s", index, sourceDefinition.getLanguage()),
                            String.format("camel.k.sources[%d].location = %s", index, sourceDefinition.getLocation()),
                            String.format("camel.k.sources[%d].name = %s", index, sourceDefinition.getName()),
                            String.format("camel.k.sources[%d].id = %s", index, sourceDefinition.getId())
                    );
                })
                .flatMap(Collection::stream)
                .toList();
    }

    private Collection<String> buildEnvironmentVars(ResourceBuildContext<List<Snapshot>> context) {
        Map<String, String> environment = new HashMap<>(context.getBuildInfo().getOptions().getEnvironment());
        environment.put("CLOUD_SERVICE_NAME", serviceNamingStrategy.getName(context));
        environment.put("QIP_ENGINE_DOMAIN", context.getBuildInfo().getOptions().getName());
        if (!context.getBuildInfo().getOptions().getIntegrations().isCamelKSourcesUtilized()) {
            String location = context.getBuildInfo().getOptions().getIntegrations().getConfigurationLocation();
            environment.put("QIP_CHAINS_CONFIGURATION_URL",
                    StringUtils.isBlank(location) ? "file:" + QIP_CHAINS_CONFIGURATION_PATH : location);
        }
        return environment
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue()))
                .toList();
    }
}
