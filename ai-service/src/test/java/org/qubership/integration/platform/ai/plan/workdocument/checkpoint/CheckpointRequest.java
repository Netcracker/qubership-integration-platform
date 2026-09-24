package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import java.nio.file.Path;

/** One opt-in checkpoint invocation. The facade runs only when {@code invokeFacades} is true. */
public record CheckpointRequest(
    String checkpoint, String caseId, Path report, Path fixtureRoot, boolean invokeFacades) {}
