package org.qubership.integration.platform.runtime.catalog.cr.configuration;

import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class HandlebarConfiguration {
    private static final String TEMPLATE_PATH = "/cr/templates";
    private static final String TEMPLATE_SUFFIX = ".hbs";

    @Bean("customResourceTemplates")
    Handlebars customResourceTemplates() {
        Handlebars handlebars = new Handlebars()
                .with(new ClassPathTemplateLoader(TEMPLATE_PATH, TEMPLATE_SUFFIX))
                .with(EscapingStrategy.NOOP);
        handlebars.prettyPrint(true);
        return handlebars;
    }
}
