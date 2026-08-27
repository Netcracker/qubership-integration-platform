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
  @JsonSubTypes.Type(value = SemanticRegion.Split.class, name = "SPLIT")
})
public sealed interface SemanticRegion
    permits SemanticRegion.Sequence, SemanticRegion.Condition, SemanticRegion.Split {

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
}
