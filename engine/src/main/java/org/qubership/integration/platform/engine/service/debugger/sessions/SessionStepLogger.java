package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;

public interface SessionStepLogger {
    void logAfter(SessionStepLogRecord logRecord, SessionLogDetails details);

    void logSessionStart(Session session, SessionLogDetails details);

    void recordStepAfter(SessionStepLogContext ctx);

    void recordStepAfterForStep(SessionStepLogContext ctx);
}
