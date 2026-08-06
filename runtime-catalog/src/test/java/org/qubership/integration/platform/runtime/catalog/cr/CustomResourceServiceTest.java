package org.qubership.integration.platform.runtime.catalog.cr;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.EngineRoutesInternalNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.EngineRoutesPrivateNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.EngineRoutesPublicNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.HttpRoutePrivateNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.HttpRoutePublicNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private static final String ENGINE_PUBLIC_ROUTE_NAME = "my-domain-v1-public-routes";
    private static final String ENGINE_PRIVATE_ROUTE_NAME = "my-domain-v1-private-routes";
    private static final String ENGINE_INTERNAL_ROUTE_NAME = "my-domain-v1-internal-routes";

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
        EngineRoutesPublicNamingStrategy enginePublicNamingStrategy = new EngineRoutesPublicNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-public-routes");
        EngineRoutesPrivateNamingStrategy enginePrivateNamingStrategy = new EngineRoutesPrivateNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-private-routes");
        EngineRoutesInternalNamingStrategy engineInternalNamingStrategy = new EngineRoutesInternalNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-internal-routes");

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
                privateNamingStrategy,
                enginePublicNamingStrategy,
                enginePrivateNamingStrategy,
                engineInternalNamingStrategy,
                new YAMLMapper()
        );
        ReflectionTestUtils.setField(customResourceService, "baseRoutePrefix", "/qip-routes");
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

    // Mirrors what io.kubernetes.client.openapi.JSON (Gson, ToNumberPolicy.DOUBLE) actually
    // produces for a sibling chain's rule read back from the cluster: every JSON number, including
    // a whole-number port/weight, decodes as Double.
    private Map<String, Object> ruleWithBackendRef(String path) {
        Map<String, Object> pathMatch = Map.of("type", "PathPrefix", "value", path);
        Map<String, Object> match = Map.of("path", pathMatch);
        Map<String, Object> backendRef = new LinkedHashMap<>();
        backendRef.put("group", "");
        backendRef.put("kind", "Service");
        backendRef.put("name", "some-other-service");
        backendRef.put("port", 8080.0);
        backendRef.put("weight", 1.0);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("matches", List.of(match));
        rule.put("backendRefs", List.of(backendRef));
        return rule;
    }

    @Test
    void deleteHttpRoutesDeletesBothComputedTierNamesUnconditionally() {
        customResourceService.deleteHttpRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PRIVATE_ROUTE_NAME);
    }

    @Test
    void deleteEngineRoutesDeletesAllThreeComputedTierNamesUnconditionally() {
        customResourceService.deleteEngineRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, ENGINE_PUBLIC_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, ENGINE_PRIVATE_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, ENGINE_INTERNAL_ROUTE_NAME);
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

    // A sibling chain's rule read back via KubeOperator.getCustomObject comes back with its
    // backendRefs[].port/weight decoded as Double (Gson's ToNumberPolicy.DOUBLE). Re-applying it
    // untouched after stripping this snapshot's own rule would re-emit e.g. "port: 8080.0", which
    // the Gateway API's int32-typed schema rejects at apply time.
    @Test
    void deleteChainSnapshotNormalizesIntegralDoublesInSurvivingSiblingRule() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(PUBLIC_ROUTE_NAME,
                        List.of(rule("/qip-routes/a"), ruleWithBackendRef("/qip-routes/b")))));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.empty());

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject updated = (KubeCustomObject) captor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remainingRules = (List<Map<String, Object>>) updated.getSpec().get("rules");
        assertEquals(1, remainingRules.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backendRefs = (List<Map<String, Object>>) remainingRules.get(0).get("backendRefs");
        Map<String, Object> backendRef = backendRefs.get(0);
        assertEquals(8080L, ((Number) backendRef.get("port")).longValue());
        assertFalse(backendRef.get("port") instanceof Double);
        assertEquals(1L, ((Number) backendRef.get("weight")).longValue());
        assertFalse(backendRef.get("weight") instanceof Double);
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

    // Finding 2: unprefixed endsWith matching over-stripped unrelated chains' routes. A rule at
    // "/qip-routes/x/a" belongs to some other chain whose own path happens to end with "/a", but it
    // is not the same full path as this chain's own "/qip-routes/a" and must survive.
    @Test
    void deleteChainSnapshotDoesNotStripAnotherChainsRuleThatMerelyEndsWithSameSuffix() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(PUBLIC_ROUTE_NAME,
                        List.of(rule("/qip-routes/a"), rule("/qip-routes/x/a")))));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.empty());

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject updated = (KubeCustomObject) captor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remainingRules = (List<Map<String, Object>>) updated.getSpec().get("rules");
        assertEquals(1, remainingRules.size());
        assertEquals("/qip-routes/x/a", pathOf(remainingRules.get(0)));
    }

    // Finding 3: a snapshot's own path set must be split per tier before it's used to strip that
    // tier's CR. A snapshot with only a PRIVATE_TRIGGER route must never even look at the public CR,
    // let alone strip a same-path rule out of it.
    @Test
    void deleteChainSnapshotWithOnlyPrivateRouteNeverTouchesPublicTier() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.PRIVATE_TRIGGER).build()));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(PRIVATE_ROUTE_NAME, List.of(rule("/qip-routes/a")))));

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        verify(kubeOperator, never()).getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME));
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PRIVATE_ROUTE_NAME);
    }

    // Finding 3: egress routes (EXTERNAL_SENDER/EXTERNAL_SERVICE) are not gateway-tier paths at all
    // and must not defeat the "nothing to do" early return, nor trigger any lookup against either
    // tier's CR.
    @Test
    void deleteChainSnapshotWithOnlyEgressRoutesDoesNothing() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://example.com").type(RouteType.EXTERNAL_SENDER).build()));

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        verify(kubeOperator, never()).getCustomObject(any(), any(), any(), any());
    }

    // Finding 4: a rule shape stripPathsFromTier didn't generate itself (no "matches" key at all)
    // must be preserved rather than crash the whole snapshot-deletion pipeline.
    @Test
    void deleteChainSnapshotPreservesRuleWithUnrecognizedShapeInsteadOfThrowing() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        Map<String, Object> malformedRule = new LinkedHashMap<>();
        malformedRule.put("name", "hand-edited-rule-without-matches");
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(PUBLIC_ROUTE_NAME, List.of(rule("/qip-routes/a"), malformedRule))));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject updated = (KubeCustomObject) captor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remainingRules = (List<Map<String, Object>>) updated.getSpec().get("rules");
        assertEquals(1, remainingRules.size());
        assertEquals("hand-edited-rule-without-matches", remainingRules.get(0).get("name"));
    }

    // Finding 4: a CR with no "rules" key at all (another shape stripPathsFromTier didn't generate)
    // must not throw either.
    @Test
    void deleteChainSnapshotDoesNotThrowWhenTierCrHasNoRulesKey() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        KubeCustomObject noRulesRoute = new KubeCustomObject();
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(PUBLIC_ROUTE_NAME);
        noRulesRoute.setMetadata(metadata);
        noRulesRoute.setKind("HTTPRoute");
        noRulesRoute.setSpec(new LinkedHashMap<>());
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME)))
                .thenReturn(Optional.of(noRulesRoute));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME)))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1"));

        verify(kubeOperator, never()).createOrUpdateResource(any());
        verify(kubeOperator, never()).deleteCustomObject(any(), any(), any(), any());
    }

    // Finding 6: for a long enough domain name, the public and private tier names must not truncate
    // to the same 63-character string.
    @Test
    void publicAndPrivateHttpRouteNamesRemainDistinctForLongDomainNames() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> longBaseNamingStrategy =
                context -> "a".repeat(60) + "-v1";
        HttpRoutePublicNamingStrategy publicStrategy = new HttpRoutePublicNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), longBaseNamingStrategy, "-chain-public-routes");
        HttpRoutePrivateNamingStrategy privateStrategy = new HttpRoutePrivateNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), longBaseNamingStrategy, "-chain-private-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-long-domain").build()).build()
        ).updateTo(Collections.emptyList());

        String publicName = publicStrategy.getName(context);
        String privateName = privateStrategy.getName(context);

        assertNotEquals(publicName, privateName);
        assertTrue(publicName.endsWith("-chain-public-routes"));
        assertTrue(privateName.endsWith("-chain-private-routes"));
    }

    // Finding 1: CustomResourceService.init() must register HTTPRoute with ModelMapper, otherwise
    // Yaml.loadAll (used by deploy()) falls back to DynamicKubernetesObject, which has no "spec"
    // property and throws for every document in the bundle -- not just the HTTPRoute one.
    @Test
    void initRegistersHttpRouteSoDeployCanParseIt() throws Exception {
        customResourceService.init();

        String httpRouteYaml = "apiVersion: gateway.networking.k8s.io/v1\n"
                + "kind: HTTPRoute\n"
                + "metadata:\n"
                + "  name: my-domain-v1-chain-public-routes\n"
                + "spec:\n"
                + "  parentRefs:\n"
                + "    - group: gateway.networking.k8s.io\n"
                + "      kind: Gateway\n"
                + "      name: public-gateway\n"
                + "  rules:\n"
                + "    - matches:\n"
                + "        - path:\n"
                + "            type: PathPrefix\n"
                + "            value: /qip-routes/a\n";

        List<Object> parsed = io.kubernetes.client.util.Yaml.loadAll(httpRouteYaml);

        assertEquals(1, parsed.size());
        assertTrue(parsed.get(0) instanceof KubeCustomObject,
                "Expected HTTPRoute to parse into KubeCustomObject (usable spec), got: " + parsed.get(0).getClass());
        KubeCustomObject parsedRoute = (KubeCustomObject) parsed.get(0);
        assertEquals("HTTPRoute", parsedRoute.getKind());
        assertEquals("my-domain-v1-chain-public-routes", parsedRoute.getMetadata().getName());

        // Prove the parsed object is also usable by the apply path, not just by the parser: this is
        // the whole "parse-to-apply boundary" the bug slipped through at.
        assertDoesNotThrow(() -> kubeOperator.createOrUpdateResource(parsedRoute));
        verify(kubeOperator).createOrUpdateResource(parsedRoute);
    }

    private String pathOf(Map<String, Object> rule) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) rule.get("matches");
        @SuppressWarnings("unchecked")
        Map<String, Object> path = (Map<String, Object>) matches.get(0).get("path");
        return (String) path.get("value");
    }
}
