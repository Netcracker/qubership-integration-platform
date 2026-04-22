package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.NamedNode;
import org.apache.camel.model.ExpressionNode;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.spi.CamelEvent;
import org.codehaus.groovy.control.CompilationFailedException;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteAdded;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.service.groovy.CustomGroovyShellFactory;
import org.qubership.integration.platform.engine.service.groovy.GroovyLanguageWithResettableCache;

import static java.util.Objects.isNull;

@Slf4j
@OnRouteAdded
@ApplicationScoped
public class CompileGroovyScriptsAction implements EventProcessingAction<CamelEvent.RouteAddedEvent> {
    @Inject
    CustomGroovyShellFactory groovyShellFactory;

    @Inject
    GroovyLanguageWithResettableCache groovyLanguage;

    @Override
    public void process(CamelEvent.RouteAddedEvent event) throws Exception {
        NamedNode node = event.getRoute().getRoute();
        if (node instanceof RouteDefinition routeDefinition) {
            compileGroovyScripts(routeDefinition);
        }
    }

    private void compileGroovyScripts(RouteDefinition routeDefinition) {
        for (ProcessorDefinition<?> processor : routeDefinition.getOutputs()) {
            if (!(processor instanceof ExpressionNode)) {
                continue;
            }
            ExpressionDefinition expression = ((ExpressionNode) processor).getExpression();
            if (!expression.getLanguage().equals("groovy")) {
                continue;
            }

            log.debug("Compiling groovy script for processor {}", processor.getId());
            compileGroovyScript(expression);
        }
    }

    @SuppressWarnings("unchecked")
    private void compileGroovyScript(ExpressionDefinition expression) {
        try {
            String text = expression.getExpression();
            if (isNull(expression.getTrim()) || Boolean.parseBoolean(expression.getTrim())) {
                text = text.trim();
            }

            GroovyShell groovyShell = groovyShellFactory.createGroovyShell(null);
            Class<Script> scriptClass = groovyShell.getClassLoader().parseClass(text);
            groovyLanguage.addScriptToCache(text, scriptClass);
        } catch (CompilationFailedException exception) {
            if (isClassResolveError(exception)) {
                throw new DeploymentRetriableException("Failed to compile groovy script.",
                        exception);
            } else {
                throw new RuntimeException("Failed to compile groovy script.", exception);
            }
        }
    }

    private static boolean isClassResolveError(CompilationFailedException exception) {
        return exception.getMessage().contains("unable to resolve class");
    }
}
