package org.qubership.integration.platform.camelk.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.util.paths.EgressTarget;
import org.qubership.integration.platform.chain.model.Snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EgressRouteResourceBuilderTest {

    private RoutesGetterService routesGetterService;
    private EgressRouteResourceBuilder builder;

    @BeforeEach
    void setUp() {
        routesGetterService = mock(RoutesGetterService.class);
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> egressNamingStrategy =
                context -> "my-domain-v1-egress-routes";

        // Mirrors the MINIMIZE_QUOTES setting of the production "customResourceYamlMapper" bean
        // (see YamlMapperConfiguration), so assertions here can match unquoted YAML scalars the
        // same way the real generated CRs render them.
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .build();
        builder = new EgressRouteResourceBuilder(
                new YAMLMapper(yamlFactory),
                routesGetterService,
                egressNamingStrategy,
                new K8sNameValidator());
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "egressGatewayName", "egress-gateway");
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "domainLabel", "qip.domain");
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "bgVersionLabel", "qip.bg-version");
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "bgVersion", "v1");
    }

    private ResourceBuildContext<List<Snapshot>> contextWithSnapshot(String snapshotId) {
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getId()).thenReturn(snapshotId);
        return ResourceBuildContext.create(BuildInfo.builder()
                        .options(ResourceBuildOptions.builder().name("my-domain").build())
                        .build())
                .updateTo(List.of(snapshot));
    }

    @Test
    void disabledWhenThereAreNoEgressRoutes() {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/chain-a").type(RouteType.EXTERNAL_TRIGGER).build()));

        assertFalse(builder.enabled(contextWithSnapshot("snap-1")));
    }

    @Test
    void enabledWhenThereIsAtLeastOneEgressRoute() {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        assertTrue(builder.enabled(contextWithSnapshot("snap-1")));
    }

    @Test
    void buildEmitsHttpRouteServiceEntryAndDestinationRuleForAnHttpsRoute() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertTrue(result.contains("kind: HTTPRoute"));
        assertTrue(result.contains("name: my-domain-v1-egress-routes"));
        assertTrue(result.contains("name: egress-gateway"));
        assertTrue(result.contains("value: /system/elem-a"));
        assertTrue(result.contains("kind: ServiceEntry"));
        assertTrue(result.contains("api.example.com"));
        assertTrue(result.contains("kind: DestinationRule"));
        assertTrue(result.contains("mode: SIMPLE"));
    }

    @Test
    void buildOmitsDestinationRuleForAnHttpRoute() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("http://backend:9090").gatewayPrefix("/http-sender/elem-a/hash")
                        .type(RouteType.EXTERNAL_SENDER).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertTrue(result.contains("kind: ServiceEntry"));
        assertFalse(result.contains("kind: DestinationRule"));
    }

    @Test
    void buildEmitsOneServiceEntryForTwoRoutesSharingAHost() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com/a").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build(),
                Route.builder().path("https://api.example.com/b").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertEqualsOccurrences(1, "kind: ServiceEntry", result);
    }

    // Two ports on one host within a single build. The ServiceEntry is named after the host alone,
    // so both ports have to land in the same document: two documents under one metadata.name would
    // apply in sequence and leave only the last port in the cluster.
    @Test
    void buildFoldsEveryPortOnAHostIntoOneServiceEntry() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com:443/a").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build(),
                Route.builder().path("https://api.example.com:9443/b").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertEqualsOccurrences(1, "kind: ServiceEntry", result);
        assertTrue(result.contains("number: 443"));
        assertTrue(result.contains("number: 9443"));
        assertTrue(result.contains("name: https-443"));
        assertTrue(result.contains("name: https-9443"));
    }

    @Test
    void buildPreservesUntouchedRulesFromTheCache() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));
        Map<String, Object> pathMatch = Map.of("type", "PathPrefix", "value", "/system/other-elem");
        Map<String, Object> match = Map.of("path", pathMatch);
        Map<String, Object> existingRule = new LinkedHashMap<>();
        existingRule.put("matches", List.of(match));
        Map<String, Object> existingSpec = new LinkedHashMap<>();
        existingSpec.put("rules", List.of(existingRule));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        context.getBuildCache().put(EgressRouteResourceBuilder.EGRESS_HTTP_ROUTE_CACHE_KEY, existingSpec);

        String result = builder.build(context);

        assertTrue(result.contains("/system/other-elem"));
        assertTrue(result.contains("/system/elem-a"));
    }

    @Test
    void buildMergesANewPortIntoAnExistingServiceEntryForTheSameHost() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com:9443/v2").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        String hostResourceName = EgressTarget.parse("https://api.example.com").hostResourceName();
        context.getBuildCache().put(
                EgressRouteResourceBuilder.serviceEntryCacheKey(hostResourceName),
                existingServiceEntrySpec(port(8443, "https", "HTTPS")));

        String result = builder.build(context);

        assertTrue(result.contains("number: 8443"));
        assertTrue(result.contains("number: 9443"));
    }

    @Test
    void buildGivesEveryPortOnAHostItsOwnNameSoIstioAcceptsTheServiceEntry() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com:9443/v2").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        String hostResourceName = EgressTarget.parse("https://api.example.com").hostResourceName();
        context.getBuildCache().put(
                EgressRouteResourceBuilder.serviceEntryCacheKey(hostResourceName),
                existingServiceEntrySpec(port(443, "https-443", "HTTPS")));

        String result = builder.build(context);

        assertTrue(result.contains("name: https-443"));
        assertTrue(result.contains("name: https-9443"));
    }

    @Test
    void buildReplacesAnExistingPortWithTheSameNumberInsteadOfDuplicating() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        String hostResourceName = EgressTarget.parse("https://api.example.com").hostResourceName();
        context.getBuildCache().put(
                EgressRouteResourceBuilder.serviceEntryCacheKey(hostResourceName),
                existingServiceEntrySpec(port(443, "https", "HTTPS")));

        String result = builder.build(context);

        // The DestinationRule this https route also gets has its own "number: 443" under
        // portLevelSettings, so scope the count to the ServiceEntry document alone.
        String serviceEntrySection = result.substring(result.indexOf("kind: ServiceEntry"));
        int destinationRuleIndex = serviceEntrySection.indexOf("kind: DestinationRule");
        if (destinationRuleIndex != -1) {
            serviceEntrySection = serviceEntrySection.substring(0, destinationRuleIndex);
        }
        assertEqualsOccurrences(1, "number: 443", serviceEntrySection);
    }

    @Test
    void buildMergesANewPortLevelSettingIntoAnExistingDestinationRuleForTheSameHost() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com:9443/v2").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        String hostResourceName = EgressTarget.parse("https://api.example.com").hostResourceName();
        context.getBuildCache().put(
                EgressRouteResourceBuilder.destinationRuleCacheKey(hostResourceName),
                existingDestinationRuleSpec(portLevelSetting(8443)));

        String result = builder.build(context);

        assertTrue(result.contains("number: 8443"));
        assertTrue(result.contains("number: 9443"));
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

    private Map<String, Object> existingServiceEntrySpec(Map<String, Object> existingPort) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("hosts", List.of("api.example.com"));
        spec.put("location", "MESH_EXTERNAL");
        spec.put("resolution", "DNS");
        spec.put("ports", List.of(existingPort));
        return spec;
    }

    private Map<String, Object> existingDestinationRuleSpec(Map<String, Object> existingPortLevelSetting) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("host", "api.example.com");
        spec.put("trafficPolicy", Map.of("portLevelSettings", List.of(existingPortLevelSetting)));
        return spec;
    }

    private void assertEqualsOccurrences(int expected, String needle, String haystack) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected, count);
    }
}
