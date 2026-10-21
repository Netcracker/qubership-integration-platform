package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkus.logging.LoggingFilter;
import org.jboss.logmanager.ExtLogRecord;
import org.qubership.integration.platform.engine.camel.scheduler.CamelJobLogFilter;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

// Writes "-" for each absent MDC field, as the classic engine's logback "%X{key:--}" does.
@LoggingFilter(name = "console-filter")
public class ConsoleLogFilter implements Filter {
    static final String ABSENT_MDC_VALUE = "-";

    private final CamelJobLogFilter camelJobLogFilter = new CamelJobLogFilter();

    @Override
    public boolean isLoggable(LogRecord logRecord) {
        if (!camelJobLogFilter.isLoggable(logRecord)) {
            return false;
        }
        if (logRecord instanceof ExtLogRecord extRecord) {
            JsonLogProvider.MDC_MAPPING.keySet().stream()
                    .filter(key -> extRecord.getMdc(key) == null)
                    .forEach(key -> extRecord.putMdc(key, ABSENT_MDC_VALUE));
        }
        return true;
    }
}
