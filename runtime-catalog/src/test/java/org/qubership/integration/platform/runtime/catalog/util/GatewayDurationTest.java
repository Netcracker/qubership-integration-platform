package org.qubership.integration.platform.runtime.catalog.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayDurationTest {

    private static final java.util.regex.Pattern GATEWAY_DURATION_PATTERN =
            java.util.regex.Pattern.compile("^([0-9]{1,5}(h|m|s|ms)){1,4}$");

    @Test
    void millisAtOrBelowFiveDigitsStayPlainMillis() {
        assertEquals("0ms", GatewayDuration.formatMillis(0));
        assertEquals("5000ms", GatewayDuration.formatMillis(5000));
        assertEquals("99999ms", GatewayDuration.formatMillis(99_999));
    }

    @Test
    void sixDigitMillisAreDecomposedIntoMinutes() {
        assertEquals("2m", GatewayDuration.formatMillis(120_000));
    }

    @Test
    void sixDigitMillisWithSecondsRemainderAreDecomposed() {
        assertEquals("1m40s", GatewayDuration.formatMillis(100_000));
    }

    @Test
    void millisRemainderAfterDecompositionIsKept() {
        assertEquals("1m40s1ms", GatewayDuration.formatMillis(100_001));
    }

    @Test
    void hoursAreIncludedWhenPresent() {
        assertEquals("1h1m1s1ms", GatewayDuration.formatMillis(3_661_001));
    }

    @Test
    void everyFormattedValueMatchesTheGatewayApiDurationSchema() {
        for (long millis : new long[] {0, 1, 999, 5000, 99_999, 100_000, 120_000, 3_661_001}) {
            String formatted = GatewayDuration.formatMillis(millis);
            assertTrue(GATEWAY_DURATION_PATTERN.matcher(formatted).matches(),
                    "'" + formatted + "' (from " + millis + "ms) does not match the Gateway API duration schema");
        }
    }
}
