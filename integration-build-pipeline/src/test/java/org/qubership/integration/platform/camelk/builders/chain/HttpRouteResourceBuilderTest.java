package org.qubership.integration.platform.camelk.builders.chain;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpRouteResourceBuilderTest {

    private RoutesGetterService routesGetterService;
    private HttpRouteResourceBuilder builder;

    @BeforeEach
    void setUp() {
        routesGetterService = mock(RoutesGetterService.class);

        // Mirrors the MINIMIZE_QUOTES setting of the production "customResourceYamlMapper" bean
        // (see YamlMapperConfiguration), so assertions here can match unquoted YAML scalars the
        // same way the real generated CRs render them.
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .build();
        YAMLMapper yamlMapper = new YAMLMapper(yamlFactory);
        NamingStrategy<ResourceBuildContext<Snapshot>> publicNamingStrategy =
                ctx -> "my-domain-v1-" + ctx.getData().getId() + "-chain-public-routes";
        NamingStrategy<ResourceBuildContext<Snapshot>> privateNamingStrategy =
                ctx -> "my-domain-v1-" + ctx.getData().getId() + "-chain-private-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy = ctx -> "my-domain-v1";

        builder = new HttpRouteResourceBuilder(
                yamlMapper, routesGetterService,
                publicNamingStrategy, privateNamingStrategy, serviceNamingStrategy,
                new K8sNameValidator());
        ReflectionTestUtils.setField(builder, "baseRoutePrefix", "/qip-routes");
        ReflectionTestUtils.setField(builder, "publicGatewayName", "public-gateway");
        ReflectionTestUtils.setField(builder, "privateGatewayName", "private-gateway");
        ReflectionTestUtils.setField(builder, "domainLabel", "my-domain-label");
        ReflectionTestUtils.setField(builder, "bgVersionLabel", "bg-version");
        ReflectionTestUtils.setField(builder, "bgVersion", "v1");
    }

    private Snapshot snapshot(String id) {
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getId()).thenReturn(id);
        return snapshot;
    }

    private ResourceBuildContext<List<Snapshot>> contextFor(List<Snapshot> snapshots) {
        return ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build(),
                mock(IntegrationServiceCatalog.class)
        ).updateTo(snapshots);
    }

    @Test
    void enabledIsFalseWhenNoTriggerRoutesExist() {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/internal").type(RouteType.INTERNAL_TRIGGER).build()));

        assertFalse(builder.enabled(contextFor(List.of(snapshot("s1")))));
    }

    @Test
    void buildEmitsOnlyPublicCrWhenOnlyPublicRoutesExist() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).connectTimeout(5000L).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        assertTrue(result.contains("my-domain-v1-s1-chain-public-routes"));
        assertFalse(result.contains("my-domain-v1-s1-chain-private-routes"));
        assertTrue(result.contains("/qip-routes/a"));
        assertTrue(result.contains("public-gateway"));
        assertTrue(result.contains("request: 5000ms"));
    }

    // Gateway API's HTTPRoute CRD rejects spec.rules[].timeouts.request unless every unit run in
    // it is 1-5 digits; a route's default connectTimeout (120000ms) has six digits, so a plain
    // "<millis>ms" suffix produced an unschedulable CR. GatewayDuration decomposes it into "2m"
    // instead.
    @Test
    void buildFormatsATimeoutAboveTheMillisDigitLimitIntoLargerUnits() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).connectTimeout(120_000L).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        assertTrue(result.contains("request: 2m"));
        assertFalse(result.contains("120000ms"));
    }

    // Finding 5: the mapper writes its own leading "---" document-start marker per document (Jackson
    // YAML enables WRITE_DOC_START_MARKER by default); appendTier used to also append a manual
    // trailing "---\n" after every tier, so back-to-back tiers ended up with a doubled marker between
    // them (an empty spurious YAML document) and a final one dangling after the last tier. With the
    // manual marker removed, each tier contributes exactly its own single leading "---" and nothing
    // trails the last one.
    @Test
    void buildDoesNotAppendRedundantDocumentSeparator() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_PRIVATE_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        long separatorCount = result.split("---", -1).length - 1;
        assertEquals(2, separatorCount,
                "expected exactly one document-start marker per tier (2 tiers), no extra redundant one");
        assertFalse(result.strip().endsWith("---"),
                "must not end with a spurious empty trailing YAML document");
    }

    @Test
    void buildEmitsRouteInBothTiersWhenExternalPrivate() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_PRIVATE_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        assertTrue(result.contains("my-domain-v1-s1-chain-public-routes"));
        assertTrue(result.contains("my-domain-v1-s1-chain-private-routes"));
    }

    @Test
    void buildEmitsRegularExpressionMatchForPlaceholderPath() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/orders/{id}").type(RouteType.EXTERNAL_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        assertTrue(result.contains("type: RegularExpression"));
        // SnakeYAML always quotes a scalar containing flow-indicator characters ("[", "]",
        // "^"), regardless of MINIMIZE_QUOTES, since an unquoted plain scalar with those
        // characters would not round-trip as this exact string.
        assertTrue(result.contains("value: \"/qip-routes/orders/[^/]+/?\""));
    }

    @Test
    void buildEmitsNoFiltersForPlaceholderFreeRoute() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        assertFalse(result.contains("URLRewrite"));
        assertFalse(result.contains("ReplacePrefixMatch"));
    }

    @Test
    void buildEmitsOneHttpRoutePerSnapshot() throws Exception {
        Snapshot first = snapshot("s1");
        Snapshot second = snapshot("s2");
        when(routesGetterService.getRoutes(eq(first), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));
        when(routesGetterService.getRoutes(eq(second), any())).thenReturn(List.of(
                Route.builder().path("/b").type(RouteType.EXTERNAL_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(first, second)));

        List<JsonNode> httpRoutes = new YAMLMapper().readValues(new YAMLFactory().createParser(result), JsonNode.class)
                .readAll();
        assertEquals(2, httpRoutes.size());
        assertEquals("my-domain-v1-s1-chain-public-routes", httpRoutes.get(0).at("/metadata/name").asText());
        assertEquals(List.of("/qip-routes/a"), rulePaths(httpRoutes.get(0)));
        assertEquals("my-domain-v1-s2-chain-public-routes", httpRoutes.get(1).at("/metadata/name").asText());
        assertEquals(List.of("/qip-routes/b"), rulePaths(httpRoutes.get(1)));
    }

    @Test
    void buildLabelsTheHttpRouteWithItsSnapshot() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        String result = builder.build(contextFor(List.of(snapshot("s1"))));

        JsonNode labels = new YAMLMapper().readTree(result).at("/metadata/labels");
        assertEquals("s1", labels.path(SourceConfigMapBuilder.SNAPSHOT_ID_LABEL).asText());
        assertEquals("my-domain", labels.path("my-domain-label").asText());
        assertEquals("v1", labels.path("bg-version").asText());
    }

    private static List<String> rulePaths(JsonNode httpRoute) {
        List<String> paths = new ArrayList<>();
        httpRoute.at("/spec/rules").forEach(rule -> paths.add(rule.at("/matches/0/path/value").asText()));
        return paths;
    }
}
