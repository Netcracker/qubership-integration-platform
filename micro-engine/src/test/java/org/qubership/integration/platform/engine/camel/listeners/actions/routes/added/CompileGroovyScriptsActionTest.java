package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import groovy.lang.GroovyShell;
import org.apache.camel.Route;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ScriptDefinition;
import org.apache.camel.model.WhenDefinition;
import org.apache.camel.model.language.GroovyExpression;
import org.apache.camel.model.language.SimpleExpression;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.groovy.CustomGroovyShellFactory;
import org.qubership.integration.platform.engine.service.groovy.GroovyLanguageWithResettableCache;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CompileGroovyScriptsActionTest {

    private CompileGroovyScriptsAction action;
    private GroovyLanguageWithResettableCache groovyLanguage;

    @BeforeEach
    void setUp() {
        CustomGroovyShellFactory shellFactory = mock(CustomGroovyShellFactory.class);
        when(shellFactory.createGroovyShell(null)).thenReturn(new GroovyShell());
        groovyLanguage = mock(GroovyLanguageWithResettableCache.class);

        action = new CompileGroovyScriptsAction();
        action.groovyShellFactory = shellFactory;
        action.groovyLanguage = groovyLanguage;
    }

    @Test
    void shouldCompileEmptyScriptWithoutThrowingWhenGroovyExpressionTextIsNull() {
        CamelEvent.RouteAddedEvent event = routeAddedEventWith(new GroovyExpression());

        assertDoesNotThrow(() -> action.process(event));

        verify(groovyLanguage).addScriptToCache(eq(""), any());
    }

    @Test
    void shouldStillCompileNonEmptyGroovyScript() {
        CamelEvent.RouteAddedEvent event = routeAddedEventWith(new GroovyExpression("1 + 1"));

        assertDoesNotThrow(() -> action.process(event));

        verify(groovyLanguage).addScriptToCache(eq("1 + 1"), any());
    }

    @Test
    void shouldRejectDeploymentWhenNestedGroovyScriptCannotCompile() {
        CamelEvent.RouteAddedEvent event = routeAddedEventWithNested(new GroovyExpression("if ("));

        assertThrows(RuntimeException.class, () -> action.process(event));
    }

    private static CamelEvent.RouteAddedEvent routeAddedEventWith(GroovyExpression expression) {
        RouteDefinition routeDefinition = new RouteDefinition();
        routeDefinition.getOutputs().add(new ScriptDefinition(expression));

        Route route = mock(Route.class);
        when(route.getRoute()).thenReturn(routeDefinition);
        CamelEvent.RouteAddedEvent event = mock(CamelEvent.RouteAddedEvent.class);
        when(event.getRoute()).thenReturn(route);
        return event;
    }

    private static CamelEvent.RouteAddedEvent routeAddedEventWithNested(GroovyExpression expression) {
        RouteDefinition routeDefinition = new RouteDefinition();
        ChoiceDefinition choice = new ChoiceDefinition();
        WhenDefinition when = new WhenDefinition(new SimpleExpression("true"));
        when.getOutputs().add(new ScriptDefinition(expression));
        choice.addOutput(when);
        routeDefinition.getOutputs().add(choice);

        Route route = mock(Route.class);
        when(route.getRoute()).thenReturn(routeDefinition);
        CamelEvent.RouteAddedEvent event = mock(CamelEvent.RouteAddedEvent.class);
        when(event.getRoute()).thenReturn(route);
        return event;
    }
}
