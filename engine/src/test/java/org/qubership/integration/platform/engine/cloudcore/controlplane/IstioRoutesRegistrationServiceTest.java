package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
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
import org.qubership.integration.platform.engine.model.gatewayapi.ParentReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class IstioRoutesRegistrationServiceTest {

    private static final String NAMESPACE = "qip";
    private static final String BASE_PATH = "/api/v1";
    private static final String CLOUD_SERVICE_NAME = "engine-service";

    private KubeOperator kubeOperator;
    private IstioRoutesRegistrationService service;

    @BeforeEach
    void setUp() {
        kubeOperator = mock(KubeOperator.class);
        service = new IstioRoutesRegistrationService(kubeOperator, new ObjectMapper(), NAMESPACE, BASE_PATH,
                "public-gateway", "private-gateway", "egress-gateway", true);
    }

    @Test
    void postPublicEngineRoutesCreatesCrWhenNoneExists() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route = route("/chain-a", RouteType.EXTERNAL_TRIGGER, 5000L);

        service.postPublicEngineRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        verify(kubeOperator, never()).deleteCustomObject(any());

        KubeCustomObjectRequest request = captor.getValue();
        assertEquals("gateway.networking.k8s.io", request.getGroup());
        assertEquals("v1", request.getVersion());
        assertEquals("httproutes", request.getResourceNamePlural());
        assertEquals(CLOUD_SERVICE_NAME + "-chain-public-routes", request.getBody().getMetadata().getName());
        assertEquals(NAMESPACE, request.getBody().getMetadata().getNamespace());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(request.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getParentRefs().size());
        assertEquals("public-gateway", spec.getParentRefs().get(0).getName());
        assertEquals(1, spec.getRules().size());
        assertEquals(BASE_PATH + "/chain-a", spec.getRules().get(0).getMatches().get(0).getPath().getValue());
        assertEquals(CLOUD_SERVICE_NAME, spec.getRules().get(0).getBackendRefs().get(0).getName());
        assertEquals("5000ms", spec.getRules().get(0).getTimeouts().getRequest());
    }

    // Gateway API's HTTPRoute CRD rejects spec.rules[].timeouts.request unless every unit run in
    // it is 1-5 digits; the default connectTimeout (120000ms) has six digits, so a plain
    // "<millis>ms" suffix produced an unschedulable CR. GatewayDuration decomposes it into "2m"
    // instead.
    @Test
    void postPublicEngineRoutesFormatsATimeoutAboveTheMillisDigitLimitIntoLargerUnits() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route = route("/chain-a", RouteType.EXTERNAL_TRIGGER, 120_000L);

        service.postPublicEngineRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals("2m", spec.getRules().get(0).getTimeouts().getRequest());
    }

    @Test
    void postPublicEngineRoutesCreatesRegularExpressionMatchForPlaceholderPath() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route = route("/chain-a/{id}", RouteType.EXTERNAL_TRIGGER, null);

        service.postPublicEngineRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        HTTPPathMatch pathMatch = spec.getRules().get(0).getMatches().get(0).getPath();
        assertEquals("RegularExpression", pathMatch.getType());
        assertEquals(BASE_PATH + "/chain-a/[^/]+/?", pathMatch.getValue());
    }

    @Test
    void postPublicEngineRoutesReplacesOwnStaleRegularExpressionRuleInsteadOfDuplicating() {
        HTTPRouteRule staleRule = rule("RegularExpression", BASE_PATH + "/chain-a/[^/]+/?", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(staleRule))));

        service.postPublicEngineRoutes(
                List.of(route("/chain-a/{id}", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getRules().size());
    }

    @Test
    void postPublicEngineRoutesPreservesOtherChainsRules() {
        HTTPRouteRule otherChainRule = rule("/chain-b", "other-service");
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(otherChainRule))));

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(2, spec.getRules().size());
        List<String> paths = spec.getRules().stream()
                .map(r -> r.getMatches().get(0).getPath().getValue())
                .toList();
        assertTrue(paths.contains(BASE_PATH + "/chain-b"));
        assertTrue(paths.contains(BASE_PATH + "/chain-a"));
    }

    @Test
    void postPublicEngineRoutesReplacesOwnStaleRuleInsteadOfDuplicating() {
        HTTPRouteRule staleRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(staleRule))));

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getRules().size());
    }

    @Test
    void postPublicEngineRoutesWithEmptyListAndOtherChainsPresentDoesNothing() {
        service.postPublicEngineRoutes(List.of(), CLOUD_SERVICE_NAME);

        verify(kubeOperator, never()).getCustomObject(any());
        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
        verify(kubeOperator, never()).deleteCustomObject(any());
    }

    @Test
    void postPrivateEngineRoutesTargetsPrivateGatewayAndCr() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        service.postPrivateEngineRoutes(List.of(route("/chain-a", RouteType.PRIVATE_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        KubeCustomObjectRequest request = captor.getValue();
        assertEquals(CLOUD_SERVICE_NAME + "-chain-private-routes", request.getBody().getMetadata().getName());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(request.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals("private-gateway", spec.getParentRefs().get(0).getName());
    }

    @Test
    void removeEngineRoutesDoesNothingWhenCrDoesNotExist() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.removeEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
        verify(kubeOperator, never()).deleteCustomObject(any());
    }

    @Test
    void removeEngineRoutesDeletesCrWhenItBecomesEmpty() {
        HTTPRouteRule onlyRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(onlyRule))));

        service.removeEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        verify(kubeOperator).deleteCustomObject(any());
        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
    }

    @Test
    void removeEngineRoutesRetriesOnceOnConflictDuringDeleteThenSucceeds() {
        HTTPRouteRule onlyRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(onlyRule))));
        doThrow(new KubeApiConflictException("conflict"))
                .doNothing()
                .when(kubeOperator).deleteCustomObject(any());

        assertDoesNotThrow(() -> service.removeEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(2)).getCustomObject(any());
        verify(kubeOperator, times(2)).deleteCustomObject(any());
    }

    @Test
    void removeEngineRoutesLeavesOtherChainsRulesInPlace() {
        HTTPRouteRule ownRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        HTTPRouteRule otherChainRule = rule("/chain-b", "other-service");
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(ownRule, otherChainRule))));

        service.removeEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        verify(kubeOperator, never()).deleteCustomObject(any());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getRules().size());
        assertEquals(BASE_PATH + "/chain-b", spec.getRules().get(0).getMatches().get(0).getPath().getValue());
    }

    @Test
    void removeEngineRoutesSplitsByTierUsingGivenType() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate publicRoute = route("/chain-a", RouteType.EXTERNAL_TRIGGER, null);
        DeploymentRouteUpdate privateRoute = route("/chain-b", RouteType.PRIVATE_TRIGGER, null);

        service.removeEngineRoutes(List.of(publicRoute, privateRoute), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(2)).getCustomObject(captor.capture());
        List<String> requestedNames = captor.getAllValues().stream()
                .map(r -> r.getBody().getMetadata().getName())
                .toList();
        assertTrue(requestedNames.contains(CLOUD_SERVICE_NAME + "-chain-public-routes"));
        assertTrue(requestedNames.contains(CLOUD_SERVICE_NAME + "-chain-private-routes"));
    }

    @Test
    void postEgressGatewayRoutesCreatesHttpRouteServiceEntryAndDestinationRuleForHttpsTarget() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);

        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(captor.capture());
        List<KubeCustomObjectRequest> requests = captor.getAllValues();

        KubeCustomObjectRequest serviceEntryRequest = requests.stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        KubeCustomObjectRequest destinationRuleRequest = requests.stream()
                .filter(r -> "destinationrules".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        KubeCustomObjectRequest httpRouteRequest = requests.stream()
                .filter(r -> "httproutes".equals(r.getResourceNamePlural())).findFirst().orElseThrow();

        assertEquals("networking.istio.io", serviceEntryRequest.getGroup());
        assertEquals(List.of("api.example.com"), serviceEntryRequest.getBody().getSpec().get("hosts"));
        assertEquals("MESH_EXTERNAL", serviceEntryRequest.getBody().getSpec().get("location"));

        assertEquals("networking.istio.io", destinationRuleRequest.getGroup());
        assertEquals("api.example.com", destinationRuleRequest.getBody().getSpec().get("host"));

        assertEquals(CLOUD_SERVICE_NAME + "-egress-routes", httpRouteRequest.getBody().getMetadata().getName());
        HTTPRouteSpec spec = new ObjectMapper().convertValue(httpRouteRequest.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals("egress-gateway", spec.getParentRefs().get(0).getName());
        assertEquals("PathPrefix", spec.getRules().get(0).getMatches().get(0).getPath().getType());
        assertEquals("/system/service-a", spec.getRules().get(0).getMatches().get(0).getPath().getValue());
        assertEquals("networking.istio.io", spec.getRules().get(0).getBackendRefs().get(0).getGroup());
        assertEquals("Hostname", spec.getRules().get(0).getBackendRefs().get(0).getKind());
        assertEquals("api.example.com", spec.getRules().get(0).getBackendRefs().get(0).getName());
        assertEquals(443, spec.getRules().get(0).getBackendRefs().get(0).getPort());
        assertEquals("api.example.com", spec.getRules().get(0).getFilters().get(0).getUrlRewrite().getHostname());
        assertEquals("/v2", spec.getRules().get(0).getFilters().get(0).getUrlRewrite().getPath().getReplacePrefixMatch());
        assertEquals("ReplacePrefixMatch", spec.getRules().get(0).getFilters().get(0).getUrlRewrite().getPath().getType());
    }

    @Test
    void postEgressGatewayRoutesSkipsDestinationRuleForHttpTarget() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route =
                egressRoute("http://backend:9090", "/http-sender/elem-1/abc", RouteType.EXTERNAL_SENDER);

        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(2)).createOrReplaceCustomObject(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(r -> "destinationrules".equals(r.getResourceNamePlural())));
    }

    @Test
    void postEgressGatewayRoutesConvergesTwoRoutesSharingAHostOnOneServiceEntry() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate routeA =
                egressRoute("https://api.example.com/a", "/system/elem-a", RouteType.EXTERNAL_SERVICE);
        DeploymentRouteUpdate routeB =
                egressRoute("https://api.example.com/b", "/system/elem-b", RouteType.EXTERNAL_SERVICE);

        service.postEgressGatewayRoutes(List.of(routeA, routeB), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        // 1 ServiceEntry + 1 DestinationRule (one unique host) + 1 HTTPRoute (two rules) = 3 calls
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(captor.capture());
        long serviceEntryCalls = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).count();
        assertEquals(1, serviceEntryCalls);
    }

    @Test
    void postEgressGatewayRoutesPreservesOtherChainsRulesInTheSharedEgressCr() {
        HTTPRouteRule existingRule = HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type("PathPrefix").value("/system/other-elem").build())
                        .build()))
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("networking.istio.io").kind("Hostname").name("other.example.com").port(443).weight(1)
                        .build()))
                .build();
        HTTPRouteSpec existingSpec = HTTPRouteSpec.builder()
                .parentRefs(List.of(ParentReference.builder()
                        .group("gateway.networking.k8s.io").kind("Gateway").name("egress-gateway").build()))
                .rules(List.of(existingRule))
                .build();
        KubeCustomObject existing = KubeCustomObject.builder()
                .metadata(metadataWithVersion(CLOUD_SERVICE_NAME + "-egress-routes", "5"))
                .spec(new ObjectMapper().convertValue(existingSpec, new TypeReference<Map<String, Object>>() {}))
                .build();
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existing));

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest httpRouteRequest = captor.getAllValues().stream()
                .filter(r -> "httproutes".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        HTTPRouteSpec spec = new ObjectMapper().convertValue(httpRouteRequest.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(2, spec.getRules().size());
        assertTrue(spec.getRules().stream()
                .anyMatch(r -> "/system/other-elem".equals(r.getMatches().get(0).getPath().getValue())));
        assertTrue(spec.getRules().stream()
                .anyMatch(r -> "/system/service-a".equals(r.getMatches().get(0).getPath().getValue())));
    }

    @Test
    void upsertHostResourceRetriesOnceOnConflictThenSucceeds() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict"))
                .doNothing()
                .when(kubeOperator)
                .createOrReplaceCustomObject(argThat(r -> "serviceentries".equals(r.getResourceNamePlural())));
        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);

        assertDoesNotThrow(() -> service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME));
    }

    @Test
    void postEgressGatewayRoutesGivesUpAfterThreeConflictsOnAHostResourceAndWrapsAsControlPlaneException() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict"))
                .when(kubeOperator)
                .createOrReplaceCustomObject(argThat(r -> "serviceentries".equals(r.getResourceNamePlural())));
        List<DeploymentRouteUpdate> routes = List.of(
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE));

        assertThrows(ControlPlaneException.class,
                () -> service.postEgressGatewayRoutes(routes, CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(3)).createOrReplaceCustomObject(
                argThat(r -> "serviceentries".equals(r.getResourceNamePlural())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesMergesANewPortIntoAnExistingServiceEntryForTheSameHost() {
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingServiceEntry(port(8443, "https", "HTTPS"))));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest serviceEntryRequest = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        List<Map<String, Object>> ports = (List<Map<String, Object>>) serviceEntryRequest.getBody().getSpec().get("ports");
        Set<Integer> portNumbers = ports.stream().map(p -> (Integer) p.get("number")).collect(Collectors.toSet());
        assertEquals(Set.of(8443, 9443), portNumbers);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesGivesEveryPortOnAHostItsOwnName() {
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingServiceEntry(port(443, "https-443", "HTTPS"))));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest serviceEntryRequest = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        List<Map<String, Object>> ports = (List<Map<String, Object>>) serviceEntryRequest.getBody().getSpec().get("ports");
        List<String> names = ports.stream().map(p -> (String) p.get("name")).sorted().toList();
        assertEquals(List.of("https-443", "https-9443"), names);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesReplacesAnExistingPortWithTheSameNumberInsteadOfDuplicating() {
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingServiceEntry(port(443, "https", "HTTPS"))));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest serviceEntryRequest = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        List<Map<String, Object>> ports = (List<Map<String, Object>>) serviceEntryRequest.getBody().getSpec().get("ports");
        assertEquals(1, ports.size());
        assertEquals(443, ports.get(0).get("number"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesMergesANewPortLevelSettingIntoAnExistingDestinationRuleForTheSameHost() {
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingDestinationRule(portLevelSetting(8443))));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest destinationRuleRequest = captor.getAllValues().stream()
                .filter(r -> "destinationrules".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        Map<String, Object> trafficPolicy =
                (Map<String, Object>) destinationRuleRequest.getBody().getSpec().get("trafficPolicy");
        List<Map<String, Object>> portLevelSettings = (List<Map<String, Object>>) trafficPolicy.get("portLevelSettings");
        Set<Integer> portNumbers = portLevelSettings.stream()
                .map(pls -> (Integer) ((Map<String, Object>) pls.get("port")).get("number"))
                .collect(Collectors.toSet());
        assertEquals(Set.of(8443, 9443), portNumbers);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesPreservesDestinationRuleFieldsItDoesNotManage() {
        KubeCustomObject existing = existingDestinationRule(portLevelSetting(8443));
        Map<String, Object> trafficPolicy = new LinkedHashMap<>(
                (Map<String, Object>) existing.getSpec().get("trafficPolicy"));
        trafficPolicy.put("tls", Map.of("mode", "MUTUAL", "credentialName", "operator-client-cert"));
        trafficPolicy.put("connectionPool", Map.of("http", Map.of("http2MaxRequests", 200)));
        existing.getSpec().put("trafficPolicy", trafficPolicy);
        existing.getSpec().put("exportTo", List.of("."));
        existing.getSpec().put("subsets", List.of(Map.of("name", "operator-subset")));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existing));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        Map<String, Object> writtenSpec = captor.getAllValues().stream()
                .filter(r -> "destinationrules".equals(r.getResourceNamePlural())).findFirst().orElseThrow()
                .getBody().getSpec();

        assertEquals(List.of("."), writtenSpec.get("exportTo"));
        assertEquals(List.of(Map.of("name", "operator-subset")), writtenSpec.get("subsets"));
        Map<String, Object> writtenTrafficPolicy = (Map<String, Object>) writtenSpec.get("trafficPolicy");
        assertEquals(Map.of("mode", "MUTUAL", "credentialName", "operator-client-cert"),
                writtenTrafficPolicy.get("tls"));
        assertEquals(Map.of("http", Map.of("http2MaxRequests", 200)), writtenTrafficPolicy.get("connectionPool"));
        // The managed list is replaced, not appended to: merging into a copy that already holds the
        // seeded 8443 setting must not carry it twice.
        List<Map<String, Object>> portLevelSettings =
                (List<Map<String, Object>>) writtenTrafficPolicy.get("portLevelSettings");
        assertEquals(2, portLevelSettings.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesPreservesServiceEntryFieldsItDoesNotManage() {
        KubeCustomObject existing = existingServiceEntry(port(8443, "https-8443", "HTTPS"));
        existing.getSpec().put("exportTo", List.of("."));
        existing.getSpec().put("workloadSelector", Map.of("labels", Map.of("app", "operator-owned")));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existing));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        Map<String, Object> writtenSpec = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow()
                .getBody().getSpec();

        assertEquals(List.of("."), writtenSpec.get("exportTo"));
        assertEquals(Map.of("labels", Map.of("app", "operator-owned")), writtenSpec.get("workloadSelector"));
        // hosts is replaced, not appended to, so the host is listed once.
        assertEquals(List.of("api.example.com"), writtenSpec.get("hosts"));
        assertEquals(2, ((List<Map<String, Object>>) writtenSpec.get("ports")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesFoldsANewTlsSettingIntoTheExistingEntryForTheSamePort() {
        // Port 443 is the port the route below targets, so this entry collides with the generated one.
        Map<String, Object> collidingEntry = new LinkedHashMap<>();
        collidingEntry.put("port", Map.of("number", 443));
        collidingEntry.put("tls", new LinkedHashMap<>(Map.of(
                "mode", "MUTUAL",
                "credentialName", "operator-client-cert",
                "subjectAltNames", List.of("spiffe://api.example.com"))));
        collidingEntry.put("connectionPool", Map.of("tcp", Map.of("maxConnections", 50)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingDestinationRule(collidingEntry)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        Map<String, Object> trafficPolicy = (Map<String, Object>) captor.getAllValues().stream()
                .filter(r -> "destinationrules".equals(r.getResourceNamePlural())).findFirst().orElseThrow()
                .getBody().getSpec().get("trafficPolicy");
        List<Map<String, Object>> portLevelSettings =
                (List<Map<String, Object>>) trafficPolicy.get("portLevelSettings");

        assertEquals(1, portLevelSettings.size());
        Map<String, Object> written = portLevelSettings.get(0);
        assertEquals(Map.of("tcp", Map.of("maxConnections", 50)), written.get("connectionPool"));
        Map<String, Object> tls = (Map<String, Object>) written.get("tls");
        assertEquals("operator-client-cert", tls.get("credentialName"));
        assertEquals(List.of("spiffe://api.example.com"), tls.get("subjectAltNames"));
        // tls.mode is seeded on creation and left alone afterwards, so the operator's choice stands.
        assertEquals("MUTUAL", tls.get("mode"));
        // sni stays owned by the service, so it is rewritten on every deploy.
        assertEquals("api.example.com", tls.get("sni"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesFoldsANewPortIntoTheExistingServiceEntryPortWithTheSameNumber() {
        Map<String, Object> collidingPort = port(443, "stale-name", "HTTPS");
        collidingPort.put("targetPort", 8443);
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingServiceEntry(collidingPort)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        List<Map<String, Object>> ports = (List<Map<String, Object>>) captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow()
                .getBody().getSpec().get("ports");

        assertEquals(1, ports.size());
        assertEquals(8443, ports.get(0).get("targetPort"));
        // name is a field the service owns, so its value replaces the stale one.
        assertEquals("https-443", ports.get(0).get("name"));
    }

    @Test
    void postEgressGatewayRoutesKeepsAnExistingResolution() {
        KubeCustomObject existing = existingServiceEntry(port(443, "https-443", "HTTPS"));
        existing.getSpec().put("resolution", "STATIC");
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existing));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        assertEquals("STATIC", writtenSpec("serviceentries").get("resolution"));
    }

    @Test
    void postEgressGatewayRoutesSetsLocationAndResolutionWhenTheServiceEntryDoesNotExistYet() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        assertEquals("MESH_EXTERNAL", writtenSpec("serviceentries").get("location"));
        assertEquals("DNS", writtenSpec("serviceentries").get("resolution"));
    }

    @Test
    void postEgressGatewayRoutesKeepsAnExistingLocation() {
        KubeCustomObject existing = existingServiceEntry(port(443, "https-443", "HTTPS"));
        existing.getSpec().put("location", "MESH_INTERNAL");
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existing));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        assertEquals("MESH_INTERNAL", writtenSpec("serviceentries").get("location"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesRewritesADisabledTlsMode() {
        Map<String, Object> disabledEntry = new LinkedHashMap<>();
        disabledEntry.put("port", Map.of("number", 443));
        disabledEntry.put("tls", new LinkedHashMap<>(Map.of(
                "mode", "DISABLE", "credentialName", "operator-client-cert")));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingDestinationRule(disabledEntry)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        Map<String, Object> tls = (Map<String, Object>) portLevelSettingFor(443).get("tls");
        // DISABLE would leave the gateway sending cleartext at an HTTPS port, so it is the one mode
        // the service overrules.
        assertEquals("SIMPLE", tls.get("mode"));
        // Overruling the mode does not cost the operator anything else on that entry.
        assertEquals("operator-client-cert", tls.get("credentialName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesLeavesADisabledTlsModeOnAPortItDoesNotManage() {
        Map<String, Object> disabledEntry = new LinkedHashMap<>();
        disabledEntry.put("port", Map.of("number", 443));
        disabledEntry.put("tls", Map.of("mode", "DISABLE"));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingDestinationRule(disabledEntry)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        assertEquals("DISABLE", ((Map<String, Object>) portLevelSettingFor(443).get("tls")).get("mode"));
        assertEquals("SIMPLE", ((Map<String, Object>) portLevelSettingFor(9443).get("tls")).get("mode"));
    }

    // The default tls.mode is folded into a copy of the live entry, never into the node the service
    // reuses across conflict retries. Aliasing it would promote the default to an owned field after
    // the first attempt, so the second attempt would overwrite an operator's MUTUAL with SIMPLE.
    // Only a retry that reads different state each time can catch that, which is what this sets up.
    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesDoesNotCarryTheDefaultTlsModeIntoARetry() {
        Map<String, Object> entryWithoutTls = new LinkedHashMap<>();
        entryWithoutTls.put("port", Map.of("number", 443));
        entryWithoutTls.put("connectionPool", Map.of("tcp", Map.of("maxConnections", 50)));

        Map<String, Object> entryWithMutualTls = new LinkedHashMap<>();
        entryWithMutualTls.put("port", Map.of("number", 443));
        entryWithMutualTls.put("tls", new LinkedHashMap<>(Map.of(
                "mode", "MUTUAL", "credentialName", "operator-client-cert")));

        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingDestinationRule(entryWithoutTls)))
                .thenReturn(Optional.of(existingDestinationRule(entryWithMutualTls)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict")).doNothing()
                .when(kubeOperator)
                .createOrReplaceCustomObject(argThat(r -> "destinationrules".equals(r.getResourceNamePlural())));

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        Map<String, Object> lastWrite = captor.getAllValues().stream()
                .filter(r -> "destinationrules".equals(r.getResourceNamePlural()))
                .reduce((first, second) -> second).orElseThrow()
                .getBody().getSpec();
        Map<String, Object> trafficPolicy = (Map<String, Object>) lastWrite.get("trafficPolicy");
        Map<String, Object> tls = (Map<String, Object>)
                ((List<Map<String, Object>>) trafficPolicy.get("portLevelSettings")).get(0).get("tls");

        assertEquals("MUTUAL", tls.get("mode"));
        assertEquals("operator-client-cert", tls.get("credentialName"));
    }

    /** The port-level setting for {@code port} in the first DestinationRule write this test made. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> portLevelSettingFor(int port) {
        Map<String, Object> trafficPolicy = (Map<String, Object>) writtenSpec("destinationrules").get("trafficPolicy");
        return ((List<Map<String, Object>>) trafficPolicy.get("portLevelSettings")).stream()
                .filter(setting -> Integer.valueOf(port)
                        .equals(((Map<String, Object>) setting.get("port")).get("number")))
                .findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesSetsTlsModeWhenTheExistingEntryHasNone() {
        Map<String, Object> entryWithoutMode = new LinkedHashMap<>();
        entryWithoutMode.put("port", Map.of("number", 443));
        entryWithoutMode.put("tls", Map.of("credentialName", "operator-client-cert"));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingDestinationRule(entryWithoutMode)));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"destinationrules".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        service.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        Map<String, Object> trafficPolicy =
                (Map<String, Object>) writtenSpec("destinationrules").get("trafficPolicy");
        Map<String, Object> tls = (Map<String, Object>)
                ((List<Map<String, Object>>) trafficPolicy.get("portLevelSettings")).get(0).get("tls");

        assertEquals("SIMPLE", tls.get("mode"));
        assertEquals("operator-client-cert", tls.get("credentialName"));
    }

    /** The spec of the first write this test made to {@code plural}. */
    private Map<String, Object> writtenSpec(String plural) {
        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        return captor.getAllValues().stream()
                .filter(r -> plural.equals(r.getResourceNamePlural())).findFirst().orElseThrow()
                .getBody().getSpec();
    }

    @Test
    void postEgressGatewayRoutesSkipsHostResourcesWhenTheyAreDisabled() {
        IstioRoutesRegistrationService withoutHostResources = new IstioRoutesRegistrationService(
                kubeOperator, new ObjectMapper(), NAMESPACE, BASE_PATH,
                "public-gateway", "private-gateway", "egress-gateway", false);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        withoutHostResources.postEgressGatewayRoutes(List.of(
                        egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE)),
                CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        List<String> written = captor.getAllValues().stream()
                .map(KubeCustomObjectRequest::getResourceNamePlural)
                .toList();

        // The egress HTTPRoute still goes out; only the Istio host resources are left to whoever
        // owns them when the flag is off.
        assertEquals(List.of("httproutes"), written);
    }

    private Map<String, Object> port(int number, String name, String protocol) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("number", number);
        port.put("name", name);
        port.put("protocol", protocol);
        return port;
    }

    private Map<String, Object> portLevelSetting(int number) {
        Map<String, Object> portLevelSetting = new LinkedHashMap<>();
        portLevelSetting.put("port", Map.of("number", number));
        portLevelSetting.put("tls", Map.of("mode", "SIMPLE", "sni", "api.example.com"));
        return portLevelSetting;
    }

    private KubeCustomObject existingServiceEntry(Map<String, Object> existingPort) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("hosts", List.of("api.example.com"));
        spec.put("location", "MESH_EXTERNAL");
        spec.put("resolution", "DNS");
        spec.put("ports", List.of(existingPort));
        return KubeCustomObject.builder()
                .apiVersion("networking.istio.io/v1")
                .kind("ServiceEntry")
                .metadata(metadataWithVersion("api-example-com-hash", "1"))
                .spec(spec)
                .build();
    }

    private KubeCustomObject existingDestinationRule(Map<String, Object> existingPortLevelSetting) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("host", "api.example.com");
        spec.put("trafficPolicy", Map.of("portLevelSettings", List.of(existingPortLevelSetting)));
        return KubeCustomObject.builder()
                .apiVersion("networking.istio.io/v1")
                .kind("DestinationRule")
                .metadata(metadataWithVersion("api-example-com-hash", "1"))
                .spec(spec)
                .build();
    }

    private V1ObjectMeta metadataWithVersion(String name, String resourceVersion) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(name);
        metadata.setResourceVersion(resourceVersion);
        return metadata;
    }

    @Test
    void postPublicEngineRoutesWrapsMalformedExistingRuleAsControlPlaneException() {
        HTTPRouteRule ruleWithNoMatches = HTTPRouteRule.builder()
                .matches(List.of())
                .filters(List.of())
                .backendRefs(List.of())
                .build();
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(ruleWithNoMatches))));
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null));

        ControlPlaneException exception = assertThrows(ControlPlaneException.class, () ->
                service.postPublicEngineRoutes(routes, CLOUD_SERVICE_NAME));

        assertInstanceOf(IndexOutOfBoundsException.class, exception.getCause());
        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
        verify(kubeOperator, never()).deleteCustomObject(any());
    }

    @Test
    void postPublicEngineRoutesCarriesTheObservedResourceVersionIntoTheWrite() {
        KubeCustomObject existing = existingCr(List.of());
        existing.getMetadata().setResourceVersion("42");
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existing));

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        assertEquals("42", captor.getValue().getBody().getMetadata().getResourceVersion());
    }

    @Test
    void postPublicEngineRoutesLeavesResourceVersionNullWhenNoCrExists() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        assertNull(captor.getValue().getBody().getMetadata().getResourceVersion());
    }

    @Test
    void postPublicEngineRoutesRetriesOnceOnConflictThenSucceeds() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict"))
                .doNothing()
                .when(kubeOperator).createOrReplaceCustomObject(any());

        assertDoesNotThrow(() -> service.postPublicEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(2)).getCustomObject(any());
        verify(kubeOperator, times(2)).createOrReplaceCustomObject(any());
    }

    @Test
    void postPublicEngineRoutesGivesUpAfterThreeConflictsAndWrapsAsControlPlaneException() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict")).when(kubeOperator).createOrReplaceCustomObject(any());
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null));

        assertThrows(ControlPlaneException.class,
                () -> service.postPublicEngineRoutes(routes, CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(3)).getCustomObject(any());
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(any());
    }

    private DeploymentRouteUpdate route(String path, RouteType type, Long connectTimeout) {
        DeploymentRouteUpdate.DeploymentRouteUpdateBuilder builder = DeploymentRouteUpdate.builder()
                .path(path)
                .type(type);
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        return builder.build();
    }

    private DeploymentRouteUpdate egressRoute(String targetUrl, String gatewayPrefix, RouteType type) {
        return DeploymentRouteUpdate.builder()
                .path(targetUrl)
                .gatewayPrefix(gatewayPrefix)
                .type(type)
                .build();
    }

    private HTTPRouteRule rule(String path, String backendName) {
        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder()
                                .type("PathPrefix")
                                .value(BASE_PATH + path)
                                .build())
                        .build()))
                .filters(List.of())
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("")
                        .kind("Service")
                        .name(backendName)
                        .port(8080)
                        .weight(1)
                        .build()))
                .build();
    }

    private HTTPRouteRule rule(String type, String path, String backendName) {
        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder()
                                .type(type)
                                .value(path)
                                .build())
                        .build()))
                .filters(List.of())
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("")
                        .kind("Service")
                        .name(backendName)
                        .port(8080)
                        .weight(1)
                        .build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private KubeCustomObject existingCr(List<HTTPRouteRule> rules) {
        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(List.of())
                .rules(rules)
                .build();
        Map<String, Object> specMap = new ObjectMapper().convertValue(spec, Map.class);

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(CLOUD_SERVICE_NAME + "-chain-public-routes");
        metadata.setNamespace(NAMESPACE);

        return KubeCustomObject.builder()
                .apiVersion("gateway.networking.k8s.io/v1")
                .kind("HTTPRoute")
                .metadata(metadata)
                .spec(specMap)
                .build();
    }
}
