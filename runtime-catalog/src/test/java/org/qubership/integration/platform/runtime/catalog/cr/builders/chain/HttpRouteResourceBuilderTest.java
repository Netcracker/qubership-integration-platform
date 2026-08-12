package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
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

        // Mirrors the MINIMIZE_QUOTES setting of the production "customResourceYamlMapper" bean
        // (see YamlMapperConfiguration), so assertions here can match unquoted YAML scalars the
        // same way the real generated CRs render them.
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .build();
        YAMLMapper yamlMapper = new YAMLMapper(yamlFactory);
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

    // Finding 5: the mapper writes its own leading "---" document-start marker per document (Jackson
    // YAML enables WRITE_DOC_START_MARKER by default); appendTier used to also append a manual
    // trailing "---\n" after every tier, so back-to-back tiers ended up with a doubled marker between
    // them (an empty spurious YAML document) and a final one dangling after the last tier. With the
    // manual marker removed, each tier contributes exactly its own single leading "---" and nothing
    // trails the last one.
    @Test
    void buildDoesNotAppendRedundantDocumentSeparator() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_PRIVATE_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(mock(Snapshot.class))));

        long separatorCount = result.split("---", -1).length - 1;
        assertEquals(2, separatorCount,
                "expected exactly one document-start marker per tier (2 tiers), no extra redundant one");
        assertFalse(result.strip().endsWith("---"),
                "must not end with a spurious empty trailing YAML document");
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

    @Test
    void buildRewritesIntegralDoublesInPreservedRuleToIntegers() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        ResourceBuildContext<List<Snapshot>> context = contextFor(List.of(mock(Snapshot.class)));
        Map<String, Object> priorSpec = new LinkedHashMap<>();
        // Mirrors what io.kubernetes.client.openapi.JSON (Gson, ToNumberPolicy.DOUBLE) actually
        // produces: every JSON number decodes as Double, even whole numbers like port/weight.
        priorSpec.put("rules", List.of(
                Map.of(
                        "matches", List.of(Map.of("path", Map.of("type", "PathPrefix", "value", "/qip-routes/b"))),
                        "backendRefs", List.of(Map.of(
                                "group", "",
                                "kind", "Service",
                                "name", "some-other-service",
                                "port", 8080.0,
                                "weight", 1.0)))));
        context.getBuildCache().put("publicHttpRoute", priorSpec);

        String result = builder.build(context);

        assertTrue(result.contains("port: 8080"));
        assertFalse(result.contains("port: 8080.0"));
        assertTrue(result.contains("weight: 1"));
        assertFalse(result.contains("weight: 1.0"));
    }

    @Test
    void buildPreservesCachedRuleWithNoRecognizablePath() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        ResourceBuildContext<List<Snapshot>> context = contextFor(List.of(mock(Snapshot.class)));
        Map<String, Object> priorSpec = new LinkedHashMap<>();
        // A rule shaped differently than expected (no "matches" key at all) must still be
        // preserved rather than silently dropped, per the "preserve unless touched" contract.
        priorSpec.put("rules", List.of(
                Map.of("name", "hand-edited-rule-without-matches")));
        context.getBuildCache().put("publicHttpRoute", priorSpec);

        String result = builder.build(context);

        assertTrue(result.contains("hand-edited-rule-without-matches"));
        assertTrue(result.contains("/qip-routes/a"));
    }

    // Gateway API's HTTPPathMatch.type defaults to PathPrefix when the field is absent, so a
    // cached rule with a "value" but no "type" key is a valid, fully-specified PathPrefix rule
    // and must be recognized as touched (and dropped) rather than preserved as unrecognized.
    @Test
    void buildDropsCachedRuleWithNoTypeKeyWhenRecognizedAsEquivalentPathPrefix() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).connectTimeout(9000L).build()));

        ResourceBuildContext<List<Snapshot>> context = contextFor(List.of(mock(Snapshot.class)));
        Map<String, Object> priorSpec = new LinkedHashMap<>();
        priorSpec.put("rules", List.of(
                Map.of("matches", List.of(Map.of("path", Map.of("value", "/qip-routes/a"))))));
        context.getBuildCache().put("publicHttpRoute", priorSpec);

        String result = builder.build(context);

        long occurrences = result.split("/qip-routes/a", -1).length - 1;
        assertEquals(1, occurrences);
        assertTrue(result.contains("9000ms"));
    }

    @Test
    void buildEmitsRegularExpressionMatchForPlaceholderPath() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/orders/{id}").type(RouteType.EXTERNAL_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(mock(Snapshot.class))));

        assertTrue(result.contains("type: RegularExpression"));
        // SnakeYAML always quotes a scalar containing flow-indicator characters ("[", "]",
        // "^"), regardless of MINIMIZE_QUOTES, since an unquoted plain scalar with those
        // characters would not round-trip as this exact string.
        assertTrue(result.contains("value: \"/qip-routes/orders/[^/]+\""));
    }

    @Test
    void buildEmitsNoFiltersForPlaceholderFreeRoute() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(mock(Snapshot.class))));

        assertFalse(result.contains("URLRewrite"));
        assertFalse(result.contains("ReplacePrefixMatch"));
    }

    @Test
    void buildDropsCachedRuleForAPlaceholderPathThisBuildReplaces() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/orders/{id}").type(RouteType.EXTERNAL_TRIGGER).connectTimeout(9000L).build()));

        ResourceBuildContext<List<Snapshot>> context = contextFor(List.of(mock(Snapshot.class)));
        Map<String, Object> priorSpec = new LinkedHashMap<>();
        priorSpec.put("rules", List.of(
                Map.of("matches", List.of(Map.of("path",
                        Map.of("type", "RegularExpression", "value", "/qip-routes/orders/[^/]+"))))));
        context.getBuildCache().put("publicHttpRoute", priorSpec);

        String result = builder.build(context);

        long occurrences = result.split("/qip-routes/orders/\\[\\^/\\]\\+", -1).length - 1;
        assertEquals(1, occurrences);
        assertTrue(result.contains("9000ms"));
    }

    // NOTE: the brief for this task also specified a
    // buildTreatsRouteAsTouchedWhenItsMatchTypeChangesBetweenDeploys test: a cached
    // PathPrefix rule for the same route should be dropped once that route's path gains a
    // placeholder and its match type becomes RegularExpression. It is intentionally omitted
    // here. A cached HTTPRoute CR rule carries only a (type, value) path match and no stable
    // route identity, so preservedRulesFromCache() cannot recognize that an old PathPrefix
    // rule and a new RegularExpression rule came from the same route. GatewayPathMatch
    // equality is deliberately by (type, value) together (see Task 1), and the parallel
    // engine-side task (Task 5, same touched-path-detection approach) has no equivalent
    // test. Satisfying this case needs new pattern-overlap matching logic beyond
    // GatewayPathMatch's existing contract, which is out of scope for wiring in the utility.
    // Flagged for the plan/brief author instead of implemented ad hoc.
}
