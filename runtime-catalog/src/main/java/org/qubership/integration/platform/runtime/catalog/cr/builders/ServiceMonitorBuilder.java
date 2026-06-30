package org.qubership.integration.platform.runtime.catalog.cr.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ServiceMonitorBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final String TEMPLATE_NAME = "service-monitor";

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Data
    @Builder
    private static class TemplateData {
        private String name;
        private String domainLabel;
        private String domainName;
        private String bgVersionLabel;
        private String bgVersion;
        private String integrationName;
        private String serviceName;
        private String interval;
        private String namespace;
    }

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceMonitorNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Autowired
    public ServiceMonitorBuilder(
            Handlebars templates,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            @Qualifier("serviceMonitorNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceMonitorNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.templates = templates;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.serviceMonitorNamingStrategy = serviceMonitorNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return context.getBuildInfo().getOptions().getMonitoring().isEnabled();
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        TemplateData templateData = buildTemplateData(context);
        Context templateContext = Context.newContext(templateData);
        Template template = templates.compile(TEMPLATE_NAME);
        return template.apply(templateContext);
    }

    private TemplateData buildTemplateData(ResourceBuildContext<List<Snapshot>> context) {
        return TemplateData.builder()
                .domainLabel(domainLabel)
                .domainName(k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()))
                .bgVersionLabel(bgVersionLabel)
                .bgVersion(bgVersion)
                .name(serviceMonitorNamingStrategy.getName(context))
                .integrationName(integrationResourceNamingStrategy.getName(context))
                .serviceName(serviceNamingStrategy.getName(context))
                .namespace(getNamespace(context))
                .interval(getMetricsScrapeInterval(context))
                .build();
    }

    private String getMetricsScrapeInterval(ResourceBuildContext<?> context) {
        String interval = context.getBuildInfo().getOptions().getMonitoring().getInterval();
        return StringUtils.isBlank(interval)
                ? "{{ .Values.monitoring.interval | default \"30s\" }}"
                : interval;
    }

    private String getNamespace(ResourceBuildContext<?> context) {
        String namespace = context.getBuildInfo().getOptions().getNamespace();
        return StringUtils.isBlank(namespace)
                ? "{{ .Release.namespace }}"
                : namespace;
    }
}
