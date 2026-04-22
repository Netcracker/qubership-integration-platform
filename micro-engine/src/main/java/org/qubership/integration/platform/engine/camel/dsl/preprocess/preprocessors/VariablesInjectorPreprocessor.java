package org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.ResourceContentPreprocessor;
import org.qubership.integration.platform.engine.service.VariablesService;

@ApplicationScoped
@Priority(3)
public class VariablesInjectorPreprocessor implements ResourceContentPreprocessor {
    private final VariablesService variablesService;

    @Inject
    public VariablesInjectorPreprocessor(VariablesService variablesService) {
        this.variablesService = variablesService;
    }

    @Override
    public String apply(String content) throws Exception {
        return variablesService.injectVariables(content, true);
    }
}
