package org.qubership.integration.platform.engine.service;

import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.spring.SpringCamelContext;
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
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingService;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryGroovyShellFactory;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import org.qubership.integration.platform.engine.service.externallibrary.GroovyLanguageWithResettableCache;
import org.springframework.beans.factory.ObjectFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationRuntimeServiceTest {

    private DeploymentProcessingService deploymentProcessingService;
    private QuartzSchedulerService quartzSchedulerService;
    private IntegrationRuntimeService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        deploymentProcessingService = mock(DeploymentProcessingService.class);
        quartzSchedulerService = mock(QuartzSchedulerService.class);
        service = new IntegrationRuntimeService(
                mock(ServerConfiguration.class),
                quartzSchedulerService,
                mock(TracingConfiguration.class),
                mock(ExternalLibraryGroovyShellFactory.class),
                mock(GroovyLanguageWithResettableCache.class),
                mock(MetricsStore.class),
                mock(ExternalLibraryService.class),
                mock(MaasService.class),
                Optional.empty(),
                mock(VariablesService.class),
                mock(EngineStateReporter.class),
                mock(Executor.class),
                mock(CamelDebuggerPropertiesService.class),
                0,
                (Predicate<FilteringEntity>) mock(Predicate.class),
                mock(DeploymentReadinessService.class),
                deploymentProcessingService,
                mock(FormDataConverter.class),
                mock(SecurityAccessPolicyConverter.class),
                (ObjectFactory<CamelDebugger>) mock(ObjectFactory.class),
                (ObjectFactory<MicrometerObservationTracer>) mock(ObjectFactory.class));
    }

    @Test
    void stopsTheContextEvenWhenAStopActionThrowsAndRethrowsAfterward() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        when(context.isRunning()).thenReturn(true);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        RuntimeException failure = new RuntimeException("route removal failed");
        doThrow(failure).when(deploymentProcessingService).processStopContext(context, deploymentInfo, null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.stopDeploymentContext(context, deploymentInfo));

        assertSame(failure, thrown);
        verify(quartzSchedulerService).removeSchedulerJobsFromContexts(List.of(context));
        verify(context).stop();
    }

    @Test
    void stopsARunningContextWhenStopActionsSucceed() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        when(context.isRunning()).thenReturn(true);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();

        assertDoesNotThrow(() -> service.stopDeploymentContext(context, deploymentInfo));

        verify(quartzSchedulerService).removeSchedulerJobsFromContexts(List.of(context));
        verify(context).stop();
    }

    @Test
    void doesNotCallStopOnAnAlreadyStoppedContext() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        when(context.isRunning()).thenReturn(false);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();

        assertDoesNotThrow(() -> service.stopDeploymentContext(context, deploymentInfo));

        verify(context, never()).stop();
    }

    @Test
    void doesNothingWithANullContextWhenStopActionsSucceed() {
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();

        assertDoesNotThrow(() -> service.stopDeploymentContext(null, deploymentInfo));

        verify(quartzSchedulerService, never()).removeSchedulerJobsFromContexts(any());
    }

    @Test
    void rethrowsWithoutTouchingSchedulerOrContextWhenContextIsNull() {
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        RuntimeException failure = new RuntimeException("route removal failed");
        doThrow(failure).when(deploymentProcessingService).processStopContext(null, deploymentInfo, null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.stopDeploymentContext(null, deploymentInfo));

        assertSame(failure, thrown);
        verify(quartzSchedulerService, never()).removeSchedulerJobsFromContexts(any());
    }

    @Test
    void attachesStopFailureAsSuppressedAndDoesNotReplaceTheOriginalStartFailure() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        DeploymentConfiguration configuration = DeploymentConfiguration.builder().build();
        Exception startFailure = new RuntimeException("bad xml");
        RuntimeException stopFailure = new RuntimeException("route removal failed");
        doThrow(stopFailure).when(deploymentProcessingService)
                .processStopContext(context, deploymentInfo, configuration);

        service.attachStopFailureToStartFailure(startFailure, context, deploymentInfo, configuration);

        assertEquals(1, startFailure.getSuppressed().length);
        assertSame(stopFailure, startFailure.getSuppressed()[0]);
        verify(quartzSchedulerService).commitScheduledJobs();
    }

    @Test
    void doesNotAddASuppressedExceptionWhenStopActionsSucceed() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        DeploymentConfiguration configuration = DeploymentConfiguration.builder().build();
        Exception startFailure = new RuntimeException("bad xml");

        service.attachStopFailureToStartFailure(startFailure, context, deploymentInfo, configuration);

        assertEquals(0, startFailure.getSuppressed().length);
        verify(quartzSchedulerService).commitScheduledJobs();
        verify(deploymentProcessingService).processStopContext(context, deploymentInfo, configuration);
    }
}
