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

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.apache.camel.component.jackson.JacksonConstants;
import org.apache.camel.impl.engine.DefaultManagementStrategy;
import org.apache.camel.impl.engine.DefaultStreamCachingStrategy;
import org.apache.camel.model.*;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.reifier.ProcessorReifier;
import org.apache.camel.spi.ClassResolver;
import org.apache.camel.spi.MessageHistoryFactory;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.tracing.Tracer;
import org.apache.commons.lang3.tuple.Pair;
import org.codehaus.groovy.control.CompilationFailedException;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.camel.CustomResilienceReifier;
import org.qubership.integration.platform.engine.camel.QipCustomClassResolver;
import org.qubership.integration.platform.engine.camel.context.propagation.constant.BusinessIds;
import org.qubership.integration.platform.engine.camel.converters.FormDataConverter;
import org.qubership.integration.platform.engine.camel.converters.SecurityAccessPolicyConverter;
import org.qubership.integration.platform.engine.camel.history.FilteringMessageHistoryFactory;
import org.qubership.integration.platform.engine.camel.history.FilteringMessageHistoryFactory.FilteringEntity;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.configuration.TracingConfiguration;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import org.qubership.integration.platform.engine.consul.EngineStateReporter;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.events.ConsulSessionCreatedEvent;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.model.RuntimeIntegrationCache;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.DeploymentOperation;
import org.qubership.integration.platform.engine.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.update.*;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingService;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before.RegisterRoutesInControlPlaneAction;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryGroovyShellFactory;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import org.qubership.integration.platform.engine.service.externallibrary.GroovyLanguageWithResettableCache;
import org.qubership.integration.platform.engine.service.xmlpreprocessor.XmlConfigurationPreProcessor;
import org.qubership.integration.platform.engine.util.MDCUtil;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    private final ExternalLibraryGroovyShellFactory groovyShellFactory;
    private final GroovyLanguageWithResettableCache groovyLanguage;
    private final MetricsStore metricsStore;
    private final Optional<ExternalLibraryService> externalLibraryService;
    private final Optional<MaasService> maasService;
    private final Optional<XmlConfigurationPreProcessor> xmlPreProcessor;
    private final VariablesService variablesService;
    private final EngineStateReporter engineStateReporter;
    private final CamelDebuggerPropertiesService propertiesService;
    private final DeploymentReadinessService deploymentReadinessService;

    private final Predicate<FilteringEntity> camelMessageHistoryFilter;

    private final RuntimeIntegrationCache deploymentCache = new RuntimeIntegrationCache();
    private final ReadWriteLock processLock = new ReentrantReadWriteLock();

    private final Executor deploymentExecutor;

    private ApplicationContext applicationContext;

    private final DeploymentProcessingService deploymentProcessingService;

    static {
        ProcessorReifier.registerReifier(StepDefinition.class, CustomStepReifier::new);
        ProcessorReifier.registerReifier(CircuitBreakerDefinition.class,
            (route, definition) -> new CustomResilienceReifier(route,
                (CircuitBreakerDefinition) definition));
    }

    @Value("${qip.camel.stream-caching.enabled}")
    private boolean enableStreamCaching;

    private final int streamCachingBufferSize;

    @Autowired
    public IntegrationRuntimeService(ServerConfiguration serverConfiguration,
        QuartzSchedulerService quartzSchedulerService,
        TracingConfiguration tracingConfiguration,
        ExternalLibraryGroovyShellFactory groovyShellFactory,
        GroovyLanguageWithResettableCache groovyLanguage,
        MetricsStore metricsStore,
        Optional<ExternalLibraryService> externalLibraryService,
        Optional<MaasService> maasService,
        Optional<XmlConfigurationPreProcessor> xmlPreProcessor,
        VariablesService variablesService,
        EngineStateReporter engineStateReporter,
        @Qualifier("deploymentExecutor") Executor deploymentExecutor,
        CamelDebuggerPropertiesService propertiesService,
        @Value("${qip.camel.stream-caching.buffer.size-kb}") int streamCachingBufferSizeKb,
        Predicate<FilteringEntity> camelMessageHistoryFilter,
        DeploymentReadinessService deploymentReadinessService,
        DeploymentProcessingService deploymentProcessingService
    ) {
        this.serverConfiguration = serverConfiguration;
        this.quartzSchedulerService = quartzSchedulerService;
        this.tracingConfiguration = tracingConfiguration;
        this.groovyShellFactory = groovyShellFactory;
        this.groovyLanguage = groovyLanguage;
        this.metricsStore = metricsStore;
        this.externalLibraryService = externalLibraryService;
        this.maasService = maasService;
        this.xmlPreProcessor = xmlPreProcessor;
        this.variablesService = variablesService;
        this.engineStateReporter = engineStateReporter;
        this.deploymentExecutor = deploymentExecutor;
        this.propertiesService = propertiesService;

        this.streamCachingBufferSize = streamCachingBufferSizeKb * 1024;

        this.camelMessageHistoryFilter = camelMessageHistoryFilter;

        this.deploymentReadinessService = deploymentReadinessService;
        this.deploymentProcessingService = deploymentProcessingService;
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

    /**
     * Method, which process provided configuration and returns occurred exception
     */
    private DeploymentStatus processDeployment(
        DeploymentUpdate deployment,
        DeploymentOperation operation
    ) throws Exception {
        return switch (operation) {
            case UPDATE -> update(deployment);
            case STOP -> stop(deployment.getDeploymentInfo());
        };
    }

    private DeploymentStatus update(DeploymentUpdate deployment) throws Exception {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        String deploymentId = deploymentInfo.getDeploymentId();
        DeploymentConfiguration configuration = deployment.getConfiguration();
        String configurationXml = preprocessDeploymentConfigurationXml(configuration);

        deploymentProcessingService.processBeforeContextCreated(deploymentInfo, configuration);

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
        context = buildContext(deploymentInfo, configuration, configurationXml);
        getCache().getContexts().put(deploymentId, context);

        List<Pair<DeploymentInfo, SpringCamelContext>> contextsToStop = getContextsRelatedToDeployment(
            deployment,
            state -> !state.getDeploymentInfo().getDeploymentId()
                .equals(deployment.getDeploymentInfo().getDeploymentId())
        );

        try {
            startContext(context);
        } catch (Exception e) {
            quartzSchedulerService.commitScheduledJobs();
            deploymentProcessingService.processStopContext(context, deploymentInfo, configuration);
            throw e;
        }

        contextsToStop.stream().forEach(p -> stopDeploymentContext(p.getRight(), p.getLeft()));

        quartzSchedulerService.commitScheduledJobs();
        if (log.isDebugEnabled()) {
            log.debug("Context for deployment {} has started", deploymentId);
        }
        return DeploymentStatus.DEPLOYED;
    }

    private String preprocessDeploymentConfigurationXml(DeploymentConfiguration configuration) throws URISyntaxException {
        String configurationXml = configuration.getXml();

        configurationXml = variablesService.injectVariables(configurationXml, true);
        if (maasService.isPresent()) {
            configurationXml = maasService.get().resolveDeploymentMaasParameters(configuration, configurationXml);
        }
        configurationXml = resolveRouteVariables(configuration.getRoutes(), configurationXml);
        if (xmlPreProcessor.isPresent()) {
            configurationXml = xmlPreProcessor.get().process(configurationXml);
        }

        return configurationXml;
    }

    private String resolveRouteVariables(List<DeploymentRouteUpdate> routes, String text) {
        String result = text;

        for (DeploymentRouteUpdate route : routes) {
            DeploymentRouteUpdate tempRoute =
                    RegisterRoutesInControlPlaneAction.formatServiceRoutes(route);

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
        Predicate<DeploymentStatus> statusPredicate
    ) {
        Iterator<Map.Entry<String, EngineDeployment>> iterator = getCache().getDeployments()
            .entrySet().iterator();
        List<Pair<DeploymentInfo, SpringCamelContext>> contextsToRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<String, EngineDeployment> entry = iterator.next();

            DeploymentInfo depInfo = entry.getValue().getDeploymentInfo();
            if (depInfo.getChainId().equals(deployment.getDeploymentInfo().getChainId()) &&
                statusPredicate.test(entry.getValue().getStatus()) &&
                !depInfo.getDeploymentId()
                    .equals(deployment.getDeploymentInfo().getDeploymentId())) {

                SpringCamelContext toRemoveContext = getCache().getContexts()
                    .remove(entry.getKey());
                if (toRemoveContext != null) {
                    contextsToRemove.add(Pair.of(depInfo, toRemoveContext));
                }

                removeRetryingDeployment(depInfo.getDeploymentId());

                metricsStore.removeChainsDeployments(depInfo.getDeploymentId());

                iterator.remove();
                propertiesService.removeDeployProperties(entry.getKey());
            }
        }

        contextsToRemove.stream().filter(p -> p.getRight().isRunning())
            .forEach(p -> stopDeploymentContext(p.getRight(), p.getLeft()));
    }

    private List<Pair<DeploymentInfo, SpringCamelContext>> getContextsRelatedToDeployment(
        DeploymentUpdate deployment,
        Predicate<EngineDeployment> filter
    ) {
        return getCache().getDeployments().entrySet().stream()
            .filter(entry -> entry.getValue().getDeploymentInfo().getChainId()
                .equals(deployment.getDeploymentInfo().getChainId())
                && filter.test(entry.getValue()))
            .map(entry -> Pair.of(
                    entry.getValue().getDeploymentInfo(),
                    getCache().getContexts().get(entry.getKey())))
            .toList();
    }

    private SpringCamelContext buildContext(
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
        context.getInflightRepository().setInflightBrowseEnabled(true);

        boolean deploymentsSuspended = isDeploymentsSuspended();
        if (deploymentsSuspended) {
            context.setAutoStartup(false);
            log.debug("Deployment {} will be suspended due to pod initialization", deploymentInfo.getDeploymentId());
        }

        context.setClassResolver(getClassResolver(context, deploymentConfiguration));

        context.setApplicationContext(applicationContext);

        String deploymentId = deploymentInfo.getDeploymentId();
        context.setManagementName("camel-context_" + deploymentId); // use repeatable after restart context name
        context.setManagementStrategy(new DefaultManagementStrategy(context));

        CamelDebugger debugger = applicationContext.getBean(CamelDebugger.class);
        debugger.setDeploymentId(deploymentId);
        context.setDebugger(debugger);
        context.setDebugging(true);

        configureMessageHistoryFactory(context);

        context.setStreamCaching(enableStreamCaching);
        if (enableStreamCaching) {
            DefaultStreamCachingStrategy streamCachingStrategy = new DefaultStreamCachingStrategy();
            streamCachingStrategy.setBufferSize(streamCachingBufferSize);
            context.setStreamCachingStrategy(streamCachingStrategy);
        }

        deploymentProcessingService.processAfterContextCreated(context, deploymentInfo, deploymentConfiguration);

        this.loadRoutes(context, configurationXml);
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

    private void startContext(SpringCamelContext context) {
        if (tracingConfiguration.isTracingEnabled()) {
            Tracer tracer = applicationContext.getBean("camelObservationTracer", MicrometerObservationTracer.class);
            tracer.init(context);
        }

        context.start();

        CamelDebugger debugger = (CamelDebugger) context.getDebugger();
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

    private DeploymentStatus stop(DeploymentInfo deploymentInfo) {
        String deploymentId = deploymentInfo.getDeploymentId();
        SpringCamelContext context = getCache().getContexts().remove(deploymentId);
        if (nonNull(context)) {
            log.debug("Removing context for deployment: {}", deploymentInfo.getDeploymentId());
        }
        stopDeploymentContext(context, deploymentInfo);
        return DeploymentStatus.REMOVED;
    }

    private void stopDeploymentContext(SpringCamelContext context, DeploymentInfo deploymentInfo) {
        deploymentProcessingService.processStopContext(context, deploymentInfo, null);
        if (nonNull(context)) {
            quartzSchedulerService.removeSchedulerJobsFromContexts(
                Collections.singletonList(context));
            if (context.isRunning()) {
                log.debug("Stopping context for deployment: {}", deploymentInfo.getDeploymentId());
                context.stop();
            }
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

    public RuntimeIntegrationCache getCache() {
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

    private void runInProcessLock(Runnable callback) {
        Lock lock = this.processLock.writeLock();
        try {
            lock.lock();
            callback.run();
        } finally {
            lock.unlock();
        }
    }

    public void suspendAllSchedulers() {
        runInProcessLock(quartzSchedulerService::suspendAllSchedulers);
    }

    public void resumeAllSchedulers() {
        runInProcessLock(quartzSchedulerService::resumeAllSchedulers);
    }
}
