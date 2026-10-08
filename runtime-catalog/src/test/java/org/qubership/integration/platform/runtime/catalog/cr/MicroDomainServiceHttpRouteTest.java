package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.strategies.EngineRoutesNamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;

class MicroDomainServiceHttpRouteTest {

    private static final String GROUP = "gateway.networking.k8s.io";
    private static final String VERSION = "v1";
    private static final String PLURAL = "httproutes";
    private static final String DOMAIN = "my-domain";
    private static final String ENGINE_ROUTE_NAME = "my-domain-v1-routes";

    private KubeOperator kubeOperator;
    private MicroDomainService microDomainService;

    @BeforeEach
    void setUp() {
        kubeOperator = mock(KubeOperator.class);

        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        EngineRoutesNamingStrategy engineRoutesNamingStrategy = new EngineRoutesNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-routes");

        microDomainService = new MicroDomainService(
                kubeOperator,
                integrationResourceNamingStrategy,
                context -> "my-domain-v1-cfg",
                mock(IntegrationConfigurationSerdes.class),
                mock(GenericCustomResources.class),
                mock(IntegrationServiceCatalog.class),
                false,
                engineRoutesNamingStrategy,
                new K8sNameValidator()
        );
        microDomainService.domainLabel = "qip.domain";
        microDomainService.bgVersionLabel = "qip.bgVersion";
        microDomainService.bgVersion = "v1";
    }

    private KubeCustomObject httpRoute(String name, String snapshotLabel) {
        KubeCustomObject object = new KubeCustomObject();
        object.setMetadata(new V1ObjectMeta().name(name).labels(Map.of(SNAPSHOT_ID_LABEL, snapshotLabel)));
        object.setKind("HTTPRoute");
        return object;
    }

    private void stubChainHttpRoutes(KubeCustomObject... httpRoutes) {
        when(kubeOperator.getCustomObjectsByLabels(eq(GROUP), eq(VERSION), eq(PLURAL), any()))
                .thenReturn(List.of(httpRoutes));
    }

    @Test
    void deleteHttpRoutesSelectsTheChainHttpRoutesOfThisDomainAndBlueGreenVersion() {
        microDomainService.deleteHttpRoutes(DOMAIN);

        Map<String, String> selector = new HashMap<>();
        selector.put("qip.domain", DOMAIN);
        selector.put("qip.bgVersion", "v1");
        selector.put(SNAPSHOT_ID_LABEL, null);
        verify(kubeOperator).getCustomObjectsByLabels(GROUP, VERSION, PLURAL, selector);
    }

    @Test
    void deleteHttpRoutesDeletesEveryChainHttpRouteOfTheDomain() {
        stubChainHttpRoutes(httpRoute("route-a-public", "s1"), httpRoute("route-b-egress", "s2"));

        microDomainService.deleteHttpRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, "route-a-public");
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, "route-b-egress");
    }

    @Test
    void deleteHttpRoutesOfOtherSnapshotsKeepsTheRoutesOfTheDeployedSnapshots() {
        String rawId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
        String labelId = new K8sNameValidator().validate(rawId);
        stubChainHttpRoutes(
                httpRoute("route-kept", labelId),
                httpRoute("route-dropped-public", "s2"),
                httpRoute("route-dropped-egress", "s2"));

        microDomainService.deleteHttpRoutesOfOtherSnapshots(DOMAIN, List.of(rawId));

        verify(kubeOperator, never()).deleteCustomObject(GROUP, VERSION, PLURAL, "route-kept");
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, "route-dropped-public");
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, "route-dropped-egress");
    }

    @Test
    void deleteEngineRoutesDeletesTheComputedNameUnconditionally() {
        microDomainService.deleteEngineRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, ENGINE_ROUTE_NAME);
    }

    // Finding 1: MicroDomainService.init() must register HTTPRoute with ModelMapper, otherwise
    // Yaml.loadAll (used by deploy()) falls back to DynamicKubernetesObject, which has no "spec"
    // property and throws for every document in the bundle -- not just the HTTPRoute one.
    @Test
    void initRegistersHttpRouteSoDeployCanParseIt() throws Exception {
        microDomainService.init();

        String httpRouteYaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: HTTPRoute
                metadata:
                  name: my-domain-v1-chain-public-routes
                spec:
                  parentRefs:
                    - group: gateway.networking.k8s.io
                      kind: Gateway
                      name: public-gateway
                  rules:
                    - matches:
                        - path:
                            type: PathPrefix
                            value: /qip-routes/a
                """;

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
}
