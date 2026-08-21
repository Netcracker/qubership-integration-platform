package org.qubership.integration.platform.engine.util.paths;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayPathMatchTest {

    @Test
    void pathWithoutPlaceholderStaysPathPrefix() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/orders");

        assertEquals("PathPrefix", match.getType());
        assertEquals("/qip-routes/orders", match.getValue());
    }

    @Test
    void pathWithSinglePlaceholderBecomesRegularExpression() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/orders/{id}");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/qip-routes/orders/[^/]+/?", match.getValue());
    }

    @Test
    void placeholderFollowedByLiteralSuffixIsPreserved() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/orders/{id}/items");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/qip-routes/orders/[^/]+/items/?", match.getValue());
    }

    @Test
    void multiplePlaceholdersAreAllReplaced() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/orders/{orderId}/items/{itemId}");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/qip-routes/orders/[^/]+/items/[^/]+/?", match.getValue());
    }

    @Test
    void placeholderAtStartOfPathIsReplaced() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/{domain}/orders");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/[^/]+/orders/?", match.getValue());
    }

    @Test
    void trailingSlashOnPlaceholderPathIsNotDoubled() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/orders/{id}/");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/qip-routes/orders/[^/]+/", match.getValue());
    }

    @Test
    void regularExpressionMatchesPathWithAndWithoutTrailingSlash() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/orders/{id}");
        Pattern pattern = Pattern.compile(match.getValue());

        assertTrue(pattern.matcher("/qip-routes/orders/123").matches());
        assertTrue(pattern.matcher("/qip-routes/orders/123/").matches());
        assertFalse(pattern.matcher("/qip-routes/orders/123/extra").matches());
    }

    @Test
    void equalityIsBasedOnTypeAndValueTogether() {
        GatewayPathMatch a = GatewayPathMatch.forPath("/qip-routes/orders/{id}");
        GatewayPathMatch b = GatewayPathMatch.of("RegularExpression", "/qip-routes/orders/[^/]+/?");
        GatewayPathMatch c = GatewayPathMatch.of("PathPrefix", "/qip-routes/orders/[^/]+/?");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void literalDotIsEscapedSoItMatchesOnlyADot() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/files/{name}.json");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/qip-routes/files/[^/]+\\.json/?", match.getValue());
    }

    @Test
    void escapedLiteralsMatchOnlyTheLiteralText() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/files/{name}.json");
        Pattern pattern = Pattern.compile(match.getValue());

        assertTrue(pattern.matcher("/qip-routes/files/report.json").matches());
        assertFalse(pattern.matcher("/qip-routes/files/report-json").matches());
    }

    @Test
    void everyRegexMetacharacterInALiteralIsEscaped() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/a+b(c)/{id}/d[e]f*");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/a\\+b\\(c\\)/[^/]+/d\\[e\\]f\\*/?", match.getValue());
    }
}
