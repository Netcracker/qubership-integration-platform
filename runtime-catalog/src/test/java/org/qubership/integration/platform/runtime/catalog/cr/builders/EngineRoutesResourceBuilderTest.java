package org.qubership.integration.platform.runtime.catalog.cr.builders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRoutesResourceBuilderTest {

    private EngineRoutesResourceBuilder builder;

    @BeforeEach
    void setUp() {
        // Mirrors HandlebarConfiguration.customResourceTemplates() exactly, including
        // prettyPrint(true) -- that flag controls the standalone-block-tag whitespace
        // stripping that shapes the emitted YAML, so the test must use the same
        // configuration production does.
        Handlebars handlebars = new Handlebars()
                .with(new ClassPathTemplateLoader("/cr/templates", ".hbs"))
                .with(EscapingStrategy.NOOP);
        handlebars.prettyPrint(true);

        NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy = ctx -> "my-domain-v1-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy = ctx -> "my-domain-v1";

        builder = new EngineRoutesResourceBuilder(
                handlebars, engineRoutesNamingStrategy, serviceNamingStrategy, new K8sNameValidator());
        ReflectionTestUtils.setField(builder, "publicRoutePrefixV1", "/api/v1/qip/engine");
        ReflectionTestUtils.setField(builder, "publicGatewayName", "public-gateway");
        ReflectionTestUtils.setField(builder, "privateGatewayName", "private-gateway");
        ReflectionTestUtils.setField(builder, "internalGatewayName", "internal-gateway-service");
        ReflectionTestUtils.setField(builder, "domainLabel", "my-domain-label");
        ReflectionTestUtils.setField(builder, "bgVersionLabel", "bg-version");
        ReflectionTestUtils.setField(builder, "bgVersion", "v1");
    }

    private ResourceBuildContext<List<Snapshot>> contextFor(String domainName) {
        return ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name(domainName).build()).build()
        ).updateTo(Collections.<Snapshot>emptyList());
    }

    @Test
    void enabledIsAlwaysTrue() {
        assertTrue(builder.enabled(contextFor("my-domain")));
    }

    @Test
    void buildEmitsTheNamedCr() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        assertTrue(result.contains("my-domain-v1-routes"));
    }

    @Test
    void crHasAllThreeGatewaysAndAllThreeRules() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        assertTrue(result.contains("name: public-gateway"));
        assertTrue(result.contains("name: private-gateway"));
        assertTrue(result.contains("name: internal-gateway-service"));
        assertTrue(result.contains("value: /api/v1/qip/engine/my-domain/sessions"));
        assertTrue(result.contains("value: /api/v1/qip/engine/my-domain/chains/"));
        assertTrue(result.contains("value: /api/v1/qip/engine/my-domain/live-exchanges"));
    }

    @Test
    void checkpointSessionRuleTruncatesToPrefixBeforeChainIdVariable() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        assertTrue(result.contains("value: /api/v1/qip/engine/my-domain/chains/"));
        assertFalse(result.contains("chainId"));
        assertTrue(result.contains("replacePrefixMatch: /v1/engine/chains/"));
    }

    @Test
    void liveExchangesRuleRewritesToRealControllerPathNotDomainPrefixedPath() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        assertTrue(result.contains("replacePrefixMatch: /v1/engine/live-exchanges"));
    }

    @Test
    void domainNameLabelIsSanitizedButPathsUseRawDomainName() throws Exception {
        String result = builder.build(contextFor("test_domain"));

        assertTrue(result.contains("value: /api/v1/qip/engine/test_domain/sessions"));
        assertTrue(result.contains("value: /api/v1/qip/engine/test_domain/chains/"));
        assertTrue(result.contains("value: /api/v1/qip/engine/test_domain/live-exchanges"));
        assertTrue(result.contains("my-domain-label: testdomain"));
    }

    // Parses the rendered output as real YAML instead of relying only on substring
    // checks, so a template indentation/whitespace regression (which prettyPrint
    // controls) would actually fail this test.
    @Test
    void renderedOutputIsWellFormedYamlWithExpectedStructure() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        YAMLMapper yamlMapper = new YAMLMapper();
        JsonNode root = yamlMapper.readTree(result);

        assertEquals("HTTPRoute", root.path("kind").asText());
        assertEquals("my-domain-v1-routes", root.path("metadata").path("name").asText());

        JsonNode parentRefs = root.path("spec").path("parentRefs");
        assertEquals(3, parentRefs.size());

        JsonNode rules = root.path("spec").path("rules");
        assertEquals(3, rules.size());

        JsonNode firstBackendRef = rules.get(0).path("backendRefs").get(0);
        assertTrue(firstBackendRef.path("port").isIntegralNumber());
        assertEquals(8080, firstBackendRef.path("port").asInt());
    }
}
