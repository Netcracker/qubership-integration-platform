package org.qubership.integration.platform.engine.service.groovy;

import groovy.lang.GroovyShell;
import org.apache.camel.CamelContext;
import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.Model;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ScriptDefinition;
import org.apache.camel.model.WhenDefinition;
import org.apache.camel.model.language.GroovyExpression;
import org.apache.camel.model.language.SimpleExpression;
import org.apache.camel.spi.Resource;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.dsl.notification.SourceProcessingNotifier;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GroovyScriptCompilationServiceTest {

    private GroovyScriptCompilationService service;
    private GroovyLanguageWithResettableCache groovyLanguage;
    private SourceProcessingNotifier notifier;
    private Model model;
    private CamelContext context;
    private final List<RouteDefinition> routes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CustomGroovyShellFactory shellFactory = mock(CustomGroovyShellFactory.class);
        when(shellFactory.createGroovyShell(null)).thenAnswer(invocation -> new GroovyShell());
        groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        notifier = mock(SourceProcessingNotifier.class);

        service = new GroovyScriptCompilationService();
        service.groovyShellFactory = shellFactory;
        service.groovyLanguage = groovyLanguage;
        service.sourceProcessingNotifier = notifier;

        model = mock(Model.class);
        when(model.getRouteDefinitions()).thenReturn(routes);
        ExtendedCamelContext extension = mock(ExtendedCamelContext.class);
        when(extension.getContextPlugin(Model.class)).thenReturn(model);
        context = mock(CamelContext.class);
        when(context.getCamelContextExtension()).thenReturn(extension);
    }

    @Test
    void shouldCompileEmptyScriptWithoutThrowingWhenGroovyExpressionTextIsNull() throws Exception {
        routes.add(route(source("healthy"), new GroovyExpression()));

        service.compileRoutes(context);

        verify(groovyLanguage).addScriptToCache(eq(""), any());
        verify(model, never()).removeRouteDefinitions(any());
    }

    @Test
    void shouldStillCompileNonEmptyGroovyScript() throws Exception {
        routes.add(route(source("healthy"), new GroovyExpression("1 + 1")));

        service.compileRoutes(context);

        verify(groovyLanguage).addScriptToCache(eq("1 + 1"), any());
        verify(notifier, never()).notifySourceLoadFailed(any(), any());
    }

    @Test
    void shouldFailSourceAndRemoveItsRoutesWhenNestedGroovyScriptCannotCompile() throws Exception {
        Resource broken = source("broken");
        RouteDefinition brokenRoute = nestedRoute(broken, new GroovyExpression("if ("));
        RouteDefinition brokenSibling = route(broken, new GroovyExpression("1 + 1"));
        RouteDefinition healthyRoute = route(source("healthy"), new GroovyExpression("2 + 2"));
        routes.addAll(List.of(brokenRoute, brokenSibling, healthyRoute));

        service.compileRoutes(context);

        ArgumentCaptor<Exception> failure = ArgumentCaptor.forClass(Exception.class);
        verify(notifier).notifySourceLoadFailed(eq(broken), failure.capture());
        assertEquals("Failed to compile groovy script.", failure.getValue().getMessage());
        verify(model).removeRouteDefinitions(List.of(brokenRoute, brokenSibling));
        verify(groovyLanguage).addScriptToCache(eq("2 + 2"), any());
    }

    @Test
    void shouldFailSourceWhenScriptImportsUnknownClass() throws Exception {
        Resource unresolved = source("unresolved");
        RouteDefinition route = route(unresolved, new GroovyExpression("import com.example.Missing\n1"));
        routes.add(route);

        service.compileRoutes(context);

        verify(notifier).notifySourceLoadFailed(eq(unresolved), any());
        verify(model).removeRouteDefinitions(List.of(route));
    }

    private static Resource source(String name) {
        return ResourceHelper.fromString("file:/sources/" + name + ".xml", "");
    }

    private static RouteDefinition route(Resource resource, GroovyExpression expression) {
        RouteDefinition routeDefinition = new RouteDefinition();
        routeDefinition.setResource(resource);
        routeDefinition.getOutputs().add(new ScriptDefinition(expression));
        return routeDefinition;
    }

    private static RouteDefinition nestedRoute(Resource resource, GroovyExpression expression) {
        RouteDefinition routeDefinition = new RouteDefinition();
        routeDefinition.setResource(resource);
        ChoiceDefinition choice = new ChoiceDefinition();
        WhenDefinition when = new WhenDefinition(new SimpleExpression("true"));
        when.getOutputs().add(new ScriptDefinition(expression));
        choice.addOutput(when);
        routeDefinition.getOutputs().add(choice);
        return routeDefinition;
    }
}
