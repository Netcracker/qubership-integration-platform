package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;

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
                org.mapstruct.factory.Mappers.getMapper(DeploymentRouteMapper.class),
                egressNamingStrategy,
                new K8sNameValidator());
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
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/chain-a").type(RouteType.EXTERNAL_TRIGGER).build()));

        assertFalse(builder.enabled(contextWithSnapshot("snap-1")));
    }

    @Test
    void enabledWhenThereIsAtLeastOneEgressRoute() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        assertTrue(builder.enabled(contextWithSnapshot("snap-1")));
    }

    @Test
    void buildEmitsHttpRouteServiceEntryAndDestinationRuleForAnHttpsRoute() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
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
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("http://backend:9090").gatewayPrefix("/http-sender/elem-a/hash")
                        .type(RouteType.EXTERNAL_SENDER).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertTrue(result.contains("kind: ServiceEntry"));
        assertFalse(result.contains("kind: DestinationRule"));
    }

    @Test
    void buildEmitsOneServiceEntryForTwoRoutesSharingAHost() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com/a").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build(),
                DeploymentRoute.builder().path("https://api.example.com/b").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertEqualsOccurrences(1, "kind: ServiceEntry", result);
    }

    @Test
    void buildPreservesUntouchedRulesFromTheCache() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
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
