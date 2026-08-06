package org.qubership.integration.platform.runtime.catalog.cr.builders;

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
        Handlebars handlebars = new Handlebars()
                .with(new ClassPathTemplateLoader("/cr/templates", ".hbs"))
                .with(EscapingStrategy.NOOP);

        NamingStrategy<ResourceBuildContext<List<Snapshot>>> publicNamingStrategy = ctx -> "my-domain-v1-public-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> privateNamingStrategy = ctx -> "my-domain-v1-private-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> internalNamingStrategy = ctx -> "my-domain-v1-internal-routes";
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> serviceNamingStrategy = ctx -> "my-domain-v1";

        builder = new EngineRoutesResourceBuilder(
                handlebars,
                publicNamingStrategy, privateNamingStrategy, internalNamingStrategy,
                serviceNamingStrategy, new K8sNameValidator());
        ReflectionTestUtils.setField(builder, "publicRoutePrefixV1", "/api/v1/qip/engine");
        ReflectionTestUtils.setField(builder, "domainLabel", "my-domain-label");
        ReflectionTestUtils.setField(builder, "bgVersionLabel", "bg-version");
        ReflectionTestUtils.setField(builder, "bgVersion", "v1");
    }

    private ResourceBuildContext<List<Snapshot>> contextFor(String domainName) {
        return ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name(domainName).build()).build()
        ).updateTo(Collections.<Snapshot>emptyList());
    }

    private String tierDocument(String fullOutput, String tierName) {
        for (String document : fullOutput.split("---")) {
            if (document.contains("name: " + tierName)) {
                return document;
            }
        }
        throw new AssertionError("No document found containing tier name: " + tierName);
    }

    @Test
    void enabledIsAlwaysTrue() {
        assertTrue(builder.enabled(contextFor("my-domain")));
    }

    @Test
    void buildEmitsAllThreeTiers() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        assertTrue(result.contains("my-domain-v1-public-routes"));
        assertTrue(result.contains("my-domain-v1-private-routes"));
        assertTrue(result.contains("my-domain-v1-internal-routes"));
    }

    @Test
    void publicTierHasAllThreeGatewaysAndAllThreeRules() throws Exception {
        String result = builder.build(contextFor("my-domain"));
        String publicTier = tierDocument(result, "my-domain-v1-public-routes");

        assertTrue(publicTier.contains("name: public-gateway"));
        assertTrue(publicTier.contains("name: private-gateway"));
        assertTrue(publicTier.contains("name: internal-gateway-service"));
        assertTrue(publicTier.contains("value: /api/v1/qip/engine/my-domain/sessions"));
        assertTrue(publicTier.contains("value: /api/v1/qip/engine/my-domain/chains/"));
        assertTrue(publicTier.contains("value: /api/v1/qip/engine/my-domain/live-exchanges"));
    }

    @Test
    void privateTierHasTwoParentRefsAndNoLiveExchangesRule() throws Exception {
        String result = builder.build(contextFor("my-domain"));
        String privateTier = tierDocument(result, "my-domain-v1-private-routes");

        assertTrue(privateTier.contains("name: private-gateway"));
        assertTrue(privateTier.contains("name: internal-gateway-service"));
        assertFalse(privateTier.contains("name: public-gateway"));
        assertTrue(privateTier.contains("value: /api/v1/qip/engine/my-domain/sessions"));
        assertTrue(privateTier.contains("value: /api/v1/qip/engine/my-domain/chains/"));
        assertFalse(privateTier.contains("live-exchanges"));
    }

    @Test
    void internalTierHasOnlyInternalGatewayServiceParentRef() throws Exception {
        String result = builder.build(contextFor("my-domain"));
        String internalTier = tierDocument(result, "my-domain-v1-internal-routes");

        assertTrue(internalTier.contains("name: internal-gateway-service"));
        assertFalse(internalTier.contains("name: public-gateway"));
        assertFalse(internalTier.contains("name: private-gateway"));
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
    void everyRuleHasABackendRefsBlockPointingAtTheDomainService() throws Exception {
        String result = builder.build(contextFor("my-domain"));

        long ruleCount = result.split("- matches:", -1).length - 1;
        assertEquals(7, ruleCount, "3 rules in public tier + 2 in private + 2 in internal = 7 rules total");

        long backendRefsCount = result.split("backendRefs:", -1).length - 1;
        assertEquals(7, backendRefsCount, "one backendRefs block per rule");
        assertTrue(result.contains("port: 8080"));
        assertFalse(result.contains("port: 8080.0"));
    }
}
