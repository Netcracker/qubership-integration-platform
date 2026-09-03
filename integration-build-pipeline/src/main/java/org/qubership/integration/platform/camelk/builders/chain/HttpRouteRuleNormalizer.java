package org.qubership.integration.platform.camelk.builders.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively rewrites {@link DoubleNode} values that hold whole numbers (as produced by
 * {@code io.kubernetes.client.openapi.JSON}'s Gson instance, which decodes every JSON number as
 * {@code Double} by default) into integral nodes. Without this, a Kubernetes object carrying such
 * a value re-emits it as e.g. {@code 8080.0}, which the Gateway API's int32-typed schema fields
 * (HTTPRoute {@code backendRefs[].port}/{@code weight}) reject at apply time.
 */
public final class HttpRouteRuleNormalizer {
    private HttpRouteRuleNormalizer() {
    }

    public static void normalizeIntegralDoubles(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode value = objectNode.get(fieldName);
                if (value instanceof DoubleNode doubleNode) {
                    Long integral = toIntegralIfWhole(doubleNode);
                    if (integral != null) {
                        objectNode.put(fieldName, integral);
                    }
                } else {
                    normalizeIntegralDoubles(value);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode value = arrayNode.get(i);
                if (value instanceof DoubleNode doubleNode) {
                    Long integral = toIntegralIfWhole(doubleNode);
                    if (integral != null) {
                        arrayNode.set(i, LongNode.valueOf(integral));
                    }
                } else {
                    normalizeIntegralDoubles(value);
                }
            }
        }
    }

    private static Long toIntegralIfWhole(DoubleNode doubleNode) {
        double value = doubleNode.asDouble();
        return (Double.isFinite(value) && value == Math.rint(value)) ? (long) value : null;
    }
}
