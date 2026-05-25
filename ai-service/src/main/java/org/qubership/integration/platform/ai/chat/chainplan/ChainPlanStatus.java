package org.qubership.integration.platform.ai.chat.chainplan;

import java.time.Instant;

/** Lightweight snapshot of chain plan state for REST/UI (no full plan JSON). */
public record ChainPlanStatus(
    boolean hasActivePlan,
    boolean approved,
    String planId,
    String chainName,
    Instant updatedAt,
    int openItemCount) {}
