package org.qubership.integration.platform.camelk.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ServiceResourceBuilder  implements ResourceBuilder<List<Snapshot>> {
    private static final String TEMPLATE_NAME = "service";

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Data
    @Builder
    private static class TemplateData {
        private String domainLabel;
        private String domainName;
        private String bgVersionLabel;
        private String bgVersion;
        private String name;
        private String integrationName;
    }

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Autowired
    public ServiceResourceBuilder(
            Handlebars templates,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.templates = templates;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return context.getBuildInfo().getOptions().getService().isEnabled();
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
                .name(serviceNamingStrategy.getName(context))
                .integrationName(integrationResourceNamingStrategy.getName(context))
                .build();
    }
}
