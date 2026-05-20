package org.qubership.integration.platform.runtime.catalog.cr.builders;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ServiceResourceBuilder  implements ResourceBuilder<List<Snapshot>> {
    private static final String TEMPLATE_NAME = "service";

    @Data
    @Builder
    private static class TemplateData {
        private String name;
        private String integrationName;
    }

    private final Handlebars templates;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;

    @Autowired
    public ServiceResourceBuilder(
            Handlebars templates,

            @Qualifier("serviceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy
    ) {
        this.templates = templates;
        this.serviceNamingStrategy = serviceNamingStrategy;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
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
                .name(serviceNamingStrategy.getName(context))
                .integrationName(integrationResourceNamingStrategy.getName(context))
                .build();
    }
}
