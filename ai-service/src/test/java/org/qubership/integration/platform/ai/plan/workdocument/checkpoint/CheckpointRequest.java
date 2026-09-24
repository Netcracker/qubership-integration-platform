package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import java.nio.file.Path;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;

/**
 * One opt-in checkpoint invocation. The facade runs only when {@code invokeFacades} is true. A
 * non-null {@code publicationStore} is the durable blob store behind the run store.
 */
public record CheckpointRequest(
    String checkpoint,
    String caseId,
    Path report,
    Path fixtureRoot,
    boolean invokeFacades,
    ArtifactBlobStore publicationStore) {}
