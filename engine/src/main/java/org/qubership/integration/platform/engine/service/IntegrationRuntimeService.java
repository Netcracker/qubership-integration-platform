/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service;

import org.qubership.integration.platform.engine.camel.QipCustomClassResolver;
import org.qubership.integration.platform.engine.camel.CustomResilienceReifier;
import org.qubership.integration.platform.engine.camel.context.propagation.constant.BusinessIds;
import org.qubership.integration.platform.engine.camel.converters.SecurityAccessPolicyConverter;
import org.qubership.integration.platform.engine.camel.converters.FormDataConverter;
import org.qubership.integration.platform.engine.camel.history.FilteringMessageHistoryFactory;
import org.qubership.integration.platform.engine.camel.history.FilteringMessageHistoryFactory.FilteringEntity;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.configuration.PredeployCheckKafkaConfiguration;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.configuration.TracingConfiguration;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import org.qubership.integration.platform.engine.consul.EngineStateReporter;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.events.ConsulSessionCreatedEvent;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecureThreadFactory;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecurityBean;
import org.qubership.integration.platform.engine.jms.weblogic.WeblogicSecurityInterceptStrategy;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.RuntimeIntegrationCache;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.ConnectionSourceType;
import org.qubership.integration.platform.engine.model.constants.EnvironmentSourceType;
import org.qubership.integration.platform.engine.model.deployment.DeploymentOperation;
import org.qubership.integration.platform.engine.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryGroovyShellFactory;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import org.qubership.integration.platform.engine.service.externallibrary.GroovyLanguageWithResettableCache;
import org.qubership.integration.platform.engine.service.xmlpreprocessor.XmlConfigurationPreProcessor;
import org.qubership.integration.platform.engine.util.MDCUtil;
import org.qubership.integration.platform.engine.util.SimpleHttpUriUtils;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import jakarta.jms.ConnectionFactory;
import org.apache.camel.component.jackson.JacksonConstants;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsConfiguration;
import org.apache.camel.impl.engine.DefaultManagementStrategy;
import org.apache.camel.impl.engine.DefaultStreamCachingStrategy;
import org.apache.camel.model.*;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.reifier.ProcessorReifier;
import org.apache.camel.spi.ClassResolver;
import org.apache.camel.spi.MessageHistoryFactory;
import org.apache.camel.spi.ThreadPoolProfile;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.tracing.Tracer;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.codehaus.groovy.control.CompilationFailedException;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentsUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.EventListener;
import org.springframework.jms.support.destination.JndiDestinationResolver;
import org.springframework.jndi.JndiObjectFactoryBean;
import org.springframework.jndi.JndiTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.camel.xml.jaxb.JaxbHelper.loadRoutesDefinition;

@Service
public class IntegrationRuntimeService implements ApplicationContextAware {
    private static final ExtendedErrorLogger log = ExtendedErrorLoggerFactory.getLogger(IntegrationRuntimeService.class);

    private final ServerConfiguration serverConfiguration;
    private final QuartzSchedulerService quartzSchedulerService;
    private final TracingConfiguration tracingConfiguration;
    private final PredeployCheckKafkaConfiguration predeployCheckKafkaConfiguration;
    private final ExternalLibraryGroovyShellFactory groovyShellFactory;
    private final GroovyLanguageWithResettableCache groovyLanguage;
    private final CamelComponentDependencyBinder dependencyBinder;
    private final MetricsStore metricsStore;
    private final Optional<ExternalLibraryService> externalLibraryService;
    private final Optional<MaasService> maasService;
    private final Optional<ControlPlaneService> controlPlaneService;
    private final Optional<XmlConfigurationPreProcessor> xmlPreProcessor;
    private final VariablesService variablesService;
    private final EngineStateReporter engineStateReporter;
    private final CamelDebuggerPropertiesService propertiesService;
    private final DeploymentReadinessService deploymentReadinessService;
    private final ApplicationAutoConfiguration applicationConfiguration;

    private final Predicate<FilteringEntity> camelMessageHistoryFilter;

    private final RuntimeIntegrationCache deploymentCache = new RuntimeIntegrationCache();
    private final ReadWriteLock processLock = new ReentrantReadWriteLock();

    private final DataSource qrtzDataSource;

    private final Executor deploymentExecutor;

    private final ObjectProvider<WeblogicSecurityBean> wlSecurityBeanProvider;
    private final ObjectProvider<WeblogicSecurityInterceptStrategy> wlSecurityInterceptStrategyProvider;
    private final ObjectProvider<WeblogicSecureThreadFactory> wlSecureThreadFactoryProvider;

    private ApplicationContext applicationContext;
    private final Optional<SdsService> sdsService;

    static {
        ProcessorReifier.registerReifier(StepDefinition.class, CustomStepReifier::new);
        ProcessorReifier.registerReifier(CircuitBreakerDefinition.class,
            (route, definition) -> new CustomResilienceReifier(route,
                (CircuitBreakerDefinition) definition));
    }


    @Value("${qip.camel.stream-caching.enabled}")
    private boolean enableStreamCaching;
    @Value("${qip.camel.component.rabbitmq.predeploy-check-enabled}")
    private boolean amqpPredeployCheckEnabled;

    private final int streamCachingBufferSize;

