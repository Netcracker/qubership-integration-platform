package org.qubership.integration.platform.engine.service.debugger.logging;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Level;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ConsoleLogFilterTest {

    private final ConsoleLogFilter filter = new ConsoleLogFilter();

    @Test
    void shouldWriteDashForAbsentMdcFieldsAndKeepPresentOnes() {
        ExtLogRecord logRecord = new ExtLogRecord(Level.INFO, "message", ConsoleLogFilterTest.class.getName());
        logRecord.setLoggerName(ConsoleLogFilterTest.class.getName());
        logRecord.setMdc(Map.of("chainId", "chain-1"));

        assertTrue(filter.isLoggable(logRecord));

        assertEquals("chain-1", logRecord.getMdc("chainId"));
        JsonLogProvider.MDC_MAPPING.keySet().stream()
                .filter(key -> !key.equals("chainId"))
                .forEach(key -> assertEquals(ConsoleLogFilter.ABSENT_MDC_VALUE, logRecord.getMdc(key), key));
    }

    @Test
    void shouldDropCamelJobRecordForMissingCamelContext() {
        ExtLogRecord logRecord = new ExtLogRecord(Level.ERROR, "message", ConsoleLogFilterTest.class.getName());
        logRecord.setLoggerName("org.apache.camel.component.quartz.CamelJob");
        logRecord.setThrown(new RuntimeException("No CamelContext could be found with name test-context"));

        assertFalse(filter.isLoggable(logRecord));
    }
}
