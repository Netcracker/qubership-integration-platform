package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/**
 * Typed control-flow region. A reconvergence node is shared downstream invoked independently, not
 * a barrier join.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = SemanticRegion.Sequence.class, name = "SEQUENCE"),
  @JsonSubTypes.Type(value = SemanticRegion.Condition.class, name = "CONDITION"),
  @JsonSubTypes.Type(value = SemanticRegion.Split.class, name = "SPLIT"),
  @JsonSubTypes.Type(value = SemanticRegion.Loop.class, name = "LOOP"),
  @JsonSubTypes.Type(value = SemanticRegion.Retry.class, name = "RETRY"),
  @JsonSubTypes.Type(value = SemanticRegion.ErrorScope.class, name = "ERROR_SCOPE")
})
public sealed interface SemanticRegion
    permits SemanticRegion.Sequence,
        SemanticRegion.Condition,
        SemanticRegion.Split,
        SemanticRegion.Loop,
        SemanticRegion.Retry,
        SemanticRegion.ErrorScope {

  String regionId();

  SemanticRegionKind kind();

  record Sequence(String regionId, SemanticRegionKind kind, List<String> memberNodeIds)
      implements SemanticRegion {

    public Sequence {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.SEQUENCE) {
        throw new IllegalArgumentException("Sequence kind must be SEQUENCE");
      }
      memberNodeIds = DesignArtifacts.copyList(memberNodeIds);
    }

    public Sequence(String regionId, List<String> memberNodeIds) {
      this(regionId, SemanticRegionKind.SEQUENCE, memberNodeIds);
    }
  }

  record Condition(
      String regionId,
      SemanticRegionKind kind,
      String ownerNodeId,
      List<SemanticBranch.Condition> branches,
      String reconvergenceNodeId)
      implements SemanticRegion {

    public Condition {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.CONDITION) {
        throw new IllegalArgumentException("Condition kind must be CONDITION");
      }
      ownerNodeId = DesignArtifacts.requireText(ownerNodeId, "ownerNodeId");
      branches = DesignArtifacts.copyList(branches);
      reconvergenceNodeId = DesignArtifacts.nullableTrimmed(reconvergenceNodeId);
    }

    public Condition(
        String regionId,
        String ownerNodeId,
        List<SemanticBranch.Condition> branches,
        String reconvergenceNodeId) {
      this(regionId, SemanticRegionKind.CONDITION, ownerNodeId, branches, reconvergenceNodeId);
    }
  }

  record Split(
      String regionId,
      SemanticRegionKind kind,
      String ownerNodeId,
      SplitMode mode,
      List<SemanticBranch.Split> branches,
      String reconvergenceNodeId)
      implements SemanticRegion {

    public Split {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.SPLIT) {
        throw new IllegalArgumentException("Split kind must be SPLIT");
      }
      ownerNodeId = DesignArtifacts.requireText(ownerNodeId, "ownerNodeId");
      mode = DesignArtifacts.requireNonNull(mode, "mode");
      branches = DesignArtifacts.copyList(branches);
      reconvergenceNodeId = DesignArtifacts.nullableTrimmed(reconvergenceNodeId);
    }

    public Split(
        String regionId,
        String ownerNodeId,
        SplitMode mode,
        List<SemanticBranch.Split> branches,
        String reconvergenceNodeId) {
      this(regionId, SemanticRegionKind.SPLIT, ownerNodeId, mode, branches, reconvergenceNodeId);
    }
  }

  record Loop(
      String regionId,
      SemanticRegionKind kind,
      String ownerNodeId,
      String bodyEntryNodeId,
      List<String> bodyExitNodeIds,
      String exitNodeId,
      LoopPolicy policy)
      implements SemanticRegion {

    public Loop {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.LOOP) {
        throw new IllegalArgumentException("Loop kind must be LOOP");
      }
      ownerNodeId = DesignArtifacts.requireText(ownerNodeId, "ownerNodeId");
      bodyEntryNodeId = DesignArtifacts.requireText(bodyEntryNodeId, "bodyEntryNodeId");
      bodyExitNodeIds = DesignArtifacts.copyList(bodyExitNodeIds);
      exitNodeId = DesignArtifacts.requireText(exitNodeId, "exitNodeId");
      policy = DesignArtifacts.requireNonNull(policy, "policy");
    }

    public Loop(
        String regionId,
        String ownerNodeId,
        String bodyEntryNodeId,
        List<String> bodyExitNodeIds,
        String exitNodeId,
        LoopPolicy policy) {
      this(
          regionId,
          SemanticRegionKind.LOOP,
          ownerNodeId,
          bodyEntryNodeId,
          bodyExitNodeIds,
          exitNodeId,
          policy);
    }
  }

  record Retry(
      String regionId,
      SemanticRegionKind kind,
      String ownerNodeId,
      String bodyEntryNodeId,
      List<String> bodyExitNodeIds,
      String exhaustedNodeId,
      RetryPolicy policy)
      implements SemanticRegion {

    public Retry {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.RETRY) {
        throw new IllegalArgumentException("Retry kind must be RETRY");
      }
      ownerNodeId = DesignArtifacts.requireText(ownerNodeId, "ownerNodeId");
      bodyEntryNodeId = DesignArtifacts.requireText(bodyEntryNodeId, "bodyEntryNodeId");
      bodyExitNodeIds = DesignArtifacts.copyList(bodyExitNodeIds);
      exhaustedNodeId = DesignArtifacts.requireText(exhaustedNodeId, "exhaustedNodeId");
      policy = DesignArtifacts.requireNonNull(policy, "policy");
    }

    public Retry(
        String regionId,
        String ownerNodeId,
        String bodyEntryNodeId,
        List<String> bodyExitNodeIds,
        String exhaustedNodeId,
        RetryPolicy policy) {
      this(
          regionId,
          SemanticRegionKind.RETRY,
          ownerNodeId,
          bodyEntryNodeId,
          bodyExitNodeIds,
          exhaustedNodeId,
          policy);
    }
  }

  record ErrorScope(
      String regionId,
      SemanticRegionKind kind,
      String ownerNodeId,
      String tryEntryNodeId,
      List<ErrorHandler> handlers,
      String finallyEntryNodeId,
      List<String> exitNodeIds)
      implements SemanticRegion {

    public ErrorScope {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.ERROR_SCOPE) {
        throw new IllegalArgumentException("ErrorScope kind must be ERROR_SCOPE");
      }
      ownerNodeId = DesignArtifacts.requireText(ownerNodeId, "ownerNodeId");
      tryEntryNodeId = DesignArtifacts.requireText(tryEntryNodeId, "tryEntryNodeId");
      handlers = DesignArtifacts.copyList(handlers);
      finallyEntryNodeId = DesignArtifacts.nullableTrimmed(finallyEntryNodeId);
      exitNodeIds = DesignArtifacts.copyList(exitNodeIds);
    }

    public ErrorScope(
        String regionId,
        String ownerNodeId,
        String tryEntryNodeId,
        List<ErrorHandler> handlers,
        String finallyEntryNodeId,
        List<String> exitNodeIds) {
      this(
          regionId,
          SemanticRegionKind.ERROR_SCOPE,
          ownerNodeId,
          tryEntryNodeId,
          handlers,
          finallyEntryNodeId,
          exitNodeIds);
    }
  }
}
