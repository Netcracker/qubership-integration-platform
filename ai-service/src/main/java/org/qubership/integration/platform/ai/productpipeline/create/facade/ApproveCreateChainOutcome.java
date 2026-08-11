package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.List;
import java.util.Objects;

/** Typed result of an exact create-chain approval command. */
public sealed interface ApproveCreateChainOutcome {

  record Accepted(List<CreateChainEvent> events, CreateChainExecutionSnapshot snapshot)
      implements ApproveCreateChainOutcome {

    public Accepted {
      events = events == null ? List.of() : List.copyOf(events);
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  record StaleRevision(long expectedRevision, long actualRevision)
      implements ApproveCreateChainOutcome {}

  record WrongArtifactHash(String expectedHash, String providedHash)
      implements ApproveCreateChainOutcome {

    public WrongArtifactHash {
      Objects.requireNonNull(expectedHash, "expectedHash");
      Objects.requireNonNull(providedHash, "providedHash");
    }
  }

  record WrongArtifactType(String expectedType, String providedType)
      implements ApproveCreateChainOutcome {

    public WrongArtifactType {
      Objects.requireNonNull(expectedType, "expectedType");
      Objects.requireNonNull(providedType, "providedType");
    }
  }

  record DuplicateApproval() implements ApproveCreateChainOutcome {}

  record NotWaitingForApproval(CreateChainExecutionStatus status)
      implements ApproveCreateChainOutcome {

    public NotWaitingForApproval {
      Objects.requireNonNull(status, "status");
    }
  }

  record ImplementationBlocked(ImplementationBlockedRecovery recovery)
      implements ApproveCreateChainOutcome {

    public ImplementationBlocked {
      Objects.requireNonNull(recovery, "recovery");
    }
  }

  record NonRecoverableFailure(String reason) implements ApproveCreateChainOutcome {

    public NonRecoverableFailure {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
