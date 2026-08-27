package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Typed execution role of a control-flow edge. */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = SemanticRoute.Sequence.class, name = "SEQUENCE"),
  @JsonSubTypes.Type(value = SemanticRoute.ConditionBranch.class, name = "CONDITION_BRANCH"),
  @JsonSubTypes.Type(value = SemanticRoute.SplitBranch.class, name = "SPLIT_BRANCH"),
  @JsonSubTypes.Type(value = SemanticRoute.Reconverge.class, name = "RECONVERGE")
})
public sealed interface SemanticRoute
    permits SemanticRoute.Sequence,
        SemanticRoute.ConditionBranch,
        SemanticRoute.SplitBranch,
        SemanticRoute.Reconverge {

  SemanticRouteKind kind();

  record Sequence(SemanticRouteKind kind) implements SemanticRoute {

    public Sequence {
      if (kind != SemanticRouteKind.SEQUENCE) {
        throw new IllegalArgumentException("Sequence kind must be SEQUENCE");
      }
    }

    public Sequence() {
      this(SemanticRouteKind.SEQUENCE);
    }
  }

  record ConditionBranch(SemanticRouteKind kind, String branchId) implements SemanticRoute {

    public ConditionBranch {
      if (kind != SemanticRouteKind.CONDITION_BRANCH) {
        throw new IllegalArgumentException("ConditionBranch kind must be CONDITION_BRANCH");
      }
      branchId = DesignArtifacts.requireText(branchId, "branchId");
    }

    public ConditionBranch(String branchId) {
      this(SemanticRouteKind.CONDITION_BRANCH, branchId);
    }
  }

  record SplitBranch(SemanticRouteKind kind, String branchId) implements SemanticRoute {

    public SplitBranch {
      if (kind != SemanticRouteKind.SPLIT_BRANCH) {
        throw new IllegalArgumentException("SplitBranch kind must be SPLIT_BRANCH");
      }
      branchId = DesignArtifacts.requireText(branchId, "branchId");
    }

    public SplitBranch(String branchId) {
      this(SemanticRouteKind.SPLIT_BRANCH, branchId);
    }
  }

  record Reconverge(SemanticRouteKind kind, List<String> branchIds) implements SemanticRoute {

    public Reconverge {
      if (kind != SemanticRouteKind.RECONVERGE) {
        throw new IllegalArgumentException("Reconverge kind must be RECONVERGE");
      }
      branchIds = DesignArtifacts.copyList(branchIds);
    }

    public Reconverge(List<String> branchIds) {
      this(SemanticRouteKind.RECONVERGE, branchIds);
    }
  }
}
