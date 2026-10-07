package org.qubership.integration.platform.engine.service;

import groovy.lang.Script;
import org.apache.camel.language.groovy.GroovyLanguage;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.spring.SpringCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.converters.FormDataConverter;
import org.qubership.integration.platform.engine.camel.converters.SecurityAccessPolicyConverter;
import org.qubership.integration.platform.engine.camel.history.FilteringMessageHistoryFactory.FilteringEntity;
import org.qubership.integration.platform.engine.cloudcore.maas.MaasService;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.configuration.TracingConfiguration;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import org.qubership.integration.platform.engine.consul.EngineStateReporter;
import org.qubership.integration.platform.engine.events.ExternalLibrariesUpdatedEvent;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingService;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryGroovyShellFactory;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Stopping a context stops every language it resolved, and GroovyLanguage.stop() clears its script cache.
// These tests check that each deployment's context resolves "groovy" to a language of its own.
class IntegrationRuntimeServiceGroovyCacheTest {

    private final List<SpringCamelContext> contexts = new ArrayList<>();
    private GenericApplicationContext applicationContext;
    private IntegrationRuntimeService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        applicationContext = new GenericApplicationContext();
        applicationContext.refresh();

        ExternalLibraryService externalLibraryService = mock(ExternalLibraryService.class);
        ClassLoader classLoader = getClass().getClassLoader();
        when(externalLibraryService.getShellClassLoader()).thenReturn(classLoader);
        when(externalLibraryService.getClassLoaderForSystemModels(any(), any())).thenReturn(classLoader);
        DeploymentReadinessService readinessService = mock(DeploymentReadinessService.class);
        when(readinessService.isInitialized()).thenReturn(true);
        ObjectFactory<CamelDebugger> debuggerFactory = mock(ObjectFactory.class);
        when(debuggerFactory.getObject()).thenAnswer(invocation -> mock(CamelDebugger.class));

        service = new IntegrationRuntimeService(
                mock(ServerConfiguration.class),
                mock(QuartzSchedulerService.class),
                mock(TracingConfiguration.class),
                new ExternalLibraryGroovyShellFactory(externalLibraryService),
                mock(MetricsStore.class),
                externalLibraryService,
                mock(MaasService.class),
                Optional.empty(),
                mock(VariablesService.class),
                mock(EngineStateReporter.class),
                mock(Executor.class),
                mock(CamelDebuggerPropertiesService.class),
                0,
                (Predicate<FilteringEntity>) mock(Predicate.class),
                readinessService,
                mock(DeploymentProcessingService.class),
                mock(FormDataConverter.class),
                mock(SecurityAccessPolicyConverter.class),
                debuggerFactory,
                (ObjectFactory<MicrometerObservationTracer>) mock(ObjectFactory.class));
        service.setApplicationContext(applicationContext);
    }

    @AfterEach
    void tearDown() {
        contexts.forEach(SpringCamelContext::stop);
        applicationContext.close();
    }

    @Test
    void stoppingAnotherDeploymentKeepsTheCompiledScript() throws Exception {
        SpringCamelContext a = buildAndStart("a", "'a'");
        SpringCamelContext b = buildAndStart("b", "'b'");
        Class<Script> compiled = cachedScript(a, "'a'");
        assertNotNull(compiled);
        assertNotSame(a.resolveLanguage("groovy"), b.resolveLanguage("groovy"));

        b.stop();

        assertSame(compiled, cachedScript(a, "'a'"));
    }

    @Test
    void redeployKeepsTheScriptItPrecompiled() throws Exception {
        SpringCamelContext superseded = buildAndStart("a1", "'a'");
        SpringCamelContext current = buildAndStart("a2", "'a'");
        Class<Script> compiled = cachedScript(current, "'a'");
        assertNotNull(compiled);

        superseded.stop();

        assertSame(compiled, cachedScript(current, "'a'"));
    }

    @Test
    void libraryUpdateResetsTheScriptsOfEveryContext() throws Exception {
        SpringCamelContext a = buildAndStart("a", "'a'");
        SpringCamelContext b = buildAndStart("b", "'b'");
        service.getCache().getContexts().put("a", a);
        service.getCache().getContexts().put("b", b);

        service.resetGroovyScriptCaches(new ExternalLibrariesUpdatedEvent(this, true));
        assertNotNull(cachedScript(a, "'a'"));

        service.resetGroovyScriptCaches(new ExternalLibrariesUpdatedEvent(this, false));
        assertNull(cachedScript(a, "'a'"));
        assertNull(cachedScript(b, "'b'"));
    }

    @Test
    void libraryUpdateSkipsAStoppedContextStillInTheCache() throws Exception {
        SpringCamelContext superseded = buildAndStart("a1", "'a'");
        SpringCamelContext current = buildAndStart("a2", "'a'");
        service.getCache().getContexts().put("a1", superseded);
        service.getCache().getContexts().put("a2", current);
        superseded.stop();

        service.resetGroovyScriptCaches(new ExternalLibrariesUpdatedEvent(this, false));

        assertNull(cachedScript(current, "'a'"));
    }

    private SpringCamelContext buildAndStart(String deploymentId, String script) throws Exception {
        Method buildContext = IntegrationRuntimeService.class.getDeclaredMethod("buildContext",
                DeploymentInfo.class, DeploymentConfiguration.class, String.class);
        buildContext.setAccessible(true);
        String xml = """
                <routes xmlns="http://camel.apache.org/schema/spring">
                  <route id="%1$s"><from uri="direct:%1$s"/><script><groovy>%2$s</groovy></script></route>
                </routes>""".formatted(deploymentId, script);
        SpringCamelContext context = (SpringCamelContext) buildContext.invoke(service,
                DeploymentInfo.builder().deploymentId(deploymentId).chainId(deploymentId).build(),
                DeploymentConfiguration.builder().xml(xml).properties(List.of()).build(),
                xml);
        contexts.add(context);
        context.start();
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Class<Script> cachedScript(SpringCamelContext context, String script) throws Exception {
        Method getScriptFromCache = GroovyLanguage.class.getDeclaredMethod("getScriptFromCache", String.class);
        getScriptFromCache.setAccessible(true);
        return (Class<Script>) getScriptFromCache.invoke(context.resolveLanguage("groovy"), script);
    }
}
