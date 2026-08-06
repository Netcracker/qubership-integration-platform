package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRouteRuleNormalizerTest {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    @Test
    void normalizeIntegralDoublesRewritesWholeNumbersAtEveryNestingLevel() {
        ObjectNode root = FACTORY.objectNode();
        root.put("topLevelPort", 8080.0);

        ObjectNode nested = root.putObject("nested");
        nested.put("weight", 1.0);

        ArrayNode backendRefs = root.putArray("backendRefs");
        ObjectNode backendRef = backendRefs.addObject();
        backendRef.put("port", 443.0);
        backendRef.put("weight", 2.0);

        HttpRouteRuleNormalizer.normalizeIntegralDoubles(root);

        assertTrue(root.get("topLevelPort").isIntegralNumber());
        assertEquals(8080L, root.get("topLevelPort").longValue());

        assertTrue(root.get("nested").get("weight").isIntegralNumber());
        assertEquals(1L, root.get("nested").get("weight").longValue());

        ObjectNode normalizedBackendRef = (ObjectNode) root.get("backendRefs").get(0);
        assertTrue(normalizedBackendRef.get("port").isIntegralNumber());
        assertEquals(443L, normalizedBackendRef.get("port").longValue());
        assertTrue(normalizedBackendRef.get("weight").isIntegralNumber());
        assertEquals(2L, normalizedBackendRef.get("weight").longValue());
    }

    @Test
    void normalizeIntegralDoublesLeavesFractionalDoublesUntouched() {
        ObjectNode root = FACTORY.objectNode();
        root.put("factor", 1.5);
        ArrayNode values = root.putArray("values");
        values.add(2.5);

        HttpRouteRuleNormalizer.normalizeIntegralDoubles(root);

        assertTrue(root.get("factor") instanceof DoubleNode);
        assertEquals(1.5, root.get("factor").doubleValue());
        assertTrue(root.get("values").get(0) instanceof DoubleNode);
        assertEquals(2.5, root.get("values").get(0).doubleValue());
    }
}
