package org.qubership.integration.platform.engine.routes.tests;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkiverse.mcp.server.runtime.DefaultSchemaGenerator;
import io.quarkiverse.mcp.server.runtime.NotificationManagerImpl;
import io.quarkiverse.mcp.server.runtime.PromptCompletionManagerImpl;
import io.quarkiverse.mcp.server.runtime.PromptManagerImpl;
import io.quarkiverse.mcp.server.runtime.ResourceManagerImpl;
import io.quarkiverse.mcp.server.runtime.ResourceTemplateCompletionManagerImpl;
import io.quarkiverse.mcp.server.runtime.ResourceTemplateManagerImpl;
import io.quarkiverse.mcp.server.runtime.ResponseHandlers;
import io.quarkiverse.mcp.server.runtime.ToolManagerImpl;
import io.quarkus.test.component.QuarkusComponentTestExtension;
import io.quarkus.test.component.QuarkusComponentTestExtensionBuilder;
import io.quarkus.test.component.SkipInject;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.quarkus.core.RuntimeRegistry;
import org.apache.camel.spi.Resource;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.camel.components.servlet.CustomHttpRestServletResolveConsumerStrategy;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomFilterStrategy;
import org.qubership.integration.platform.engine.camel.components.servlet.binding.HandlingHttpBinding;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.ChainGlobalExceptionHandler;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.camel.context.propagation.ContextPropsProvider;
import org.qubership.integration.platform.engine.camel.dsl.CustomXmlRoutesBuilderLoader;
import org.qubership.integration.platform.engine.camel.dsl.errorhandling.ErrorHandlerFactory;
import org.qubership.integration.platform.engine.camel.dsl.notification.SourceProcessingNotifier;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.ResourceContentPreprocessingService;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors.RouteVariablesResolverPreprocessor;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors.VariablesInjectorPreprocessor;
import org.qubership.integration.platform.engine.camel.listeners.RouteEventListener;
import org.qubership.integration.platform.engine.camel.listeners.actions.routes.added.McpToolRegistrar;
import org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed.McpToolUnregisterAction;
import org.qubership.integration.platform.engine.camel.listeners.actions.routes.started.CommitSchedulerJobsAction;
import org.qubership.integration.platform.engine.camel.listeners.actions.routes.stopped.ResetSchedulersProxyOnStopAction;
import org.qubership.integration.platform.engine.camel.processors.ChainExceptionResponseHandlerProcessor;
import org.qubership.integration.platform.engine.camel.processors.HttpTriggerFinishProcessor;
import org.qubership.integration.platform.engine.camel.processors.HttpTriggerProcessor;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.KafkaSenderProcessor;
import org.qubership.integration.platform.engine.camel.processors.NoopProcessor;
import org.qubership.integration.platform.engine.camel.processors.RabbitMqSenderProcessor;
import org.qubership.integration.platform.engine.camel.processors.RabbitMqTriggerProcessor;
import org.qubership.integration.platform.engine.camel.processors.SchedulerProcessor;
import org.qubership.integration.platform.engine.camel.processors.SftpTriggerProcessor;
import org.qubership.integration.platform.engine.camel.processors.context.propagation.ContextPropagationProcessor;
import org.qubership.integration.platform.engine.camel.processors.context.propagation.ContextRestoreProcessor;
import org.qubership.integration.platform.engine.camel.processors.context.propagation.MessagingXHeadersPropagationProcessorProxy;
import org.qubership.integration.platform.engine.camel.processors.context.propagation.MessagingXHeadersPropagationRestoreProcessorProxy;
import org.qubership.integration.platform.engine.camel.processors.session.ChainFinishProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.configuration.MapperConfiguration;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.engine.maas.MaasService;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.routes.entrypoint.CatalogSnapshotExecutionEntryPoint;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotDeployment;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionPlan;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;
import org.qubership.integration.platform.engine.routes.fixture.McpTriggerSnapshotDependencies;
import org.qubership.integration.platform.engine.routes.fixture.SchedulerSnapshotDependencies;
import org.qubership.integration.platform.engine.routes.fixture.SchedulerSnapshotRequestProbe;
import org.qubership.integration.platform.engine.routes.runtime.SnapshotRuntimeDependencies;
import org.qubership.integration.platform.engine.service.BlueGreenSchedulerControllerService;
import org.qubership.integration.platform.engine.service.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.debugger.util.ChainExceptionResponseHandlerService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(SnapshotScenarioReportExtension.class)
class MicroEngineSnapshotRouteExecutionTest extends SnapshotRouteExecutionTest {
    @RegisterExtension
    static final QuarkusComponentTestExtension COMPONENT_TEST = new QuarkusComponentTestExtensionBuilder()
            .addComponentClasses(
                    SnapshotRuntimeDependencies.class,
                    McpTriggerSnapshotDependencies.class,
                    SchedulerSnapshotDependencies.class,
                    SchedulerSnapshotRequestProbe.class,
                    BlueGreenSchedulerControllerService.class,
                    CommitSchedulerJobsAction.class,
                    ResetSchedulersProxyOnStopAction.class,
                    ToolManagerImpl.class,
                    ResponseHandlers.class,
                    ConnectionManager.class,
                    DefaultSchemaGenerator.class,
                    PromptManagerImpl.class,
                    ResourceManagerImpl.class,
                    ResourceTemplateManagerImpl.class,
                    PromptCompletionManagerImpl.class,
                    ResourceTemplateCompletionManagerImpl.class,
                    NotificationManagerImpl.class,
                    StreamableHttpMcpMessageHandler.class,
                    RouteEventListener.class,
                    McpToolRegistrar.class,
                    McpToolUnregisterAction.class,
                    NoopProcessor.class,
                    MetricTagsHelper.class,
                    MetricsStore.class,
                    ApplicationConfiguration.class,
                    MapperConfiguration.class,
                    VariablesService.class,
                    MaasService.class,
                    ResourceContentPreprocessingService.class,
                    VariablesInjectorPreprocessor.class,
                    RouteVariablesResolverPreprocessor.class,
                    SourceProcessingNotifier.class,
                    ErrorHandlerFactory.class,
                    ContextPropsProvider.class,
                    CamelExchangeContextPropagation.class,
                    ContextPropagationProcessor.class,
                    ContextRestoreProcessor.class,
                    HandlingHttpBinding.class,
                    ServletCustomFilterStrategy.class,
                    CustomHttpRestServletResolveConsumerStrategy.class,
                    ChainStartProcessor.class,
                    ChainFinishProcessor.class,
                    HttpTriggerProcessor.class,
                    HttpTriggerFinishProcessor.class,
                    InterruptExchangeProcessor.class,
                    ChainExceptionResponseHandlerProcessor.class,
                    ChainExceptionResponseHandlerService.class,
                    ChainGlobalExceptionHandler.class,
                    CorrelationIdSetter.class,
                    JsonMessageValidator.class,
                    KafkaSenderProcessor.class,
                    RabbitMqSenderProcessor.class,
                    RabbitMqTriggerProcessor.class,
                    SftpTriggerProcessor.class,
                    SchedulerProcessor.class,
                    MessagingXHeadersPropagationProcessorProxy.class,
                    MessagingXHeadersPropagationRestoreProcessorProxy.class,
                    TenantConfiguration.class
            )
            .ignoreNestedClasses()
            .configProperty("application.name", "snapshot-tests")
            .configProperty("application.namespace", "snapshot-tests")
            .configProperty("application.cloud_service_name", "snapshot-tests")
            .configProperty("cloud.microservice.namespace", "snapshot-tests")
            .configProperty("cip.metrics.enabled", "false")
            .configProperty("cip.metrics.http-payload-metrics.enabled", "false")
            .configProperty("cip.metrics.http-payload-metrics.buckets", "128,1024")
            .configProperty("cip.metrics.session-duration.buckets", "100ms,1s")
            .configProperty("cip.metrics.prometheus.init.delay", "30")
            .configProperty("cip.camel.startup.error-handling.ignore-variables-errors", "false")
            .configProperty("cip.camel.startup.error-handling.ignore-route-loading-errors", "false")
            .configProperty("cip.chains.http-client.m2m.fallback-interceptor.enabled", "false")
            .configProperty("kubernetes.variables-secret.label", "cip-variable-type")
            .configProperty("kubernetes.variables-secret.name", "snapshot-test-variables")
            .configProperty("tenant.default.id", "default-tenant")
            .configProperty("quarkus.mcp.server.server-info.name", "snapshot-mcp-server")
            .configProperty("quarkus.mcp.server.server-info.version", "1")
            .configProperty("quarkus.mcp.server.connection-idle-timeout", "0s")
            .build();

