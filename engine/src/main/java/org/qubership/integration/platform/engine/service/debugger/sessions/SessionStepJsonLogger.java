package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;

public interface SessionStepJsonLogger {

    /**
     * Emits a single JSON log record for an already-processed ("after") session element.
     * Implementations decide whether to skip the record based on {@code details}.
     */
    void logAfter(SessionStepLogRecord record, SessionLogDetails details);
}