    @Autowired
    public IntegrationRuntimeService(ServerConfiguration serverConfiguration,
        QuartzSchedulerService quartzSchedulerService,
        TracingConfiguration tracingConfiguration,
        PredeployCheckKafkaConfiguration predeployCheckKafkaConfiguration,
        ExternalLibraryGroovyShellFactory groovyShellFactory,
        GroovyLanguageWithResettableCache groovyLanguage,
        MetricsStore metricsStore,
        CamelComponentDependencyBinder dependencyBinder,
        Optional<ExternalLibraryService> externalLibraryService,
        Optional<MaasService> maasService, Optional<ControlPlaneService> controlPlaneService,
        Optional<XmlConfigurationPreProcessor> xmlPreProcessor,
        VariablesService variablesService,
        EngineStateReporter engineStateReporter,
        @Qualifier("deploymentExecutor") Executor deploymentExecutor,
        CamelDebuggerPropertiesService propertiesService,
        @Value("${qip.camel.stream-caching.buffer.size-kb}") int streamCachingBufferSizeKb,
        Predicate<FilteringEntity> camelMessageHistoryFilter,
        @Qualifier("qrtzDataSource") DataSource qrtzDataSource,
        Optional<SdsService> sdsService,
        DeploymentReadinessService deploymentReadinessService,
        ApplicationAutoConfiguration applicationConfiguration,
        ObjectProvider<WeblogicSecurityBean> wlSecurityBeanProvider,
        ObjectProvider<WeblogicSecurityInterceptStrategy> wlSecurityInterceptStrategyProvider,
        ObjectProvider<WeblogicSecureThreadFactory> wlSecureThreadFactoryProvider
    ) {
        this.serverConfiguration = serverConfiguration;
        this.quartzSchedulerService = quartzSchedulerService;
        this.tracingConfiguration = tracingConfiguration;
        this.predeployCheckKafkaConfiguration = predeployCheckKafkaConfiguration;
        this.groovyShellFactory = groovyShellFactory;
        this.groovyLanguage = groovyLanguage;
        this.metricsStore = metricsStore;
        this.dependencyBinder = dependencyBinder;
        this.externalLibraryService = externalLibraryService;
        this.maasService = maasService;
        this.controlPlaneService = controlPlaneService;
        this.xmlPreProcessor = xmlPreProcessor;
        this.variablesService = variablesService;
        this.engineStateReporter = engineStateReporter;
        this.deploymentExecutor = deploymentExecutor;
        this.propertiesService = propertiesService;

        this.streamCachingBufferSize = streamCachingBufferSizeKb * 1024;

        this.camelMessageHistoryFilter = camelMessageHistoryFilter;

        this.qrtzDataSource = qrtzDataSource;
        this.sdsService = sdsService;
        this.deploymentReadinessService = deploymentReadinessService;
        this.applicationConfiguration = applicationConfiguration;
        this.wlSecurityBeanProvider = wlSecurityBeanProvider;
        this.wlSecurityInterceptStrategyProvider = wlSecurityInterceptStrategyProvider;
        this.wlSecureThreadFactoryProvider = wlSecureThreadFactoryProvider;
    }

