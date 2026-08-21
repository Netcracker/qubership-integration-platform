package org.qubership.integration.platform.engine.model.gatewayapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HTTPRouteSpecTest {

    @Test
    @SuppressWarnings("unchecked")
    void convertsToMapWithGatewayApiFieldNames() {
        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(List.of(ParentReference.builder()
                        .group("gateway.networking.k8s.io")
                        .kind("Gateway")
                        .name("public-gateway")
                        .build()))
                .rules(List.of(HTTPRouteRule.builder()
                        .matches(List.of(HTTPRouteMatch.builder()
                                .path(HTTPPathMatch.builder()
                                        .type("PathPrefix")
                                        .value("/api/v1/chain-1")
                                        .build())
                                .build()))
                        .filters(List.of(HTTPRouteFilter.builder()
                                .type("URLRewrite")
                                .urlRewrite(HTTPUrlRewriteFilter.builder()
                                        .path(HTTPPathModifier.builder()
                                                .type("ReplacePrefixMatch")
                                                .replacePrefixMatch("/api/v1")
                                                .build())
                                        .build())
                                .build()))
                        .backendRefs(List.of(HTTPBackendRef.builder()
                                .group("")
                                .kind("Service")
                                .name("engine-service")
                                .port(8080)
                                .weight(1)
                                .build()))
                        .timeouts(HTTPRouteTimeouts.builder().request("30000ms").build())
                        .build()))
                .build();

        Map<String, Object> map = new ObjectMapper().convertValue(spec, Map.class);

        List<Map<String, Object>> parentRefs = (List<Map<String, Object>>) map.get("parentRefs");
        assertEquals("gateway.networking.k8s.io", parentRefs.get(0).get("group"));
        assertEquals("Gateway", parentRefs.get(0).get("kind"));
        assertEquals("public-gateway", parentRefs.get(0).get("name"));

        List<Map<String, Object>> rules = (List<Map<String, Object>>) map.get("rules");
        Map<String, Object> rule = rules.get(0);

        List<Map<String, Object>> matches = (List<Map<String, Object>>) rule.get("matches");
        Map<String, Object> path = (Map<String, Object>) matches.get(0).get("path");
        assertEquals("PathPrefix", path.get("type"));
        assertEquals("/api/v1/chain-1", path.get("value"));

        List<Map<String, Object>> filters = (List<Map<String, Object>>) rule.get("filters");
        Map<String, Object> filter = filters.get(0);
        assertEquals("URLRewrite", filter.get("type"));
        Map<String, Object> urlRewrite = (Map<String, Object>) filter.get("urlRewrite");
        Map<String, Object> pathModifier = (Map<String, Object>) urlRewrite.get("path");
        assertEquals("ReplacePrefixMatch", pathModifier.get("type"));
        assertEquals("/api/v1", pathModifier.get("replacePrefixMatch"));

        List<Map<String, Object>> backendRefs = (List<Map<String, Object>>) rule.get("backendRefs");
        Map<String, Object> backendRef = backendRefs.get(0);
        assertEquals("", backendRef.get("group"));
        assertEquals("Service", backendRef.get("kind"));
        assertEquals("engine-service", backendRef.get("name"));
        assertEquals(8080, backendRef.get("port"));
        assertEquals(1, backendRef.get("weight"));

        Map<String, Object> timeouts = (Map<String, Object>) rule.get("timeouts");
        assertEquals("30000ms", timeouts.get("request"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsTimeoutsWhenNotSet() {
        HTTPRouteRule rule = HTTPRouteRule.builder()
                .matches(List.of())
                .filters(List.of())
                .backendRefs(List.of())
                .build();

        Map<String, Object> map = new ObjectMapper().convertValue(rule, Map.class);

        assertFalse(map.containsKey("timeouts"));
    }
}
