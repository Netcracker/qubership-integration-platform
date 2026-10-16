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
        ExtLogRecord record = new ExtLogRecord(Level.INFO, "message", ConsoleLogFilterTest.class.getName());
        record.setLoggerName(ConsoleLogFilterTest.class.getName());
        record.setMdc(Map.of("chainId", "chain-1"));

        assertTrue(filter.isLoggable(record));

        assertEquals("chain-1", record.getMdc("chainId"));
        JsonLogProvider.MDC_MAPPING.keySet().stream()
                .filter(key -> !key.equals("chainId"))
                .forEach(key -> assertEquals(ConsoleLogFilter.ABSENT_MDC_VALUE, record.getMdc(key), key));
    }

    @Test
    void shouldDropCamelJobRecordForMissingCamelContext() {
        ExtLogRecord record = new ExtLogRecord(Level.ERROR, "message", ConsoleLogFilterTest.class.getName());
        record.setLoggerName("org.apache.camel.component.quartz.CamelJob");
        record.setThrown(new RuntimeException("No CamelContext could be found with name test-context"));

        assertFalse(filter.isLoggable(record));
    }
}