    @Inject
    DefaultCamelContext camelContext;

    // Keep the HTTP handler available for the SDK recorder's programmatic lookup.
    @Inject
    Instance<StreamableHttpMcpMessageHandler> mcpMessageHandler;

    @Inject
    Instance<SchedulerSnapshotRequestProbe> schedulerRequestProbe;

    // Keep dependencies resolved by XML bean builders in the component test's CDI container.
    @Inject
    MetricsStore metricsStore;

    @Inject
    CustomHttpRestServletResolveConsumerStrategy servletResolveConsumerStrategy;

    @Inject
    BlueGreenStatePublisher blueGreenStatePublisher;

    @Inject
    MaasService maasService;

    @ParameterizedTest(name = "{0}/{1}", allowZeroInvocations = true)
    @MethodSource("snapshotScenarios")
    void shouldExecuteMicroEngineSnapshot(
            @SkipInject SnapshotExecutionTarget target,
            @SkipInject SnapshotExecutionScenario scenario
    ) throws Exception {
        executeScenario(target, scenario);
    }

    static Stream<Arguments> snapshotScenarios(TestInfo testInfo) throws IOException {
        SnapshotShard shard = testInfo.getTestClass().orElseThrow().getAnnotation(SnapshotShard.class);
        SnapshotExecutionPlan plan = new CatalogSnapshotExecutionEntryPoint().buildExecutionPlan();
        assertFalse(plan.getTargets().isEmpty(), "Snapshot execution plan does not contain any targets.");
        plan.getTargets().forEach(target -> assertFalse(
                target.getScenarios().isEmpty(),
                () -> "Snapshot execution target '" + target.getId() + "' does not contain any scenarios."
        ));
        return SnapshotScenarioSelector.select(
                plan,
                System.getProperty(SnapshotScenarioSelector.TARGET_PROPERTY),
                System.getProperty(SnapshotScenarioSelector.SCENARIO_PROPERTY),
                System.getProperty(SnapshotScenarioSelector.SCOPE_PROPERTY),
                shard == null ? System.getProperty(SnapshotScenarioSelector.SHARD_COUNT_PROPERTY) : Integer.toString(shard.count()),
                shard == null ? System.getProperty(SnapshotScenarioSelector.SHARD_INDEX_PROPERTY) : Integer.toString(shard.index())
        ).stream().map(selection -> Arguments.of(
                Named.of(selection.target().getId(), selection.target()),
                Named.of(selection.scenario().getId(), selection.scenario())
        ));
    }