    @Override
    public void setApplicationContext(@NotNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Async
    @EventListener
    public void onExternalLibrariesUpdated(ConsulSessionCreatedEvent event) {
        // if consul session (re)create - force update engine state
        updateEngineState();
    }

    // requires completion of all deployment processes
    public List<DeploymentInfo> buildExcludeDeploymentsMap() {
        Lock processLock = this.processLock.writeLock();
        try {
            processLock.lock();
            return deploymentCache.getDeployments().values().stream()
                .map(EngineDeployment::getDeploymentInfo)
                .toList();
        } finally {
            processLock.unlock();
        }
    }

    // requires completion of all deployment processes
    private Map<String, EngineDeployment> buildActualDeploymentsSnapshot() {
        Lock processLock = this.processLock.writeLock();
        try {
            processLock.lock();
            // copy deployments objects to avoid concurrent modification
            return deploymentCache.getDeployments().entrySet().stream()
                .collect(Collectors.toMap(
                    Entry::getKey, entry -> entry.getValue().toBuilder().build()));
        } finally {
            processLock.unlock();
        }
    }

    /**
     * Start parallel deployments processing, wait for completion and update engine state
     */
    public void processAndUpdateState(DeploymentsUpdate update, boolean retry)
        throws ExecutionException, InterruptedException {
        List<CompletableFuture<?>> completableFutures = new ArrayList<>();

        // <chainId, OrderedCollection<DeploymentUpdate>>
        Map<String, TreeSet<DeploymentUpdate>> updatesPerChain = new HashMap<>();
        for (DeploymentUpdate deploymentUpdate : update.getUpdate()) {
            // deployments with same chainId must be ordered
            TreeSet<DeploymentUpdate> chainDeployments = updatesPerChain.computeIfAbsent(
                deploymentUpdate.getDeploymentInfo().getChainId(),
                k -> new TreeSet<>(
                    Comparator.comparingLong(d -> d.getDeploymentInfo().getCreatedWhen())));

            chainDeployments.add(deploymentUpdate);
        }

        for (Entry<String, TreeSet<DeploymentUpdate>> entry : updatesPerChain.entrySet()) {
            TreeSet<DeploymentUpdate> chainDeployments = entry.getValue();
            completableFutures.add(process(chainDeployments, DeploymentOperation.UPDATE, retry));
        }

        for (DeploymentUpdate toStop : update.getStop()) {
            completableFutures.add(
                process(Collections.singletonList(toStop), DeploymentOperation.STOP, retry));
        }

        // wait for all async tasks
        CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0])).get();

        // update engine state in consul
        updateEngineState();
    }

    private synchronized void updateEngineState() {
        engineStateReporter.addStateToQueue(EngineState.builder()
            .engine(serverConfiguration.getEngineInfo())
            .deployments(buildActualDeploymentsSnapshot())
            .build());
    }

    /**
     * @param chainDeployments - an ordered collection of deployments related to the same chain
     * @param operation        - operation type
     */
    private CompletableFuture<?> process(Collection<DeploymentUpdate> chainDeployments,
        DeploymentOperation operation, boolean retry) {
        return CompletableFuture.runAsync(() -> {
            MDCUtil.setRequestId(UUID.randomUUID().toString());
            for (DeploymentUpdate chainDeployment : chainDeployments) {
                log.info("Start processing deployment {}, operation: {}",
                    chainDeployment.getDeploymentInfo(), operation);

                Lock chainLock = getCache().getLockForChain(
                    chainDeployment.getDeploymentInfo().getChainId());
                try {
                    chainLock.lock();
                    log.debug("Locked by-chain lock");

                    // update and retry concurrent case: check if retry deployment is still required
                    // necessary so as not to break the order of deployments
                    if (!retry || getCache().getDeployments()
                        .containsKey(chainDeployment.getDeploymentInfo().getDeploymentId())) {
                        Lock processWeakLock = processLock.readLock();
                        try {
                            processWeakLock.lock();
                            log.debug("Locked process read lock");
                            processDeploymentUpdate(chainDeployment, operation);
                        } finally {
                            processWeakLock.unlock();
                            log.debug("Unlocked process read lock");
                        }
                    }
                } finally {
                    chainLock.unlock();
                    log.debug("Unlocked by-chain lock");
                }
                log.info("Deployment {} processing completed",
                    chainDeployment.getDeploymentInfo().getDeploymentId());
            }
        }, deploymentExecutor);
    }

    private void processDeploymentUpdate(DeploymentUpdate deployment,
                                         DeploymentOperation operation) {
        String chainId = deployment.getDeploymentInfo().getChainId();
        String snapshotId = deployment.getDeploymentInfo().getSnapshotId();
        String deploymentId = deployment.getDeploymentInfo().getDeploymentId();
        Throwable exception = null;
        ErrorCode chainErrorCode = null;
        DeploymentStatus status = DeploymentStatus.FAILED;

        try {
            MDCUtil.setBusinessIds(Map.of(
                BusinessIds.CHAIN_ID, chainId,
                BusinessIds.DEPLOYMENT_ID, deploymentId,
                BusinessIds.SNAPSHOT_ID, snapshotId));

            log.info("Processing deployment {}: {} for chain {}", deploymentId, deployment.getDeploymentInfo(), chainId);

            if (operation != DeploymentOperation.STOP) {
                prepareAndRegisterRoutesInControlPlane(deployment);
            }

            status = processDeployment(deployment, operation);
        } catch (KubeApiException e) {
            exception = e;
        } catch (DeploymentRetriableException e) {
            status = DeploymentStatus.PROCESSING;
            putInRetryQueue(deployment);
            exception = e;
            chainErrorCode = ErrorCode.PREDEPLOY_CHECK_ERROR;
        } catch (Throwable e) {
            exception = e;
            chainErrorCode = ErrorCode.UNEXPECTED_DEPLOYMENT_ERROR;
            log.error(chainErrorCode, chainErrorCode.compileMessage(deploymentId), e);
        } finally {
            try {
                log.info("Status of deployment {} for chain {} is {}", deploymentId, chainId, status);
                quartzSchedulerService.resetSchedulersProxy();
                switch (status) {
                    case DEPLOYED, FAILED, PROCESSING -> {
                        if (chainErrorCode != null) {
                            deployment.getDeploymentInfo().setChainStatusCode(chainErrorCode.getCode());
                        }
                        var stateBuilder = EngineDeployment.builder()
                                .deploymentInfo(deployment.getDeploymentInfo())
                                .status(status);

                        if (isNull(exception)) {
                            removeOldDeployments(deployment, deploymentStatus -> true);
                        } else {
                            stateBuilder.errorMessage(exception.getMessage());

                            log.error(chainErrorCode, "Failed to deploy chain {} with id {}. Deployment: {}",
                                    deployment.getDeploymentInfo().getChainName(), chainId, deploymentId, exception);

                            removeOldDeployments(
                                    deployment,
                                    (deploymentStatus) ->
                                            deploymentStatus == DeploymentStatus.FAILED ||
                                                    deploymentStatus == DeploymentStatus.PROCESSING);
                        }

                        // If Pod is not initialized yet and this is first deploy -
                        // set corresponding flag and Processing status
                        if (status == DeploymentStatus.DEPLOYED && isDeploymentsSuspended()) {
                            stateBuilder.suspended(true);
                        }

                        EngineDeployment deploymentState = stateBuilder.build();
                        getCache().getDeployments().put(deploymentId, deploymentState);
                    }
                    case REMOVED -> {
                        getCache().getDeployments().remove(deploymentId);
                        removeRetryingDeployment(deploymentId);
                        propertiesService.removeDeployProperties(deploymentId);
                        metricsStore.removeChainsDeployments(deploymentId);
                    }
                    default -> {
                    }
                }
            } finally {
                MDCUtil.clear();
            }
        }
    }

    private boolean isDeploymentsSuspended() {
        return !deploymentReadinessService.isInitialized();
    }

    private String getCPRouteHash(DeploymentRouteUpdate route) {
        if (route.getPath() == null) {
            return null;
        }

        // Add all parameters that will be sent to control-plane
        String strToHash = StringUtils.joinWith(",",
                route.getPath(),
                route.getConnectTimeout()
        );

        return DigestUtils.sha1Hex(strToHash);
    }

    private void resolveVariablesInRoutes(DeploymentUpdate deploymentUpdate) {
        deploymentUpdate.getConfiguration().getRoutes().stream()
            .filter(route -> nonNull(route.getVariableName())
                && (RouteType.EXTERNAL_SENDER == route.getType()
                || RouteType.EXTERNAL_SERVICE == route.getType()))
            .filter(route -> variablesService.hasVariableReferences(route.getPath()))
            .forEach(route -> route.setPath(variablesService.injectVariables(route.getPath())));
    }

    private void prepareAndRegisterRoutesInControlPlane(DeploymentUpdate deployment) {
        if (controlPlaneService.isPresent()) {
            resolveVariablesInRoutes(deployment);

            // external triggers routes
            List<DeploymentRouteUpdate> gatewayTriggersRoutes = deployment.getConfiguration()
                .getRoutes().stream()
                .filter(route -> RouteType.triggerRouteWithGateway(route.getType()))
                .peek(externalRoute ->
                    externalRoute.setPath("/" + StringUtils.strip(externalRoute.getPath(), "/")))
                .toList();

            try {
                controlPlaneService.get().postPublicEngineRoutes(
                    gatewayTriggersRoutes.stream()
                        .filter(route -> RouteType.isPublicTriggerRoute(route.getType())).toList(),
                    applicationConfiguration.getDeploymentName());
                controlPlaneService.get().postPrivateEngineRoutes(
                    gatewayTriggersRoutes.stream()
                        .filter(route -> RouteType.isPrivateTriggerRoute(route.getType())).toList(),
                    applicationConfiguration.getDeploymentName());

                // cleanup triggers routes if necessary (for internal triggers)
                controlPlaneService.get().removeEngineRoutesByPathsAndEndpoint(
                    deployment.getConfiguration().getRoutes().stream()
                        .filter(route -> RouteType.triggerRouteCleanupNeeded(route.getType()))
                        .map(route -> Pair.of(route.getPath(), route.getType()))
                        .toList(),
                    applicationConfiguration.getDeploymentName());

                // Register http based senders and service call paths '/{senderType}/{elementId}', '/system/{elementId}'
                deployment.getConfiguration().getRoutes().stream()
                    .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                        || route.getType() == RouteType.EXTERNAL_SERVICE)
                    .forEach(route -> controlPlaneService.get().postEgressGatewayRoutes(formatServiceRoutes(route)));
            } catch (ControlPlaneException e) {
                throw new DeploymentRetriableException(e);
            }
        }
    }

    private @NotNull DeploymentRouteUpdate formatServiceRoutes(DeploymentRouteUpdate route) {
        DeploymentRouteUpdate routeUpdate = route;

        // add hash to route
        if (nonNull(routeUpdate.getVariableName()) && RouteType.EXTERNAL_SERVICE == routeUpdate.getType()) {
            routeUpdate = routeUpdate.toBuilder().build();
            // Formatting URI (add protocol if needed)
            try {
                routeUpdate.setPath(SimpleHttpUriUtils.formatUri(routeUpdate.getPath()));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            // Adding hash to gateway prefix
            String pathHash = getCPRouteHash(routeUpdate);
            if (!StringUtils.isBlank(pathHash)) {
                routeUpdate.setGatewayPrefix(routeUpdate.getGatewayPrefix() + '/' + pathHash);
            }
        }
        return routeUpdate;
    }

    private void checkKafkaTopicsAndConnection(DeploymentUpdate deployment)
        throws UnknownTopicOrPartitionException {
        for (ElementProperties elementProperties : deployment.getConfiguration().getProperties()) {
            ChainElementType chainElementType = ChainElementType.fromString(
                elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE));
            String elementId = elementProperties.getProperties().get(ChainProperties.ELEMENT_ID);
            try {
                MDC.put(ChainProperties.ELEMENT_ID, elementId);
                Map<String, String> props = elementProperties.getProperties();

                if (ChainElementType.isKafkaAsyncElement(chainElementType)) {

                    String brokers = variablesService.injectVariables(
                        props.get(ElementOptions.BROKERS));
                    String securityProtocol = variablesService.injectVariables(
                        props.get(ElementOptions.SECURITY_PROTOCOL));
                    String saslMechanism = variablesService.injectVariables(
                        props.get(ElementOptions.SASL_MECHANISM));
                    String saslJaasConfig = variablesService.injectVariables(
                        props.get(ElementOptions.SASL_JAAS_CONFIG));
                    String topicsString = variablesService.injectVariables(
                        props.get(ElementOptions.TOPICS));

                    if (brokers == null) {
                        log.debug(
                            "Element with id {} not contains kafka connection params, skipping",
                            elementProperties.getElementId());
                        continue;
                    }

                    Map<String, Object> validationKafkaAdminConfig =
                        predeployCheckKafkaConfiguration.createValidationKafkaAdminConfig(brokers,
                            securityProtocol, saslMechanism, saslJaasConfig);

                    Set<String> topics;
                    try (AdminClient client = AdminClient.create(validationKafkaAdminConfig)) {
                        Set<String> kafkaTopics = client.listTopics().names().get();
                        String[] topicsArray = topicsString.split(",");
                        topics = new HashSet<>();
                        if (topicsArray.length == 0) {
                            throw new KafkaException("Topic property can't be empty");
                        }
                        topics.add(topicsArray[0]); // take only first topic from string
                        topics.removeAll(kafkaTopics);
                    }

                    if (!topics.isEmpty()) {
                        String topicString = String.join(", ", topics);
                        throw new DeploymentRetriableException(
                            "Kafka topics (" + topicString
                                + ") not found, check if this topics exists in kafka");
                    }
                }
            } catch (ExecutionException | KafkaException e) {
                // skip check if permissions denied
                if (e instanceof AuthorizationException
                    || e.getCause() instanceof AuthorizationException) {
                    log.warn(
                        "Kafka predeploy check is failed with AuthorizationException. Exception not thrown",
                        e);
                } else {
                    log.warn("Kafka predeploy check is failed. " +
                            "Connection configuration is invalid, topics not found or broker is unavailable",
                        e);
                    throw new DeploymentRetriableException(
                        "Kafka predeploy check is failed. " +
                            "Connection configuration is invalid, topics not found or broker is unavailable",
                        e);
                }
            } catch (DeploymentRetriableException e) {
                log.warn("Kafka predeploy check is failed with retriable exception", e);
                throw e;
            } catch (Exception e) {
                log.warn(
                    "Failed to check kafka topic(s) or connection for deployment: {}, element: {}",
                    deployment.getDeploymentInfo().getDeploymentId(),
                    elementProperties.getElementId(),
                    e);
            } finally {
                MDC.remove(ChainProperties.ELEMENT_ID);
            }
        }
    }

    private void checkAmqpConnection(DeploymentUpdate deployment) {
        for (ElementProperties elementProperties : deployment.getConfiguration().getProperties()) {
            ChainElementType chainElementType = ChainElementType.fromString(
                elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE));
            String elementId = elementProperties.getProperties().get(ChainProperties.ELEMENT_ID);
            try {

                MDC.put(ChainProperties.ELEMENT_ID, elementId);
                Map<String, String> props = elementProperties.getProperties();

                if (ChainElementType.isAmqpAsyncElement(chainElementType) &&
                        (ConnectionSourceType.MAAS.toString().equalsIgnoreCase(
                        props.get(
                            ElementOptions.CONNECTION_SOURCE_TYPE_PROP)) || EnvironmentSourceType.MAAS_BY_CLASSIFIER.toString().equalsIgnoreCase(
                                props.get(ElementOptions.CONNECTION_SOURCE_TYPE_PROP)))) {
                    if (StringUtils.containsAnyIgnoreCase(chainElementType.name(), ChainElementType.ASYNCAPI_TRIGGER.name(), ChainElementType.SERVICE_CALL.name()) && !ChainProperties.OPERATION_PROTOCOL_TYPE_AMQP.equals(
                        variablesService.injectVariables(props.get(
                            ChainProperties.OPERATION_PROTOCOL_TYPE_PROP)))) {
                        continue;
                    }

                    boolean isProducerElement = ChainElementType.isAmqpProducerElement(
                        chainElementType);

                    String exchange = variablesService.injectVariables(
                        props.get(ElementOptions.EXCHANGE));

                    String queues = variablesService.injectVariables(
                        props.get(ElementOptions.QUEUES));

                    String addresses = variablesService.injectVariables(
                        props.get(ElementOptions.ADDRESSES));
                    String username = variablesService.injectVariables(
                        props.get(ElementOptions.USERNAME));
                    String password = variablesService.injectVariables(
                        props.get(ElementOptions.PASSWORD));
                    String vhost = variablesService.injectVariables(
                        props.get(ElementOptions.VHOST));
                    String ssl = variablesService.injectVariables(props.get(ElementOptions.SSL));

                    if (StringUtils.isBlank(exchange) || StringUtils.isBlank(addresses)) {
                        throw new IllegalArgumentException(
                            "AMQP mandatory parameters are missing, check configuration");
                    }
                    if (!addresses.matches("^[\\w.,:\\-_]+$")) {
                        throw new IllegalArgumentException(
                            "AMQP addresses has invalid format, check configuration");
                    }

                    com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();

                    factory.setUri((StringUtils.isNotBlank(ssl) && ssl.equals("true") ?
                        "amqps://" : "amqp://") + addresses);

                    if (StringUtils.isNotBlank(username)) {
                        factory.setUsername(username);
                    }

                    if (StringUtils.isNotBlank(password)) {
                        factory.setPassword(password);
                    }

                    if (StringUtils.isNotBlank(vhost)) {
                        factory.setVirtualHost(vhost);
                    }

                    try (Connection connection = factory.newConnection()) {
                        Channel channel = connection.createChannel();

                        try {
                            if (isProducerElement) {
                                channel.exchangeDeclarePassive(exchange);
                            } else {
                                channel.queueDeclarePassive(queues);
                            }
                        } catch (IOException e) {
                            throw new DeploymentRetriableException(
                                "AMQP " + (isProducerElement ?
                                    ("exchange " + exchange) : ("queue(s) " + queues)) +
                                    " not found, check configuration");
                        }
                    } catch (IOException e) {
                        throw new DeploymentRetriableException(
                            "Connection configuration is invalid or broker is unavailable", e);
                    }
                }
            } catch (IllegalArgumentException e) {
                log.error("AMQP predeploy check is failed", e);
                throw e;
            } catch (DeploymentRetriableException e) {
                log.warn("AMQP predeploy check is failed with retriable exception", e);
                throw e;
            } catch (Exception e) {
                log.warn("Failed to check amqp connection for deployment: {}, element: {}",
                    deployment.getDeploymentInfo().getDeploymentId(),
                    elementProperties.getElementId(),
                    e);
            } finally {
                MDC.remove(ChainProperties.ELEMENT_ID);
            }
        }
    }

    /**
     * Method, which process provided configuration and returns occurred exception
     */
    private DeploymentStatus processDeployment(DeploymentUpdate deployment,
        DeploymentOperation operation) throws Exception {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        return switch (operation) {
            case UPDATE -> update(deployment);
            case STOP -> stop(deploymentInfo.getDeploymentId());
        };
    }

    private DeploymentStatus update(DeploymentUpdate deployment) throws Exception {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        String deploymentId = deploymentInfo.getDeploymentId();
        DeploymentConfiguration configuration = deployment.getConfiguration();
        String configurationXml = configuration.getXml();

        configurationXml = variablesService.injectVariables(configurationXml, true);
        if (maasService.isPresent()) {
            configurationXml = maasService.get().resolveDeploymentMaasParameters(configuration, configurationXml);
        }
        configurationXml = resolveRouteVariables(deployment.getConfiguration().getRoutes(), configurationXml);
        if (xmlPreProcessor.isPresent()) {
            configurationXml = xmlPreProcessor.get().process(configurationXml);
        }

        if (predeployCheckKafkaConfiguration.isCamelKafkaPredeployCheckEnabled()) {
            checkKafkaTopicsAndConnection(deployment);
        }
        if (amqpPredeployCheckEnabled) {
            checkAmqpConnection(deployment);
        }

        if (deployment.getDeploymentInfo().isContainsSchedulerElements()) {
            checkSchedulerRequirements();
        }

        checkSdsConnection(deployment);

        propertiesService.mergeWithRuntimeProperties(CamelDebuggerProperties.builder()
            .deploymentInfo(deployment.getDeploymentInfo())
            .maskedFields(deployment.getMaskedFields())
            .properties(configuration.getProperties())
            .build());

        SpringCamelContext context = getCache().getContexts().get(deploymentId);
        if (context != null) {
            if (log.isDebugEnabled()) {
                log.debug("Context for deployment {} already exists", deploymentId);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Creating context for deployment {}", deploymentId);
        }
        context = createContext(deploymentInfo, configuration, configurationXml);
        CamelDebugger debugger = applicationContext.getBean(CamelDebugger.class);
        debugger.setDeploymentId(deploymentId);

        List<SpringCamelContext> contextsToRemove = new LinkedList<>(getContextsRelatedToDeployment(
            deployment,
            state -> !state.getDeploymentInfo().getDeploymentId()
                .equals(deployment.getDeploymentInfo().getDeploymentId())
        ));

        try {
            startContext(context, debugger, deploymentId);
        } catch (Exception e) {
            quartzSchedulerService.commitScheduledJobs();
            sdsService.ifPresent(bean -> bean.removeSchedulerJobs(deploymentId));
            throw e;
        }
        quartzSchedulerService.removeSchedulerJobsFromContexts(contextsToRemove);
        sdsService.ifPresent(bean -> bean.removeSchedulerJobs(contextsToRemove));
        contextsToRemove.stream()
            .filter(SpringCamelContext::isRunning)
            .forEach(SpringCamelContext::stop);

        quartzSchedulerService.commitScheduledJobs();
        if (log.isDebugEnabled()) {
            log.debug("Context for deployment {} has started", deploymentId);
        }
        return DeploymentStatus.DEPLOYED;
    }

    private void checkSchedulerRequirements() {
        if (!isSchedulerDatabaseReady()) {
            log.warn("Failed to obtain DB connection for scheduler");
            throw new DeploymentRetriableException(
                "Failed to obtain DB connection for scheduler");
        } else {
            log.debug("Scheduler database is ready");
        }
    }

    private void checkSdsConnection(DeploymentUpdate deployment) {
        for (ElementProperties elementProperties : deployment.getConfiguration().getProperties()) {
            ChainElementType chainElementType = ChainElementType.fromString(
                    elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE));
            if (ChainElementType.isSdsTriggerElement(chainElementType)) {
                try {
                    sdsService.ifPresent(SdsService::getJobsMetadata);
                } catch (Exception exception) {
                    log.warn("Sds trigger predeploy check failed. Please check scheduling-service");
                    throw new DeploymentRetriableException(
                            "Sds trigger predeploy check failed. Please check scheduling-service",
                            exception);
                }
            }
        }
    }

    private boolean isSchedulerDatabaseReady() {
        try (java.sql.Connection conn = qrtzDataSource.getConnection()) {
            return conn != null;
        } catch (Exception e) {
            log.warn("Scheduler database not ready", e);
        }
        return false;
    }

    private String resolveRouteVariables(List<DeploymentRouteUpdate> routes, String text) {
        String result = text;

        for (DeploymentRouteUpdate route : routes) {
            DeploymentRouteUpdate tempRoute = formatServiceRoutes(route);

            RouteType type = tempRoute.getType();
            if (nonNull(tempRoute.getVariableName())
                && (RouteType.EXTERNAL_SENDER == type || RouteType.EXTERNAL_SERVICE == type)) {
                String variablePlaceholder = String.format("%%%%{%s}", tempRoute.getVariableName());
                String gatewayPrefix = tempRoute.getGatewayPrefix();
                result = result.replace(variablePlaceholder,
                    isNull(gatewayPrefix) ? "" : gatewayPrefix);
            }
        }
        return result;
    }

    /**
     * Stop and remove same old deployments and contexts
     */
    private void removeOldDeployments(
        DeploymentUpdate deployment,
        Function<DeploymentStatus, Boolean> statusCondition
    ) {
        Iterator<Map.Entry<String, EngineDeployment>> iterator = getCache().getDeployments()
            .entrySet().iterator();
        List<SpringCamelContext> contextsToRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<String, EngineDeployment> entry = iterator.next();

            DeploymentInfo depInfo = entry.getValue().getDeploymentInfo();
            if (depInfo.getChainId().equals(deployment.getDeploymentInfo().getChainId()) &&
                statusCondition.apply(entry.getValue().getStatus()) &&
                !depInfo.getDeploymentId()
                    .equals(deployment.getDeploymentInfo().getDeploymentId())) {

                SpringCamelContext toRemoveContext = getCache().getContexts()
                    .remove(entry.getKey());
                if (toRemoveContext != null) {
                    contextsToRemove.add(toRemoveContext);
                }

                removeRetryingDeployment(depInfo.getDeploymentId());

                metricsStore.removeChainsDeployments(depInfo.getDeploymentId());

                iterator.remove();
                propertiesService.removeDeployProperties(entry.getKey());
            }
        }

        contextsToRemove.stream().filter(SpringCamelContext::isRunning)
            .forEach(SpringCamelContext::stop);
    }

    private List<SpringCamelContext> getContextsRelatedToDeployment(DeploymentUpdate deployment) {
        return getContextsRelatedToDeployment(deployment, state -> true);
    }

    private List<SpringCamelContext> getContextsRelatedToDeployment(DeploymentUpdate deployment,
        Function<EngineDeployment, Boolean> filter) {
        return getCache().getDeployments().entrySet().stream()
            .filter(entry -> entry.getValue().getDeploymentInfo().getChainId()
                .equals(deployment.getDeploymentInfo().getChainId())
                && filter.apply(entry.getValue()))
            .map(Map.Entry::getKey)
            .map(getCache().getContexts()::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private SpringCamelContext createContext(
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration,
        String configurationXml
    ) throws Exception {
        SpringCamelContext context = new SpringCamelContext(applicationContext);
        context.getTypeConverterRegistry().addTypeConverter(
            FormData.class,
            String.class,
            applicationContext.getBean(FormDataConverter.class));
        context.getTypeConverterRegistry().addTypeConverter(
            QipSecurityAccessPolicy.class,
            String.class,
            applicationContext.getBean(SecurityAccessPolicyConverter.class));
        context.getGlobalOptions().put(JacksonConstants.ENABLE_TYPE_CONVERTER, "true");
        context.getGlobalOptions().put(JacksonConstants.TYPE_CONVERTER_TO_POJO, "true");

        dependencyBinder.bindToRegistry(
            context, deploymentInfo, deploymentConfiguration);

        boolean deploymentsSuspended = isDeploymentsSuspended();
        if (deploymentsSuspended) {
            context.setAutoStartup(false);
            log.debug("Deployment {} will be suspended due to pod initialization", deploymentInfo.getDeploymentId());
        }

        context.setClassResolver(getClassResolver(context, deploymentConfiguration));
        this.loadRoutes(context, configurationXml);
        getCache().getContexts().put(deploymentInfo.getDeploymentId(), context);
        registerComponents(context, deploymentInfo, deploymentConfiguration);
        return context;
    }

    private ClassResolver getClassResolver(
        SpringCamelContext context,
        DeploymentConfiguration deploymentConfiguration
    ) {
        Collection<String> systemModelIds = deploymentConfiguration.getProperties().stream()
            .map(ElementProperties::getProperties)
            .filter(properties -> ChainProperties.SERVICE_CALL_ELEMENT.equals(properties.get(
                ChainProperties.ELEMENT_TYPE)))
            .map(properties -> properties.get(ChainProperties.OPERATION_SPECIFICATION_ID))
            .filter(Objects::nonNull)
            .toList();
        ClassLoader classLoader = externalLibraryService.isPresent() ?
            externalLibraryService.get().getClassLoaderForSystemModels(systemModelIds, context.getApplicationContextClassLoader()) :
            getClass().getClassLoader();
        return new QipCustomClassResolver(classLoader);
    }

    private void registerComponents(SpringCamelContext context,
                                    DeploymentInfo deploymentInfo,
                                    DeploymentConfiguration deploymentConfiguration)
        throws NamingException {
        List<Map<String, String>> sdsElementsProperties = new ArrayList<>();
        for (ElementProperties elementProperties : deploymentConfiguration.getProperties()) {
            String elementId = elementProperties.getElementId();
            Map<String, String> properties = elementProperties.getProperties();
            ChainElementType elementType = ChainElementType.fromString(properties.get(
                ChainProperties.ELEMENT_TYPE));
            switch (elementType) {
                case JMS_SENDER, JMS_TRIGGER: {
                    registerJmsComponent(context, elementId, properties);
                    break;
                }
                case SDS_TRIGGER: {
                    sdsElementsProperties.add(properties);
                    break;
                }
            }
        }
        sdsService.ifPresent(bean -> bean.registerSchedulerJobs(context, deploymentInfo, sdsElementsProperties));
    }

    private void registerJmsComponent(SpringCamelContext context, String elementId,
        Map<String, String> properties)
        throws NamingException {
        Properties environment = new Properties();
        String jmsInitialContextFactory = variablesService.injectVariables(
            properties.get(ChainProperties.JMS_INITIAL_CONTEXT_FACTORY));
        String jmsProviderUrl = variablesService.injectVariables(properties.get(
            ChainProperties.JMS_PROVIDER_URL));
        String jmsConnectionFactoryName = variablesService.injectVariables(
            properties.get(ChainProperties.JMS_CONNECTION_FACTORY_NAME));

        String username = variablesService.injectVariables(properties.get(
            ChainProperties.JMS_USERNAME));
        String password = variablesService.injectVariables(properties.get(
            ChainProperties.JMS_PASSWORD));

        environment.put(Context.INITIAL_CONTEXT_FACTORY, jmsInitialContextFactory);
        environment.put(Context.PROVIDER_URL, jmsProviderUrl);

        boolean secured = !StringUtils.isBlank(username) && !StringUtils.isBlank(password);
        if (secured) {
            environment.put(Context.SECURITY_PRINCIPAL, username);
            environment.put(Context.SECURITY_CREDENTIALS, password);
        }

        JndiTemplate jmsJndiTemplate = new JndiTemplate(environment);

        JndiObjectFactoryBean jmsConnectionFactory = new JndiObjectFactoryBean();
        jmsConnectionFactory.setJndiTemplate(jmsJndiTemplate);
        jmsConnectionFactory.setJndiName(jmsConnectionFactoryName);
        jmsConnectionFactory.setProxyInterface(ConnectionFactory.class);
        jmsConnectionFactory.setLookupOnStartup(false);
        jmsConnectionFactory.setExposeAccessContext(true);
        jmsConnectionFactory.afterPropertiesSet();

        JndiDestinationResolver jndiDestinationResolver = new JndiDestinationResolver();
        jndiDestinationResolver.setJndiTemplate(jmsJndiTemplate);
        jndiDestinationResolver.setFallbackToDynamicDestination(true);

        JmsConfiguration jmsConfiguration = new JmsConfiguration();
        jmsConfiguration.setConnectionFactory((ConnectionFactory) jmsConnectionFactory.getObject());
        jmsConfiguration.setDestinationResolver(jndiDestinationResolver);

        WeblogicSecurityBean wlSecurityBean = wlSecurityBeanProvider.getIfAvailable();
        WeblogicSecureThreadFactory wlSecureThreadFactory = wlSecureThreadFactoryProvider.getIfAvailable();
        WeblogicSecurityInterceptStrategy wlSecurityInterceptStrategy = wlSecurityInterceptStrategyProvider.getIfAvailable();
        if (secured && wlSecurityBean != null && wlSecureThreadFactory != null &&
            wlSecurityInterceptStrategy != null
        ) {
            wlSecurityBean.setProviderUrl(jmsProviderUrl);
            wlSecurityBean.setSecurityPrincipal(username);
            wlSecurityBean.setSecurityCredentials(password);

            wlSecureThreadFactory.setName("jms-thread-factory-" + elementId);
            wlSecureThreadFactory.setWeblogicSecurityBean(wlSecurityBean);

            ThreadPoolProfile profile = context.getExecutorServiceManager().getDefaultThreadPoolProfile();
            ThreadPoolTaskExecutor jmsTaskExecutor = new ThreadPoolTaskExecutor();
            jmsTaskExecutor.setBeanName("jms-task-executor-" + elementId);
            jmsTaskExecutor.setThreadFactory(wlSecureThreadFactory);
            jmsTaskExecutor.setCorePoolSize(profile.getPoolSize());
            jmsTaskExecutor.setMaxPoolSize(profile.getMaxPoolSize());
            jmsTaskExecutor.setKeepAliveSeconds(profile.getKeepAliveTime().intValue());
            jmsTaskExecutor.setQueueCapacity(profile.getMaxQueueSize());
            jmsTaskExecutor.afterPropertiesSet();

            jmsConfiguration.setTaskExecutor(jmsTaskExecutor);

            wlSecurityInterceptStrategy.setTargetId(elementId);
            wlSecurityInterceptStrategy.setWeblogicSecurityBean(wlSecurityBean);

            context.getCamelContextExtension().addInterceptStrategy(wlSecurityInterceptStrategy);
        }

        JmsComponent jmsComponent = new JmsComponent(jmsConfiguration);

        String componentName = buildJmsComponentName(elementId, properties);
        context.addComponent(componentName, jmsComponent);
    }

    private String buildJmsComponentName(String elementId, Map<String, String> properties) {
        return String.format("jms-%s", elementId);
    }

    private void startContext(SpringCamelContext context, CamelDebugger debugger,
        String contextMgmtSuffix) {
        context.setApplicationContext(applicationContext);
        context.setManagementName(
            "camel-context_" + contextMgmtSuffix); // use repeatable after restart context name
        context.setManagementStrategy(new DefaultManagementStrategy(context));
        context.setDebugger(debugger);
        context.setDebugging(true);

        configureMessageHistoryFactory(context);

        context.setStreamCaching(enableStreamCaching);
        if (enableStreamCaching) {
            DefaultStreamCachingStrategy streamCachingStrategy = new DefaultStreamCachingStrategy();
            streamCachingStrategy.setBufferSize(streamCachingBufferSize);
            context.setStreamCachingStrategy(streamCachingStrategy);
        }

        if (tracingConfiguration.isTracingEnabled()) {
            Tracer tracer = applicationContext.getBean("camelObservationTracer", MicrometerObservationTracer.class);
            tracer.init(context);
        }

        context.start();

        if (!debugger.isStartingOrStarted()) {
            debugger.start();
        }
    }

    private void configureMessageHistoryFactory(SpringCamelContext context) {
        context.setMessageHistory(true);
        MessageHistoryFactory defaultFactory = context.getMessageHistoryFactory();
        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(
            camelMessageHistoryFilter, defaultFactory);
        context.setMessageHistoryFactory(factory);
    }

    /**
     * Upload routes to a new context from provided configuration
     */
    private void loadRoutes(SpringCamelContext context, String xmlConfiguration) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Loading routes from: \n{}", xmlConfiguration);
        }

        byte[] configurationBytes = xmlConfiguration.getBytes();
        ByteArrayInputStream configInputStream = new ByteArrayInputStream(configurationBytes);
        RoutesDefinition routesDefinition = loadRoutesDefinition(context, configInputStream);

        // xml routes must be marked as un-prepared as camel-core
        // must do special handling for XML DSL
        for (RouteDefinition route : routesDefinition.getRoutes()) {
            RouteDefinitionHelper.prepareRoute(context, route);
            route.markPrepared();
        }
        routesDefinition.getRoutes().forEach(RouteDefinition::markUnprepared);

        compileGroovyScripts(routesDefinition);

        context.addRouteDefinitions(routesDefinition.getRoutes());
    }

    private void compileGroovyScripts(RoutesDefinition routesDefinition) {
        for (RouteDefinition route : routesDefinition.getRoutes()) {
            for (ProcessorDefinition<?> processor : route.getOutputs()) {
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

    private DeploymentStatus stop(String deploymentId) {
        stopContext(deploymentId);
        return DeploymentStatus.REMOVED;
    }

    private void stopContext(String deploymentId) {
        SpringCamelContext context = getCache().getContexts().remove(deploymentId);
        sdsService.ifPresent(bean -> bean.removeSchedulerJobs(deploymentId));
        if (context != null) {
            log.debug("Removing context for deployment: {}", deploymentId);
            quartzSchedulerService.removeSchedulerJobsFromContexts(
                Collections.singletonList(context));
            context.stop();
        }
    }

    public void retryProcessingDeploys() {
        try {
            Collection<DeploymentUpdate> toRetry = getCache().flushDeploymentsToRetry();
            if (!toRetry.isEmpty()) {
                processAndUpdateState(DeploymentsUpdate.builder().update(toRetry).build(), true);
            }
        } catch (Exception e) {
            log.error("Failed to process retry deployments", e);
        }
    }

    private void putInRetryQueue(DeploymentUpdate deploymentUpdate) {
        log.info("Deployment marked for retry {}",
            deploymentUpdate.getDeploymentInfo().getDeploymentId());
        getCache().putToRetryQueue(deploymentUpdate);
    }

    private void removeRetryingDeployment(String deploymentId) {
        getCache().removeRetryDeploymentFromQueue(deploymentId);
    }

    private RuntimeIntegrationCache getCache() {
        return deploymentCache;
    }

    public void startAllRoutesOnInit() {
        getCache().getContexts().forEach((deploymentId, context) -> {
            EngineDeployment state = getCache().getDeployments().get(deploymentId);
            try {
                context.startAllRoutes();
                log.debug("Deployment {} was resumed from suspend", deploymentId);
            } catch (Exception e) {
                if (state != null) {
                    state.setStatus(DeploymentStatus.FAILED);
                    state.setErrorMessage("Deployment wasn't initialized correctly during pod startup " + e.getMessage());
                }
                ErrorCode errorCode = ErrorCode.DEPLOYMENT_START_ERROR;
                log.error(errorCode, errorCode.compileMessage(deploymentId), e);
            } finally {
                if (state != null) {
                    state.setSuspended(false);
                }
            }
        });
    }
}
