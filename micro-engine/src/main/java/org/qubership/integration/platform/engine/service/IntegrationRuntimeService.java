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

import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.model.*;
import org.apache.camel.reifier.ProcessorReifier;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.camel.context.propagation.constant.BusinessIds;
import org.qubership.integration.platform.engine.camel.reifiers.CustomResilienceReifier;
import org.qubership.integration.platform.engine.camel.reifiers.CustomStepReifier;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import org.qubership.integration.platform.engine.consul.EngineStateReporter;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.deployment.DeploymentOperation;
import org.qubership.integration.platform.engine.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;
import org.qubership.integration.platform.engine.model.deployment.update.*;
import org.qubership.integration.platform.engine.service.deployment.DeploymentLockHelper;
import org.qubership.integration.platform.engine.service.deployment.DeploymentRetryQueue;
import org.qubership.integration.platform.engine.service.deployment.DeploymentStateHolder;
import org.qubership.integration.platform.engine.service.deployment.preprocessor.DeploymentPreprocessorService;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingService;
import org.qubership.integration.platform.engine.util.MDCUtil;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.engine.consul.ConsulSessionService.CREATE_SESSION_EVENT;

@ApplicationScoped
public class IntegrationRuntimeService {
    @SuppressWarnings("checkstyle:ConstantName")
    private static final ExtendedErrorLogger log = ExtendedErrorLoggerFactory.getLogger(IntegrationRuntimeService.class);

    private final ServerConfiguration serverConfiguration;
    private final QuartzSchedulerService quartzSchedulerService;
    private final EngineStateReporter engineStateReporter;
    private final DeploymentReadinessService deploymentReadinessService;
    private final DeploymentRetryQueue retryQueue = new DeploymentRetryQueue();
    private final DeploymentLockHelper lockHelper = new DeploymentLockHelper();
    private final DeploymentStateHolder deploymentStateHolder = new DeploymentStateHolder();
    private final DeploymentProcessingService deploymentProcessingService;
    private final Executor deploymentExecutor;
    private final DeploymentPreprocessorService deploymentPreprocessorService;

    static {
        ProcessorReifier.registerReifier(StepDefinition.class, CustomStepReifier::new);
        ProcessorReifier.registerReifier(CircuitBreakerDefinition.class,
            (route, definition) -> new CustomResilienceReifier(route,
                (CircuitBreakerDefinition) definition));
    }

    @Inject
    public IntegrationRuntimeService(ServerConfiguration serverConfiguration,
        QuartzSchedulerService quartzSchedulerService,
        EngineStateReporter engineStateReporter,
        @Named("deploymentExecutor") Executor deploymentExecutor,
        DeploymentReadinessService deploymentReadinessService,
        DeploymentProcessingService deploymentProcessingService,
        DeploymentPreprocessorService deploymentPreprocessorService
    ) {
        this.serverConfiguration = serverConfiguration;
        this.quartzSchedulerService = quartzSchedulerService;
        this.engineStateReporter = engineStateReporter;
        this.deploymentExecutor = deploymentExecutor;
        this.deploymentReadinessService = deploymentReadinessService;
        this.deploymentProcessingService = deploymentProcessingService;
        this.deploymentPreprocessorService = deploymentPreprocessorService;
    }

    @ConsumeEvent(CREATE_SESSION_EVENT)
    public void onConsulSessionCreated(String sessionId) throws Exception {
        // if consul session (re)create - force update engine state
        notifyEngineState();
    }

    // requires completion of all deployment processes
    public List<DeploymentInfo> buildExcludeDeploymentsMap() throws Exception {
        return lockHelper.runWithProcessWriteLock(() ->
                deploymentStateHolder.values().map(Pair::getKey).toList());
    }

