package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import org.qubership.integration.platform.ai.plan.workdocument.binding.CatalogResolution;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;

/**
 * The model and catalog the caller already configured. The harness records these values and does
 * not replace them.
 */
public record CheckpointSession(
    String provider,
    String model,
    boolean providerSwitched,
    WorkTaskModel modelClient,
    CatalogResolution catalog) {}