    @Override
    protected DefaultCamelContext createCamelContext() {
        return camelContext;
    }

    @Override
    protected DefaultCamelContext createCamelContext(SnapshotDeployment deployment) {
        return deployment.isIsolatedContext()
                ? new DefaultCamelContext(new RuntimeRegistry(Map.of())) : createCamelContext();
    }

    @Override
    protected void loadXmlRouteDefinitions(
            DefaultCamelContext context,
            String location
    ) throws Exception {
        Resource resource = ResourceHelper.resolveMandatoryResource(context, location);
        int previousRouteCount = context.getRouteDefinitions().size();
        Set<String> previousMetadataNames = Set.copyOf(
                context.getRegistry().findByTypeWithName(DeploymentInfo.class).keySet()
        );
        CustomXmlRoutesBuilderLoader loader = new CustomXmlRoutesBuilderLoader();
        loader.setCamelContext(context);
        try {
            loader.preParseRoute(resource);
            RoutesBuilder routesBuilder = loader.loadRoutesBuilder(resource);
            routesBuilder.addRoutesToCamelContext(context);
        } finally {
            loader.stop();
        }

        Map<String, DeploymentInfo> deploymentMetadata =
                context.getRegistry().findByTypeWithName(DeploymentInfo.class);
        List<DeploymentInfo> generatedMetadata = deploymentMetadata.entrySet().stream()
                .filter(entry -> !previousMetadataNames.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        assertEquals(
                1,
                generatedMetadata.size(),
                () -> "Micro-engine source '" + location + "' must define one deployment metadata bean."
        );
        DeploymentInfo deployment = generatedMetadata.getFirst();
        assertNotNull(deployment.getSnapshot(), "Generated deployment metadata has no snapshot.");
        String snapshotId = deployment.getSnapshot().getId();
        assertNotNull(snapshotId, "Generated deployment metadata has no snapshot id.");

        List<RouteDefinition> routes = context.getRouteDefinitions();
        for (RouteDefinition route : routes.subList(previousRouteCount, routes.size())) {
            if (route.getGroup() != null) {
                assertEquals(
                        snapshotId,
                        route.getGroup(),
                        () -> "Micro-engine route '" + route.getId() + "' references an unexpected deployment group."
                );
            }
        }
    }
}
