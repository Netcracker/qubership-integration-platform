package org.qubership.integration.platform.engine.camel.groovy;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.apache.camel.NamedNode;
import org.apache.camel.Route;
import org.apache.camel.model.ExpressionNode;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.spi.CamelEvent;
import org.codehaus.groovy.control.CompilationFailedException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryGroovyShellFactory;
import org.qubership.integration.platform.engine.service.externallibrary.GroovyLanguageWithResettableCache;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CompileScriptOnRouteAddedNotifierTest {

    @Test
    void shouldIgnoreNonRouteAddedEventWhenNotifyCalled() throws Exception {
        ExternalLibraryGroovyShellFactory groovyShellFactory = mock(ExternalLibraryGroovyShellFactory.class);
        GroovyLanguageWithResettableCache groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        CompileScriptOnRouteAddedNotifier notifier = notifier(groovyShellFactory, groovyLanguage);
        CamelEvent event = mock(CamelEvent.class);

        notifier.notify(event);

        verify(groovyShellFactory, never()).createGroovyShell(isNull());
        verify(groovyLanguage, never()).addScriptToCache(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Class<Script>>any());
    }

    @Test
    void shouldIgnoreRouteAddedEventWhenNamedNodeIsNotRouteDefinition() throws Exception {
        ExternalLibraryGroovyShellFactory groovyShellFactory = mock(ExternalLibraryGroovyShellFactory.class);
        GroovyLanguageWithResettableCache groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        CompileScriptOnRouteAddedNotifier notifier = notifier(groovyShellFactory, groovyLanguage);

        CamelEvent.RouteAddedEvent event = mock(CamelEvent.RouteAddedEvent.class);
        Route route = mock(Route.class);
        NamedNode namedNode = mock(NamedNode.class);

        when(event.getRoute()).thenReturn(route);
        when(route.getRoute()).thenReturn(namedNode);

        notifier.notify(event);

        verify(groovyShellFactory, never()).createGroovyShell(isNull());
        verify(groovyLanguage, never()).addScriptToCache(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Class<Script>>any());
    }

    @Test
    void shouldCompileOnlyGroovyExpressionNodesWhenRouteAdded() throws Exception {
        ExternalLibraryGroovyShellFactory groovyShellFactory = mock(ExternalLibraryGroovyShellFactory.class);
        GroovyLanguageWithResettableCache groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        CompileScriptOnRouteAddedNotifier notifier = notifier(groovyShellFactory, groovyLanguage);

        GroovyShell groovyShell = mock(GroovyShell.class);
        GroovyClassLoader groovyClassLoader = mock(GroovyClassLoader.class);
        when(groovyShellFactory.createGroovyShell(null)).thenReturn(groovyShell);
        when(groovyShell.getClassLoader()).thenReturn(groovyClassLoader);
        when(groovyClassLoader.parseClass("return 1")).thenReturn(castScriptClass(DummyScript.class));

        RouteDefinition routeDefinition = new RouteDefinition();

        ProcessorDefinition<?> nonExpressionProcessor = mock(ProcessorDefinition.class);
        ExpressionNode groovyProcessor = mock(ExpressionNode.class);
        ExpressionDefinition groovyExpression = mock(ExpressionDefinition.class);
        ExpressionNode nonGroovyProcessor = mock(ExpressionNode.class);
        ExpressionDefinition nonGroovyExpression = mock(ExpressionDefinition.class);

        when(groovyProcessor.getExpression()).thenReturn(groovyExpression);
        when(groovyProcessor.getId()).thenReturn("groovy-1");
        when(groovyExpression.getLanguage()).thenReturn("groovy");
        when(groovyExpression.getExpression()).thenReturn("  return 1  ");
        when(groovyExpression.getTrim()).thenReturn(null);

        when(nonGroovyProcessor.getExpression()).thenReturn(nonGroovyExpression);
        when(nonGroovyExpression.getLanguage()).thenReturn("simple");

        routeDefinition.getOutputs().add(nonExpressionProcessor);
        routeDefinition.getOutputs().add(groovyProcessor);
        routeDefinition.getOutputs().add(nonGroovyProcessor);

        CamelEvent.RouteAddedEvent event = routeAddedEvent(routeDefinition);

        notifier.notify(event);

        verify(groovyShellFactory).createGroovyShell(null);
        verify(groovyClassLoader).parseClass("return 1");
        verify(groovyLanguage).addScriptToCache("return 1", castScriptClass(DummyScript.class));
    }

    @Test
    void shouldPreserveWhitespaceWhenTrimDisabled() throws Exception {
        ExternalLibraryGroovyShellFactory groovyShellFactory = mock(ExternalLibraryGroovyShellFactory.class);
        GroovyLanguageWithResettableCache groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        CompileScriptOnRouteAddedNotifier notifier = notifier(groovyShellFactory, groovyLanguage);

        GroovyShell groovyShell = mock(GroovyShell.class);
        GroovyClassLoader groovyClassLoader = mock(GroovyClassLoader.class);
        when(groovyShellFactory.createGroovyShell(null)).thenReturn(groovyShell);
        when(groovyShell.getClassLoader()).thenReturn(groovyClassLoader);
        when(groovyClassLoader.parseClass("  return 1  ")).thenReturn(castScriptClass(DummyScript.class));

        RouteDefinition routeDefinition = new RouteDefinition();

        ExpressionNode groovyProcessor = mock(ExpressionNode.class);
        ExpressionDefinition groovyExpression = mock(ExpressionDefinition.class);

        when(groovyProcessor.getExpression()).thenReturn(groovyExpression);
        when(groovyProcessor.getId()).thenReturn("groovy-1");
        when(groovyExpression.getLanguage()).thenReturn("groovy");
        when(groovyExpression.getExpression()).thenReturn("  return 1  ");
        when(groovyExpression.getTrim()).thenReturn("false");

        routeDefinition.getOutputs().add(groovyProcessor);

        CamelEvent.RouteAddedEvent event = routeAddedEvent(routeDefinition);

        notifier.notify(event);

        verify(groovyClassLoader).parseClass("  return 1  ");
        verify(groovyLanguage).addScriptToCache("  return 1  ", castScriptClass(DummyScript.class));
    }

    @Test
    void shouldThrowDeploymentRetriableExceptionWhenGroovyCompilationFailsDueToClassResolveError() throws Exception {
        ExternalLibraryGroovyShellFactory groovyShellFactory = mock(ExternalLibraryGroovyShellFactory.class);
        GroovyLanguageWithResettableCache groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        CompileScriptOnRouteAddedNotifier notifier = notifier(groovyShellFactory, groovyLanguage);

        GroovyShell groovyShell = mock(GroovyShell.class);
        GroovyClassLoader groovyClassLoader = mock(GroovyClassLoader.class);
        CompilationFailedException exception = mock(CompilationFailedException.class);

        when(groovyShellFactory.createGroovyShell(null)).thenReturn(groovyShell);
        when(groovyShell.getClassLoader()).thenReturn(groovyClassLoader);
        when(exception.getMessage()).thenReturn("unable to resolve class MissingType");
        when(groovyClassLoader.parseClass("return MissingType.newInstance()")).thenThrow(exception);

        RouteDefinition routeDefinition = new RouteDefinition();

        ExpressionNode groovyProcessor = mock(ExpressionNode.class);
        ExpressionDefinition groovyExpression = mock(ExpressionDefinition.class);

        when(groovyProcessor.getExpression()).thenReturn(groovyExpression);
        when(groovyProcessor.getId()).thenReturn("groovy-1");
        when(groovyExpression.getLanguage()).thenReturn("groovy");
        when(groovyExpression.getExpression()).thenReturn("return MissingType.newInstance()");
        when(groovyExpression.getTrim()).thenReturn(null);

        routeDefinition.getOutputs().add(groovyProcessor);

        CamelEvent.RouteAddedEvent event = routeAddedEvent(routeDefinition);

        DeploymentRetriableException thrown = assertThrows(
                DeploymentRetriableException.class,
                () -> notifier.notify(event)
        );

        assertSame(exception, thrown.getCause());
    }

    @Test
    void shouldThrowRuntimeExceptionWhenGroovyCompilationFailsForOtherReason() throws Exception {
        ExternalLibraryGroovyShellFactory groovyShellFactory = mock(ExternalLibraryGroovyShellFactory.class);
        GroovyLanguageWithResettableCache groovyLanguage = mock(GroovyLanguageWithResettableCache.class);
        CompileScriptOnRouteAddedNotifier notifier = notifier(groovyShellFactory, groovyLanguage);

        GroovyShell groovyShell = mock(GroovyShell.class);
        GroovyClassLoader groovyClassLoader = mock(GroovyClassLoader.class);
        CompilationFailedException exception = mock(CompilationFailedException.class);

        when(groovyShellFactory.createGroovyShell(null)).thenReturn(groovyShell);
        when(groovyShell.getClassLoader()).thenReturn(groovyClassLoader);
        when(exception.getMessage()).thenReturn("unexpected compilation problem");
        when(groovyClassLoader.parseClass("return 1 +")).thenThrow(exception);

        RouteDefinition routeDefinition = new RouteDefinition();

        ExpressionNode groovyProcessor = mock(ExpressionNode.class);
        ExpressionDefinition groovyExpression = mock(ExpressionDefinition.class);

        when(groovyProcessor.getExpression()).thenReturn(groovyExpression);
        when(groovyProcessor.getId()).thenReturn("groovy-1");
        when(groovyExpression.getLanguage()).thenReturn("groovy");
        when(groovyExpression.getExpression()).thenReturn("return 1 +");
        when(groovyExpression.getTrim()).thenReturn(null);

        routeDefinition.getOutputs().add(groovyProcessor);

        CamelEvent.RouteAddedEvent event = routeAddedEvent(routeDefinition);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> notifier.notify(event)
        );

        assertSame(exception, thrown.getCause());
    }

    private static CompileScriptOnRouteAddedNotifier notifier(
            ExternalLibraryGroovyShellFactory groovyShellFactory,
            GroovyLanguageWithResettableCache groovyLanguage
    ) throws Exception {
        CompileScriptOnRouteAddedNotifier notifier = new CompileScriptOnRouteAddedNotifier();
        setField(notifier, "groovyShellFactory", groovyShellFactory);
        setField(notifier, "groovyLanguage", groovyLanguage);
        return notifier;
    }

    private static CamelEvent.RouteAddedEvent routeAddedEvent(RouteDefinition routeDefinition) {
        CamelEvent.RouteAddedEvent event = mock(CamelEvent.RouteAddedEvent.class);
        Route route = mock(Route.class);

        when(event.getRoute()).thenReturn(route);
        when(route.getRoute()).thenReturn(routeDefinition);

        return event;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Class<Script> castScriptClass(Class<? extends Script> clazz) {
        return (Class<Script>) clazz;
    }

    static class DummyScript extends Script {
        @Override
        public Object run() {
            return null;
        }
    }
}
