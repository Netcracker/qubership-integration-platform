package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpRouteResourceBuilderTest {

    private RoutesGetterService routesGetterService;
    private HttpRouteResourceBuilder builder;

    @BeforeEach
    void setUp() {
        routesGetterService = mock(RoutesGetterService.class);
        DeploymentRouteMapper mapper = Mappers.getMapper(DeploymentRouteMapper.class);

        YAMLMapper yamlMapper = new YAMLMapper();
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> publicNamingStrategy = ctx -> "my-domain-v1-chain-public-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> privateNamingStrategy = ctx -> "my-domain-v1-chain-private-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy = ctx -> "my-domain-v1";

        builder = new HttpRouteResourceBuilder(
                yamlMapper, routesGetterService, mapper,
                publicNamingStrategy, privateNamingStrategy, serviceNamingStrategy,
                new K8sNameValidator());
        ReflectionTestUtils.setField(builder, "baseRoutePrefix", "/qip-routes");
        ReflectionTestUtils.setField(builder, "domainLabel", "my-domain-label");
        ReflectionTestUtils.setField(builder, "bgVersionLabel", "bg-version");
        ReflectionTestUtils.setField(builder, "bgVersion", "v1");
    }

    private ResourceBuildContext<List<Snapshot>> contextFor(List<Snapshot> snapshots) {
        return ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(snapshots);
    }

    @Test
    void enabledIsFalseWhenNoTriggerRoutesExist() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/internal").type(RouteType.INTERNAL_TRIGGER).build()));

        assertFalse(builder.enabled(contextFor(List.of(mock(Snapshot.class)))));
    }

    @Test
    void buildEmitsOnlyPublicCrWhenOnlyPublicRoutesExist() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).connectTimeout(5000L).build()));

        String result = builder.build(contextFor(List.of(mock(Snapshot.class))));

        assertTrue(result.contains("my-domain-v1-chain-public-routes"));
        assertFalse(result.contains("my-domain-v1-chain-private-routes"));
        assertTrue(result.contains("/qip-routes/a"));
        assertTrue(result.contains("public-gateway"));
    }

    @Test
    void buildEmitsRouteInBothTiersWhenExternalPrivate() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_PRIVATE_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(mock(Snapshot.class))));

        assertTrue(result.contains("my-domain-v1-chain-public-routes"));
        assertTrue(result.contains("my-domain-v1-chain-private-routes"));
    }

    @Test
    void buildMergesWithCachedPriorRulesOnAppend() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        ResourceBuildContext<List<Snapshot>> context = contextFor(List.of(mock(Snapshot.class)));
        Map<String, Object> priorSpec = new LinkedHashMap<>();
        priorSpec.put("rules", List.of(
                Map.of("matches", List.of(Map.of("path", Map.of("type", "PathPrefix", "value", "/qip-routes/b"))))));
        context.getBuildCache().put("publicHttpRoute", priorSpec);

        String result = builder.build(context);

        assertTrue(result.contains("/qip-routes/a"));
        assertTrue(result.contains("/qip-routes/b"));
    }

    @Test
    void buildDropsCachedRuleForAPathThisBuildReplaces() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).connectTimeout(9000L).build()));

        ResourceBuildContext<List<Snapshot>> context = contextFor(List.of(mock(Snapshot.class)));
        Map<String, Object> priorSpec = new LinkedHashMap<>();
        priorSpec.put("rules", List.of(
                Map.of("matches", List.of(Map.of("path", Map.of("type", "PathPrefix", "value", "/qip-routes/a"))))));
        context.getBuildCache().put("publicHttpRoute", priorSpec);

        String result = builder.build(context);

        long occurrences = result.split("/qip-routes/a", -1).length - 1;
        assertEquals(1, occurrences);
        assertTrue(result.contains("9000ms"));
    }
}
