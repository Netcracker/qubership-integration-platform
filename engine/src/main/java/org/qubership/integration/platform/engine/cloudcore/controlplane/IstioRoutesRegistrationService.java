package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObject;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObjectRequest;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPBackendRef;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathModifier;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteFilter;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteRule;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteSpec;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteTimeouts;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPUrlRewriteFilter;
import org.qubership.integration.platform.engine.model.gatewayapi.ParentReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration.CAMEL_ROUTES_PREFIX;

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
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) {
        throw new UnsupportedOperationException(
                "Egress gateway route registration is not implemented for Istio Ambient Mesh yet; "
                        + "see docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md");
    }

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
            Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
            List<HTTPRouteRule> existingRules = current
                    .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                    .map(HTTPRouteSpec::getRules)
                    .filter(Objects::nonNull)
                    .orElse(List.of());

            Set<String> touchedPaths = givenRoutes.stream()
                    .map(route -> baseRoutePrefix + route.getPath())
                    .collect(Collectors.toSet());

            List<HTTPRouteRule> preservedRules = existingRules.stream()
                    .filter(rule -> !touchedPaths.contains(matchPath(rule)))
                    .toList();

            List<HTTPRouteRule> newRules = buildRules
                    ? givenRoutes.stream().map(route -> buildRule(route, backendName)).toList()
                    : List.of();

            List<HTTPRouteRule> mergedRules = new ArrayList<>(preservedRules);
            mergedRules.addAll(newRules);

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
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update Istio HTTPRoute for control plane routes: {}", e.getMessage());
            throw new ControlPlaneException("Failed to update Istio HTTPRoute for control plane routes", e);
        }
    }

    private String matchPath(HTTPRouteRule rule) {
        return rule.getMatches().get(0).getPath().getValue();
    }

    private HTTPRouteRule buildRule(DeploymentRouteUpdate route, String backendName) {
        String path = baseRoutePrefix + route.getPath();

        HTTPRouteTimeouts timeouts = null;
        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            timeouts = HTTPRouteTimeouts.builder()
                    .request(route.getConnectTimeout() + "ms")
                    .build();
        }

        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type("PathPrefix").value(path).build())
                        .build()))
                .filters(List.of(HTTPRouteFilter.builder()
                        .type("URLRewrite")
                        .urlRewrite(HTTPUrlRewriteFilter.builder()
                                .path(HTTPPathModifier.builder()
                                        .type("ReplacePrefixMatch")
                                        .replacePrefixMatch(CAMEL_ROUTES_PREFIX + route.getPath())
                                        .build())
                                .build())
                        .build()))
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
