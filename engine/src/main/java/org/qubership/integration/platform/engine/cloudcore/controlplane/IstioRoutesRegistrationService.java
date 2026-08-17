package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObject;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObjectRequest;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPBackendRef;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteRule;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteSpec;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteTimeouts;
import org.qubership.integration.platform.engine.model.gatewayapi.ParentReference;
import org.qubership.integration.platform.engine.util.GatewayDuration;
import org.qubership.integration.platform.engine.util.paths.GatewayPathMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class IstioRoutesRegistrationService implements ControlPlaneService {

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String PUBLIC_GATEWAY_NAME = "public-gateway";
    private static final String PRIVATE_GATEWAY_NAME = "private-gateway";
    private static final int BACKEND_PORT = 8080;

    private final KubeOperator kubeOperator;
    private final ObjectMapper objectMapper;
    private final String namespace;
    private final String baseRoutePrefix;

    @Autowired
    public IstioRoutesRegistrationService(
            KubeOperator kubeOperator,
            ObjectMapper objectMapper,
            @Value("${cloud.microservice.namespace}") String namespace,
            @Value("${qip.chains.external-routes.base-path}") String baseRoutePrefix
    ) {
        this.kubeOperator = kubeOperator;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
        this.baseRoutePrefix = baseRoutePrefix;
    }

    @Override
    public synchronized void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "public"), deploymentRoutes, PUBLIC_GATEWAY_NAME, endpoint, true);
    }

    @Override
    public synchronized void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "private"), deploymentRoutes, PRIVATE_GATEWAY_NAME, endpoint, true);
    }

    @Override
    public synchronized void removeEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName)
            throws ControlPlaneException {
        List<DeploymentRouteUpdate> publicRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPublicTriggerRoute(route.getType()))
                .toList();
        if (!publicRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "public"), publicRoutes, PUBLIC_GATEWAY_NAME, deploymentName, false);
        }

        List<DeploymentRouteUpdate> privateRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPrivateTriggerRoute(route.getType()))
                .toList();
        if (!privateRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "private"), privateRoutes, PRIVATE_GATEWAY_NAME, deploymentName, false);
        }
    }

    @Override
    public void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint) {
        throw new UnsupportedOperationException(
                "Egress gateway route registration is not implemented for Istio Ambient Mesh yet; "
                        + "see docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md");
    }

    private static final int MAX_MERGE_ATTEMPTS = 3;

    private void mergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            String backendName,
            boolean buildRules
    ) {
        if (givenRoutes.isEmpty()) {
            return;
        }
        try {
            for (int attempt = 1; attempt <= MAX_MERGE_ATTEMPTS; attempt++) {
                try {
                    attemptMergeTierRoutes(tierRequest, givenRoutes, gatewayName, backendName, buildRules);
                    return;
                } catch (KubeApiConflictException e) {
                    if (attempt == MAX_MERGE_ATTEMPTS) {
                        throw e;
                    }
                    log.warn("Concurrent update detected for {} on attempt {}/{}, retrying",
                            tierRequest.getBody().getMetadata().getName(), attempt, MAX_MERGE_ATTEMPTS);
                }
            }
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update Istio HTTPRoute for control plane routes: {}", e.getMessage());
            throw new ControlPlaneException("Failed to update Istio HTTPRoute for control plane routes", e);
        }
    }

    private void attemptMergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            String backendName,
            boolean buildRules
    ) {
        Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
        List<HTTPRouteRule> existingRules = current
                .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                .map(HTTPRouteSpec::getRules)
                .filter(Objects::nonNull)
                .orElse(List.of());

        Set<GatewayPathMatch> touchedPaths = givenRoutes.stream()
                .map(route -> GatewayPathMatch.forPath(baseRoutePrefix + route.getPath()))
                .collect(Collectors.toSet());

        List<HTTPRouteRule> preservedRules = existingRules.stream()
                .filter(rule -> !touchedPaths.contains(ruleMatch(rule)))
                .toList();

        List<HTTPRouteRule> newRules = buildRules
                ? givenRoutes.stream().map(route -> buildRule(route, backendName)).toList()
                : List.of();

        List<HTTPRouteRule> mergedRules = new ArrayList<>(preservedRules);
        mergedRules.addAll(newRules);

        tierRequest.getBody().getMetadata().setResourceVersion(
                current.map(obj -> obj.getMetadata().getResourceVersion()).orElse(null));

        if (mergedRules.isEmpty()) {
            if (current.isPresent()) {
                kubeOperator.deleteCustomObject(tierRequest);
            }
            return;
        }

        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(parentRefs(gatewayName))
                .rules(mergedRules)
                .build();
        tierRequest.getBody().setSpec(objectMapper.convertValue(spec, new TypeReference<Map<String, Object>>() {}));
        kubeOperator.createOrReplaceCustomObject(tierRequest);
    }

    /**
     * Reads the path match off an existing rule fetched from the cluster. Unlike
     * {@code runtime-catalog}'s sibling code (which preserves an unrecognized rule and logs a
     * warning), a malformed rule here throws (e.g. {@link IndexOutOfBoundsException} on an
     * empty {@code matches} list), which {@link #mergeTierRoutes} wraps as a
     * {@link ControlPlaneException} and aborts the whole write. That asymmetry is intentional:
     * this write path merges into a live route the engine itself owns, so failing loudly and
     * leaving the existing HTTPRoute untouched is safer than silently guessing at a rule's
     * intent and possibly dropping or misapplying it.
     */
    private GatewayPathMatch ruleMatch(HTTPRouteRule rule) {
        HTTPPathMatch path = rule.getMatches().get(0).getPath();
        return GatewayPathMatch.of(path.getType(), path.getValue());
    }

    private HTTPRouteRule buildRule(DeploymentRouteUpdate route, String backendName) {
        GatewayPathMatch pathMatch = GatewayPathMatch.forPath(baseRoutePrefix + route.getPath());

        HTTPRouteTimeouts timeouts = null;
        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            timeouts = HTTPRouteTimeouts.builder()
                    .request(GatewayDuration.formatMillis(route.getConnectTimeout()))
                    .build();
        }

        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type(pathMatch.getType()).value(pathMatch.getValue()).build())
                        .build()))
                .filters(Collections.emptyList())
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("")
                        .kind("Service")
                        .name(backendName)
                        .port(BACKEND_PORT)
                        .weight(1)
                        .build()))
                .timeouts(timeouts)
                .build();
    }

    private List<ParentReference> parentRefs(String gatewayName) {
        return List.of(ParentReference.builder()
                .group(GATEWAY_API_GROUP)
                .kind("Gateway")
                .name(gatewayName)
                .build());
    }

    private KubeCustomObjectRequest tierRequest(String cloudServiceName, String tier) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(cloudServiceName + "-chain-" + tier + "-routes");
        metadata.setNamespace(namespace);

        return KubeCustomObjectRequest.builder()
                .group(GATEWAY_API_GROUP)
                .version(GATEWAY_API_VERSION)
                .resourceNamePlural(HTTP_ROUTES_PLURAL)
                .body(KubeCustomObject.builder()
                        .apiVersion(GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION)
                        .kind("HTTPRoute")
                        .metadata(metadata)
                        .build())
                .build();
    }
}
