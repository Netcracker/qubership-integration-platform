package org.qubership.integration.platform.engine.cloudcore.controlplane;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
        service = new IstioRoutesRegistrationService(kubeOperator, new ObjectMapper(), NAMESPACE, BASE_PATH);
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
    void postEgressGatewayRoutesThrowsUnsupportedOperationException() {
        DeploymentRouteUpdate route = route("http://external/api", RouteType.EXTERNAL_SERVICE, null);

        assertThrows(UnsupportedOperationException.class, () -> service.postEgressGatewayRoutes(route));
    }

    @Test
    void postPublicEngineRoutesWrapsMalformedExistingRuleAsControlPlaneException() {
        HTTPRouteRule ruleWithNoMatches = HTTPRouteRule.builder()
                .matches(List.of())
                .filters(List.of())
                .backendRefs(List.of())
                .build();
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(ruleWithNoMatches))));

        ControlPlaneException exception = assertThrows(ControlPlaneException.class, () ->
                service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

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

        assertThrows(ControlPlaneException.class, () -> service.postPublicEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

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
