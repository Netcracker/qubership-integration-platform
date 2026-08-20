package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathModifier;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteFilter;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteRule;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteSpec;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteTimeouts;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPUrlRewriteFilter;
import org.qubership.integration.platform.engine.model.gatewayapi.ParentReference;
import org.qubership.integration.platform.engine.util.GatewayDuration;
import org.qubership.integration.platform.engine.util.paths.EgressTarget;
import org.qubership.integration.platform.engine.util.paths.GatewayPathMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class IstioRoutesRegistrationService implements ControlPlaneService {

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final int BACKEND_PORT = 8080;

    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";
    private static final String SERVICE_ENTRIES_PLURAL = "serviceentries";
    private static final String DESTINATION_RULES_PLURAL = "destinationrules";

    private final KubeOperator kubeOperator;
    private final ObjectMapper objectMapper;
    private final String namespace;
    private final String baseRoutePrefix;
    private final String publicGatewayName;
    private final String privateGatewayName;
    private final String egressGatewayName;

    @Autowired
    public IstioRoutesRegistrationService(
            KubeOperator kubeOperator,
            ObjectMapper objectMapper,
            @Value("${cloud.microservice.namespace}") String namespace,
            @Value("${qip.chains.external-routes.base-path}") String baseRoutePrefix,
            @Value("${qip.gateway.public.name}") String publicGatewayName,
            @Value("${qip.gateway.private.name}") String privateGatewayName,
            @Value("${qip.gateway.egress.name}") String egressGatewayName
    ) {
        this.kubeOperator = kubeOperator;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
        this.baseRoutePrefix = baseRoutePrefix;
        this.publicGatewayName = publicGatewayName;
        this.privateGatewayName = privateGatewayName;
        this.egressGatewayName = egressGatewayName;
    }

    @Override
    public synchronized void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "public"), deploymentRoutes, publicGatewayName,
                this::triggerPathMatch, route -> buildTriggerRule(route, endpoint));
    }

    @Override
    public synchronized void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "private"), deploymentRoutes, privateGatewayName,
                this::triggerPathMatch, route -> buildTriggerRule(route, endpoint));
    }

    @Override
    public synchronized void removeEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName)
            throws ControlPlaneException {
        List<DeploymentRouteUpdate> publicRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPublicTriggerRoute(route.getType()))
                .toList();
        if (!publicRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "public"), publicRoutes, publicGatewayName,
                    this::triggerPathMatch, null);
        }

        List<DeploymentRouteUpdate> privateRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPrivateTriggerRoute(route.getType()))
                .toList();
        if (!privateRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "private"), privateRoutes, privateGatewayName,
                    this::triggerPathMatch, null);
        }
    }

    @Override
    public synchronized void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint)
            throws ControlPlaneException {
        try {
            // Dedupe by (host, port), not host alone: two routes can legitimately share a host on
            // different ports, and each such pair must reach upsertHostResources so its port gets
            // merged in -- deduping by host alone would silently drop every port but one, even
            // within this single call.
            routes.stream()
                    .map(route -> EgressTarget.parse(route.getPath()))
                    .collect(Collectors.toMap(
                            target -> target.host() + ":" + target.port(),
                            Function.identity(),
                            (first, second) -> first))
                    .values()
                    .forEach(this::upsertHostResources);
        } catch (KubeApiConflictException e) {
            throw new ControlPlaneException(
                    "Failed to update host-keyed egress resources after " + MAX_MERGE_ATTEMPTS + " attempts", e);
        }

        mergeTierRoutes(egressTierRequest(endpoint), routes, egressGatewayName,
                this::egressPathMatch, this::buildEgressRule);
    }

    private static final int MAX_MERGE_ATTEMPTS = 3;

    /**
     * Merges {@code givenRoutes} into the named gateway tier's HTTPRoute, retrying on a
     * concurrent-update conflict up to {@link #MAX_MERGE_ATTEMPTS} times.
     *
     * @param ruleBuilder builds the replacement {@link HTTPRouteRule} for each route whose path
     *                    matches {@code pathMatchExtractor}. Pass {@code null} for removal-only
     *                    mode: matching paths are stripped from the tier and no new rules are
     *                    added back. {@link #removeEngineRoutes} relies on this; every other
     *                    caller (the POST-style tiers) must pass a real builder.
     */
    private void mergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            Function<DeploymentRouteUpdate, GatewayPathMatch> pathMatchExtractor,
            Function<DeploymentRouteUpdate, HTTPRouteRule> ruleBuilder
    ) {
        if (givenRoutes.isEmpty()) {
            return;
        }
        try {
            for (int attempt = 1; attempt <= MAX_MERGE_ATTEMPTS; attempt++) {
                try {
                    attemptMergeTierRoutes(tierRequest, givenRoutes, gatewayName, pathMatchExtractor, ruleBuilder);
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
            Function<DeploymentRouteUpdate, GatewayPathMatch> pathMatchExtractor,
            Function<DeploymentRouteUpdate, HTTPRouteRule> ruleBuilder
    ) {
        Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
        List<HTTPRouteRule> existingRules = current
                .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                .map(HTTPRouteSpec::getRules)
                .filter(Objects::nonNull)
                .orElse(List.of());

        Set<GatewayPathMatch> touchedPaths = givenRoutes.stream()
                .map(pathMatchExtractor)
                .collect(Collectors.toSet());

        List<HTTPRouteRule> preservedRules = existingRules.stream()
                .filter(rule -> !touchedPaths.contains(ruleMatch(rule)))
                .toList();

        List<HTTPRouteRule> newRules = ruleBuilder == null
                ? List.of()
                : givenRoutes.stream().map(ruleBuilder).toList();

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
     * {@code integration-build-pipeline}'s sibling {@code HttpRouteRuleNormalizer} (which
     * preserves an unrecognized rule and logs a warning), a malformed rule here throws
     * (e.g. {@link IndexOutOfBoundsException} on an
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

    private GatewayPathMatch triggerPathMatch(DeploymentRouteUpdate route) {
        return GatewayPathMatch.forPath(baseRoutePrefix + route.getPath());
    }

    private GatewayPathMatch egressPathMatch(DeploymentRouteUpdate route) {
        return GatewayPathMatch.forPath(route.getGatewayPrefix());
    }

    private HTTPRouteRule buildTriggerRule(DeploymentRouteUpdate route, String backendName) {
        GatewayPathMatch pathMatch = triggerPathMatch(route);

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

    private HTTPRouteRule buildEgressRule(DeploymentRouteUpdate route) {
        EgressTarget target = EgressTarget.parse(route.getPath());
        GatewayPathMatch pathMatch = egressPathMatch(route);

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
                .filters(List.of(HTTPRouteFilter.builder()
                        .type("URLRewrite")
                        .urlRewrite(HTTPUrlRewriteFilter.builder()
                                .hostname(target.host())
                                .path(HTTPPathModifier.builder()
                                        .type("ReplacePrefixMatch")
                                        .replacePrefixMatch(target.path())
                                        .build())
                                .build())
                        .build()))
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group(NETWORKING_ISTIO_API_GROUP)
                        .kind("Hostname")
                        .name(target.host())
                        .port(target.port())
                        .weight(1)
                        .build()))
                .timeouts(timeouts)
                .build();
    }

    /**
     * Creates or updates the {@code ServiceEntry} (and, for an https target, the
     * {@code DestinationRule}) that registers {@code target}'s host with the mesh. Both are named
     * deterministically from the host alone ({@link EgressTarget#hostResourceName()}), so every
     * route that targets the same external host -- across every chain this namespace hosts --
     * converges on the same pair of objects. Different routes can legitimately target the same
     * host on different ports, so the write is a read-merge-write keyed on {@code port.number}
     * (via {@link #upsertHostResource}), not a blind overwrite -- a blind overwrite would let
     * whichever chain deploys last silently erase every other chain's port entry for that host.
     * Never deleted; see the design spec's cleanup non-goal (ports are only ever added or updated,
     * never removed, even when their contributing chain undeploys).
     */
    private void upsertHostResources(EgressTarget target) {
        String name = target.hostResourceName();
        upsertServiceEntry(name, target);
        if (target.isHttps()) {
            upsertDestinationRule(name, target);
        }
    }

    private void upsertServiceEntry(String name, EgressTarget target) {
        ObjectNode newPort = objectMapper.createObjectNode();
        newPort.put("number", target.port());
        newPort.put("name", target.isHttps() ? "https" : "http");
        newPort.put("protocol", target.isHttps() ? "HTTPS" : "HTTP");

        upsertHostResource(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, SERVICE_ENTRIES_PLURAL,
                "ServiceEntry", name, existingSpec -> {
                    ObjectNode spec = objectMapper.createObjectNode();
                    spec.putArray("hosts").add(target.host());
                    spec.put("location", "MESH_EXTERNAL");
                    spec.put("resolution", "DNS");
                    ArrayNode ports = spec.putArray("ports");
                    mergedEntries(existingSpec.path("ports"), entry -> entry.path("number").asInt(), newPort)
                            .forEach(ports::add);
                    return spec;
                });
    }

    private void upsertDestinationRule(String name, EgressTarget target) {
        ObjectNode newPortLevelSetting = objectMapper.createObjectNode();
        newPortLevelSetting.putObject("port").put("number", target.port());
        ObjectNode tls = newPortLevelSetting.putObject("tls");
        tls.put("mode", "SIMPLE");
        tls.put("sni", target.host());

        upsertHostResource(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, DESTINATION_RULES_PLURAL,
                "DestinationRule", name, existingSpec -> {
                    ObjectNode spec = objectMapper.createObjectNode();
                    spec.put("host", target.host());
                    ArrayNode portLevelSettings = spec.putObject("trafficPolicy").putArray("portLevelSettings");
                    JsonNode existingPortLevelSettings = existingSpec.path("trafficPolicy").path("portLevelSettings");
                    mergedEntries(existingPortLevelSettings,
                            entry -> entry.path("port").path("number").asInt(), newPortLevelSetting)
                            .forEach(portLevelSettings::add);
                    return spec;
                });
    }

    /**
     * Returns {@code existingList}'s entries with any entry sharing {@code newEntry}'s key (per
     * {@code keyExtractor}) removed, plus {@code newEntry} appended -- i.e. add-or-replace-by-key,
     * never duplicate. {@code existingList} may be a Jackson {@code MissingNode} (absent field);
     * that's treated as an empty list, not an error.
     */
    private List<JsonNode> mergedEntries(
            JsonNode existingList, Function<JsonNode, Integer> keyExtractor, JsonNode newEntry
    ) {
        int newKey = keyExtractor.apply(newEntry);
        List<JsonNode> merged = new ArrayList<>();
        if (existingList.isArray()) {
            for (JsonNode entry : existingList) {
                if (!keyExtractor.apply(entry).equals(newKey)) {
                    merged.add(entry);
                }
            }
        }
        merged.add(newEntry);
        return merged;
    }

    /**
     * Creates or updates a host-keyed egress resource ({@code ServiceEntry}/{@code DestinationRule})
     * by reading its current spec (if any), letting {@code specMerger} fold the new content into it,
     * and writing the result back with optimistic-concurrency retry -- the same read-merge-write-
     * with-retry shape {@link #mergeTierRoutes} uses for the shared HTTPRoute tiers, applied here
     * because these resources are shared across chains too and must not lose another chain's port
     * entry to a concurrent write.
     */
    private void upsertHostResource(
            String group, String version, String plural, String kind, String name,
            Function<JsonNode, ObjectNode> specMerger
    ) {
        for (int attempt = 1; attempt <= MAX_MERGE_ATTEMPTS; attempt++) {
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(name);
            metadata.setNamespace(namespace);

            KubeCustomObjectRequest request = KubeCustomObjectRequest.builder()
                    .group(group)
                    .version(version)
                    .resourceNamePlural(plural)
                    .body(KubeCustomObject.builder()
                            .apiVersion(group + "/" + version)
                            .kind(kind)
                            .metadata(metadata)
                            .build())
                    .build();

            Optional<KubeCustomObject> existing = kubeOperator.getCustomObject(request);
            JsonNode existingSpec = existing
                    .map(obj -> (JsonNode) objectMapper.valueToTree(obj.getSpec()))
                    .orElseGet(objectMapper::createObjectNode);

            ObjectNode mergedSpec = specMerger.apply(existingSpec);
            request.getBody().setSpec(objectMapper.convertValue(mergedSpec, new TypeReference<Map<String, Object>>() {}));
            request.getBody().getMetadata().setResourceVersion(
                    existing.map(obj -> obj.getMetadata().getResourceVersion()).orElse(null));

            try {
                kubeOperator.createOrReplaceCustomObject(request);
                return;
            } catch (KubeApiConflictException e) {
                if (attempt == MAX_MERGE_ATTEMPTS) {
                    throw e;
                }
                log.warn("Concurrent update detected for {} '{}' on attempt {}/{}, retrying",
                        kind, name, attempt, MAX_MERGE_ATTEMPTS);
            }
        }
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

    private KubeCustomObjectRequest egressTierRequest(String cloudServiceName) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(cloudServiceName + "-egress-routes");
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
