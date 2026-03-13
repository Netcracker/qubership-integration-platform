package org.qubership.integration.platform.engine.camel.scheduler;

import org.jboss.logmanager.Level;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelJobLogFilterTest {

    @Test
    void shouldFilterErrorFromCamelJobWhenMessageMatches() {
        CamelJobLogFilter filter = new CamelJobLogFilter();
        LogRecord record = record(
                Level.ERROR,
                "org.apache.camel.component.quartz.CamelJob",
                new RuntimeException("No CamelContext could be found with name test-context")
        );

        assertFalse(filter.isLoggable(record));
    }

    @Test
    void shouldFilterInfoFromJobRunShellWhenMessageMatches() {
        CamelJobLogFilter filter = new CamelJobLogFilter();
        LogRecord record = record(
                Level.INFO,
                "org.quartz.core.JobRunShell",
                new RuntimeException("No CamelContext could be found with name test-context")
        );

        assertFalse(filter.isLoggable(record));
    }

    @Test
    void shouldNotFilterWhenThrownIsNull() {
        CamelJobLogFilter filter = new CamelJobLogFilter();
        LogRecord record = record(
                Level.ERROR,
                "org.apache.camel.component.quartz.CamelJob",
                null
        );

        assertTrue(filter.isLoggable(record));
    }

    @Test
    void shouldNotFilterWhenMessageDoesNotMatch() {
        CamelJobLogFilter filter = new CamelJobLogFilter();
        LogRecord record = record(
                Level.ERROR,
                "org.apache.camel.component.quartz.CamelJob",
                new RuntimeException("Another error")
        );

        assertTrue(filter.isLoggable(record));
    }

    @Test
    void shouldNotFilterWhenLoggerDoesNotMatch() {
        CamelJobLogFilter filter = new CamelJobLogFilter();
        LogRecord record = record(
                Level.ERROR,
                "org.apache.camel.component.quartz.OtherJob",
                new RuntimeException("No CamelContext could be found with name test-context")
        );

        assertTrue(filter.isLoggable(record));
    }

    @Test
    void shouldNotFilterWhenLevelDoesNotMatch() {
        CamelJobLogFilter filter = new CamelJobLogFilter();
        LogRecord record = record(
                Level.WARN,
                "org.apache.camel.component.quartz.CamelJob",
                new RuntimeException("No CamelContext could be found with name test-context")
        );

        assertTrue(filter.isLoggable(record));
    }

    private static LogRecord record(Level level, String loggerName, Throwable thrown) {
        LogRecord record = new LogRecord(level, "message");
        record.setLoggerName(loggerName);
        record.setThrown(thrown);
        return record;
    }
}
