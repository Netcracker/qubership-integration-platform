package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import java.nio.file.Path;
import java.time.Instant;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;

/**
 * One opt-in checkpoint invocation. The facade runs only when {@code invokeFacades} is true. A
 * non-null {@code publicationStore} is the durable blob store behind the run store.
 *
 * <p>Filling runs add the run id, resume flag, optional answer file, model-call ceiling, deadline,
 * and the offline sequential fake. Older checkpoints leave those unset.
 */
public record CheckpointRequest(
    String checkpoint,
    String caseId,
    Path report,
    Path fixtureRoot,
    boolean invokeFacades,
    ArtifactBlobStore publicationStore,
    String runId,
    boolean resume,
    Path inputFile,
    int maxModelCalls,
    Instant deadline,
    boolean sequentialFake) {

  public CheckpointRequest(
      String checkpoint,
      String caseId,
      Path report,
      Path fixtureRoot,
      boolean invokeFacades,
      ArtifactBlobStore publicationStore) {
    this(
        checkpoint,
        caseId,
        report,
        fixtureRoot,
        invokeFacades,
        publicationStore,
        "",
        false,
        null,
        0,
        null,
        false);
  }
}
