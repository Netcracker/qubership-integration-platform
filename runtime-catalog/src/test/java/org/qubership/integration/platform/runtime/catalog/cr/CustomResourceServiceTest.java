package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.HttpRoutePrivateNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.HttpRoutePublicNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomResourceServiceTest {

    private static final String GROUP = "gateway.networking.k8s.io";
    private static final String VERSION = "v1";
    private static final String PLURAL = "httproutes";
    private static final String DOMAIN = "my-domain";
    private static final String PUBLIC_ROUTE_NAME = "my-domain-v1-chain-public-routes";
    private static final String PRIVATE_ROUTE_NAME = "my-domain-v1-chain-private-routes";

    private KubeOperator kubeOperator;
    private RoutesGetterService routesGetterService;
    private CustomResourceService customResourceService;

    @BeforeEach
    void setUp() {
        kubeOperator = mock(KubeOperator.class);
        routesGetterService = mock(RoutesGetterService.class);

        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        HttpRoutePublicNamingStrategy publicNamingStrategy = new HttpRoutePublicNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-chain-public-routes");
        HttpRoutePrivateNamingStrategy privateNamingStrategy = new HttpRoutePrivateNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-chain-private-routes");

        customResourceService = new CustomResourceService(
                kubeOperator,
                integrationResourceNamingStrategy,
                context -> "my-domain-v1-cfg",
                mock(IntegrationConfigurationSerdes.class),
                mock(GenericCustomResources.class),
                false,
                routesGetterService,
                Mappers.getMapper(DeploymentRouteMapper.class),
                publicNamingStrategy,
                privateNamingStrategy
        );
    }

    private KubeCustomObject httpRoute(String name, List<Map<String, Object>> rules) {
        KubeCustomObject object = new KubeCustomObject();
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(name);
        object.setMetadata(metadata);
        object.setKind("HTTPRoute");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", rules);
        object.setSpec(spec);
        return object;
    }

    private Map<String, Object> rule(String path) {
        Map<String, Object> pathMatch = Map.of("type", "PathPrefix", "value", path);
        Map<String, Object> match = Map.of("path", pathMatch);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("matches", List.of(match));
        return rule;
    }

    @Test
    void deleteHttpRoutesDeletesBothComputedTierNamesUnconditionally() {
        customResourceService.deleteHttpRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PRIVATE_ROUTE_NAME);
    }

    @Test
    void deleteChainSnapshotStripsOnlyTargetSnapshotPathsAndKeepsCrWhenRulesRemain() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(PUBLIC_ROUTE_NAME, List.of(rule("/qip-routes/a"), rule("/qip-routes/b")))));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.empty());

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject updated = (KubeCustomObject) captor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remainingRules = (List<Map<String, Object>>) updated.getSpec().get("rules");
        assertEquals(1, remainingRules.size());
    }

    @Test
    void deleteChainSnapshotDeletesTierCrWhenNoRulesRemain() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(PUBLIC_ROUTE_NAME, List.of(rule("/qip-routes/a")))));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.empty());

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME);
        verify(kubeOperator, never()).createOrUpdateResource(any());
    }

    @Test
    void deleteChainSnapshotDoesNothingWhenSnapshotHasNoRoutes() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of());

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        verify(kubeOperator, never()).getCustomObject(any(), any(), any(), any());
    }
}
