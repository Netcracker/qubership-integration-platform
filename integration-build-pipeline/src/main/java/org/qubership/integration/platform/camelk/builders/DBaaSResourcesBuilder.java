package org.qubership.integration.platform.camelk.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.camelk.model.options.DBaaSOperatorType;
import org.qubership.integration.platform.camelk.model.options.DBaaSOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.camelk.naming.validation.K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT;

@Component
public class DBaaSResourcesBuilder implements ResourceBuilder<List<Snapshot>> {
    private static final Map<String, String> RESOURCE_TEMPLATES = Map.of(
        "checkpoints", "dbaas-checkpoints",
        "configs", "dbaas-configs"
    );

    @Value("${cip.cr.labels.domain}")
    String domainLabel;

    @Data
    @Builder
    private static class TemplateData {
        private String domainLabel;
        private String domainName;
        private String name;
        private String integrationName;
        private String dbaasOperatorNamespace;
        private String cloudServiceName;
    }

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> cloudServiceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Autowired
    public DBaaSResourcesBuilder(
        @Qualifier("customResourceTemplates")
        Handlebars templates,

        @Qualifier("cloudServiceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> cloudServiceNamingStrategy,

        @Qualifier("integrationResourceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

        K8sNameValidator k8sNameValidator
    ) {
        this.templates = templates;
        this.cloudServiceNamingStrategy = cloudServiceNamingStrategy;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        DBaaSOptions dbaaSOptions = context.getBuildInfo().getOptions().getDbaas();
        return dbaaSOptions.isEnabled() && DBaaSOperatorType.DBAAS.equals(dbaaSOptions.getOperatorType());
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : RESOURCE_TEMPLATES.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
            String dbName = entry.getKey();
            String templateName = entry.getValue();
            TemplateData templateData = buildTemplateData(context, dbName);
            Context templateContext = Context.newContext(templateData);
            Template template = templates.compile(templateName);
            String resourceText = template.apply(templateContext);
            stringBuilder.append(resourceText);
        }
        return stringBuilder.toString();
    }

    private TemplateData buildTemplateData(ResourceBuildContext<List<Snapshot>> context, String dbName) {
        String cloudServiceName = cloudServiceNamingStrategy.getName(context);
        String suffix = "-" + dbName;
        // Reserve room for the full suffix before truncating, so a long cloud service name cannot
        // cut into it and give both databases the same resource name.
        String base = StringUtils.truncate(cloudServiceName, K8S_RESOURCE_NAME_LENGTH_LIMIT - suffix.length());
        return TemplateData.builder()
            .domainLabel(domainLabel)
            .domainName(k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()))
            .name(k8sNameValidator.validate(base + suffix))
            .integrationName(integrationResourceNamingStrategy.getName(context))
            .cloudServiceName(cloudServiceName)
            .dbaasOperatorNamespace(context.getBuildInfo().getOptions().getDbaas().getOperatorNamespace())
            .build();
    }
}
