package org.qubership.integration.platform.engine.service.groovy;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.model.ExpressionNode;
import org.apache.camel.model.Model;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.spi.Resource;
import org.codehaus.groovy.control.CompilationFailedException;
import org.qubership.integration.platform.engine.camel.dsl.notification.SourceProcessingNotifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

// A source with a script that does not compile is reported FAILED, and its routes are removed before the context starts.
@Slf4j
@Unremovable
@ApplicationScoped
public class GroovyScriptCompilationService {
    @Inject
    CustomGroovyShellFactory groovyShellFactory;

    @Inject
    GroovyLanguageWithResettableCache groovyLanguage;

    @Inject
    SourceProcessingNotifier sourceProcessingNotifier;

    public void compileRoutes(CamelContext context) throws Exception {
        Model model = context.getCamelContextExtension().getContextPlugin(Model.class);
        Map<Resource, List<RouteDefinition>> routesBySource = model.getRouteDefinitions().stream()
                .collect(Collectors.groupingBy(RouteDefinition::getResource, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Resource, List<RouteDefinition>> entry : routesBySource.entrySet()) {
            Resource resource = entry.getKey();
            try {
                for (RouteDefinition route : entry.getValue()) {
                    compileGroovyScripts(route);
                }
            } catch (CompilationFailedException exception) {
                log.error("Failed to compile a groovy script from {}. Its routes will not start.",
                        resource.getLocation(), exception);
                sourceProcessingNotifier.notifySourceLoadFailed(resource,
                        new RuntimeException("Failed to compile groovy script.", exception));
                model.removeRouteDefinitions(entry.getValue());
            }
        }
    }

    private void compileGroovyScripts(ProcessorDefinition<?> parent) {
        for (ProcessorDefinition<?> processor : parent.getOutputs()) {
            if (processor instanceof ExpressionNode) {
                ExpressionDefinition expression = ((ExpressionNode) processor).getExpression();
                if (expression.getLanguage().equals("groovy")) {
                    log.debug("Compiling groovy script for processor {}", processor.getId());
                    compileGroovyScript(expression);
                }
            }
            compileGroovyScripts(processor);
        }
    }

    @SuppressWarnings("unchecked")
    private void compileGroovyScript(ExpressionDefinition expression) {
        String text = expression.getExpression();
        if (isNull(text)) {
            // camel-xml-io-dsl leaves the expression null for empty/whitespace-only <groovy>
            // bodies; treat it as an empty (no-op) script instead of dereferencing null below.
            text = "";
        }
        if (isNull(expression.getTrim()) || Boolean.parseBoolean(expression.getTrim())) {
            text = text.trim();
        }

        GroovyShell groovyShell = groovyShellFactory.createGroovyShell(null);
        Class<Script> scriptClass = groovyShell.getClassLoader().parseClass(text);
        groovyLanguage.addScriptToCache(text, scriptClass);
    }
}
