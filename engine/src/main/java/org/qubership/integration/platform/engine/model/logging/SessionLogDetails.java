package org.qubership.integration.platform.engine.model.logging;

public enum SessionLogDetails {
    SENDERS,
    FULL,
    OFF;

    public boolean isExchangeLogEnabled() {
        return this == SENDERS || this == FULL;
    }
}