    // requires completion of all deployment processes
    private Map<String, EngineDeployment> buildActualDeploymentsSnapshot() throws Exception {
        return lockHelper.runWithProcessWriteLock(() ->
                deploymentStateHolder.values()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().getDeploymentId(),
                        entry -> {
                            DeploymentStateHolder.DeploymentState deploymentState = entry.getValue();
                            return EngineDeployment.builder()
                                    .deploymentInfo(entry.getKey().toBuilder()
                                            .chainStatusCode(deploymentState.getChainStatusCode())
                                            .build())
                                    .errorMessage(deploymentState.getErrorMessage())
                                    .suspended(deploymentState.isSuspended())
                                    .status(deploymentState.getStatus())
                                    .build();
                        })));
    }

    /**
     * Start parallel deployments processing, wait for completion and update engine state
     */
    public void processAndUpdateState(DeploymentsUpdate update, boolean retry)
            throws Exception {
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
        notifyEngineState();
    }

    private synchronized void notifyEngineState() throws Exception {
        engineStateReporter.addStateToQueue(buildEngineState());
    }

    private EngineState buildEngineState() throws Exception {
        return EngineState.builder()
                .engine(serverConfiguration.getEngineInfo())
                .deployments(buildActualDeploymentsSnapshot())
                .build();
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
                lockHelper.runWithChainLock(
                        chainDeployment.getDeploymentInfo().getChainId(),
                        () -> {
                            // update and retry concurrent case: check if retry deployment is still required
                            // necessary so as not to break the order of deployments
                            if (!retry || deploymentStateHolder.has(
                                    chainDeployment.getDeploymentInfo().getDeploymentId())) {
                                lockHelper.runWithProcessReadLock(() -> {
                                    log.debug("Locked process read lock");
                                    processDeploymentUpdate(chainDeployment, operation);
                                });
                            }
                        }
                );
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
        Optional<Throwable> exception = Optional.empty();
        Optional<ErrorCode> errorCode = Optional.empty();
        DeploymentStatus status = DeploymentStatus.FAILED;

        try {
            MDCUtil.setBusinessIds(Map.of(
                BusinessIds.CHAIN_ID, chainId,
                BusinessIds.DEPLOYMENT_ID, deploymentId,
                BusinessIds.SNAPSHOT_ID, snapshotId));

            log.info("Processing deployment {}: {} for chain {}", deploymentId, deployment.getDeploymentInfo(), chainId);

            status = processDeployment(deployment, operation);
        } catch (KubeApiException e) {
            exception = Optional.of(e);
        } catch (DeploymentRetriableException e) {
            status = DeploymentStatus.PROCESSING;
            log.info("Scheduling deployment for retry {}",
                    deployment.getDeploymentInfo().getDeploymentId());
            retryQueue.put(deployment);
            exception = Optional.of(e);
            errorCode = Optional.of(ErrorCode.PREDEPLOY_CHECK_ERROR);
        } catch (Throwable e) {
            ErrorCode code = ErrorCode.UNEXPECTED_DEPLOYMENT_ERROR;
            log.error(code, code.compileMessage(deploymentId), e);
            exception = Optional.of(e);
            errorCode = Optional.of(code);
        } finally {
            log.info("Status of deployment {} for chain {} is {}", deploymentId, chainId, status);
            try {
                quartzSchedulerService.resetSchedulersProxy();
                switch (status) {
                    case DEPLOYED, FAILED, PROCESSING -> {
                        if (exception.isPresent()) {
                            log.error(
                                    errorCode.orElse(null),
                                    "Failed to deploy chain {} with id {}. Deployment: {}",
                                    deployment.getDeploymentInfo().getChainName(),
                                    chainId,
                                    deploymentId,
                                    exception.get());
                        }

                        undeploy(isOtherDeploymentForThisChain(deployment.getDeploymentInfo()).and(
                                exception.map(e -> isFailedOrProcessing())
                                        .orElse((info, state) -> true)));

                        var deploymentState = DeploymentStateHolder.DeploymentState.builder()
                                .status(status)
                                // If Pod is not initialized yet and this is first deploy -
                                // set corresponding flag and Processing status
                                .suspended(status == DeploymentStatus.DEPLOYED && isDeploymentsSuspended())
                                .chainStatusCode(errorCode.map(ErrorCode::getCode).orElse(null))
                                .errorMessage(exception.map(Throwable::getMessage).orElse(null))
                                .build();
                        deploymentStateHolder.put(deployment.getDeploymentInfo(), deploymentState);
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
            case STOP -> stop(deployment);
        };
    }

    private DeploymentStatus update(DeploymentUpdate deployment) throws Exception {
        DeploymentUpdate preprocessedDeployment = deploymentPreprocessorService.preprocess(deployment);
        boolean deploymentsSuspended = isDeploymentsSuspended();
        if (deploymentsSuspended) {
            log.debug("Deployment {} will be suspended due to pod initialization",
                    preprocessedDeployment.getDeploymentInfo().getDeploymentId());
        }
        deploymentProcessingService.deploy(preprocessedDeployment, !deploymentsSuspended);
        return DeploymentStatus.DEPLOYED;
    }

    private Collection<DeploymentInfo> getDeployments(
            BiPredicate<DeploymentInfo, DeploymentStateHolder.DeploymentState> predicate
    ) {
        return deploymentStateHolder.values()
                .filter(entry -> predicate.test(entry.getKey(), entry.getValue()))
                .map(Entry::getKey)
                .toList();
    }

    private BiPredicate<DeploymentInfo, DeploymentStateHolder.DeploymentState> isOtherDeploymentForThisChain(
            DeploymentInfo deploymentInfo
    ) {
        String chainId = deploymentInfo.getChainId();
        String deploymentId = deploymentInfo.getDeploymentId();
        return (info, state) -> info.getChainId().equals(chainId)
                && !info.getDeploymentId().equals(deploymentId);
    }

    private BiPredicate<DeploymentInfo, DeploymentStateHolder.DeploymentState> isFailedOrProcessing() {
        return (info, state) ->
                state.getStatus() == DeploymentStatus.FAILED
                        || state.getStatus() == DeploymentStatus.PROCESSING;
    }

    private void undeploy(BiPredicate<DeploymentInfo, DeploymentStateHolder.DeploymentState> predicate) {
        getDeployments(predicate).forEach(this::tryUndeploy);
    }

    private void tryUndeploy(DeploymentInfo deploymentInfo) {
        try {
            stop(DeploymentUpdate.builder().deploymentInfo(deploymentInfo).build());
        } catch (Exception exception) {
            log.error("Failed to undeploy {}", deploymentInfo.getDeploymentId(), exception);
        }
    }

    private DeploymentStatus stop(DeploymentUpdate deploymentUpdate) throws Exception {
        deploymentProcessingService.undeploy(deploymentUpdate);
        String deploymentId = deploymentUpdate.getDeploymentInfo().getDeploymentId();
        deploymentStateHolder.remove(deploymentId);
        retryQueue.remove(deploymentId);
        return DeploymentStatus.REMOVED;
    }

    public void retryProcessingDeploys() {
        try {
            Collection<DeploymentUpdate> toRetry = retryQueue.flush();
            if (!toRetry.isEmpty()) {
                processAndUpdateState(DeploymentsUpdate.builder().update(toRetry).build(), true);
            }
        } catch (Exception e) {
            log.error("Failed to process retry deployments", e);
        }
    }

    public void startSuspendedRoutesOnInit() {
        var entries = deploymentStateHolder.values()
                .filter(entry -> entry.getValue().isSuspended())
                .toList();
        entries.forEach(entry -> {
            DeploymentInfo deploymentInfo = entry.getKey();
            String deploymentId = deploymentInfo.getDeploymentId();
            var stateBuilder = entry.getValue().toBuilder();
            try {
                deploymentProcessingService.startRoutes(deploymentId);
            } catch (Exception exception) {
                ErrorCode errorCode = ErrorCode.DEPLOYMENT_START_ERROR;
                log.error(errorCode, errorCode.compileMessage(deploymentId), exception);
                stateBuilder
                        .status(DeploymentStatus.FAILED)
                        .errorMessage("Deployment wasn't initialized correctly during pod startup "
                                + exception.getMessage())
                        .chainStatusCode(errorCode.getCode());
            } finally {
                deploymentStateHolder.put(deploymentInfo, stateBuilder.suspended(false).build());
            }
        });
    }

    public void suspendAllSchedulers() {
        lockHelper.runWithProcessWriteLock(quartzSchedulerService::suspendAllSchedulers);
    }

    public void resumeAllSchedulers() {
        lockHelper.runWithProcessWriteLock(quartzSchedulerService::resumeAllSchedulers);
    }
}
