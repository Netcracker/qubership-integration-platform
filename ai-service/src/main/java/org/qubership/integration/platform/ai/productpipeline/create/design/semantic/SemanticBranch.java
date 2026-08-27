package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Typed branch inside a condition or split region. */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = SemanticBranch.Condition.class, name = "CONDITION"),
  @JsonSubTypes.Type(value = SemanticBranch.Split.class, name = "SPLIT")
})
public sealed interface SemanticBranch permits SemanticBranch.Condition, SemanticBranch.Split {

  String branchId();

  SemanticBranchKind kind();

  record Condition(
      String branchId,
      SemanticBranchKind kind,
      ConditionBranchRole role,
      String predicate,
      int priority,
      String entryNodeId,
      List<String> exitNodeIds)
      implements SemanticBranch {

    public Condition {
      branchId = DesignArtifacts.requireText(branchId, "branchId");
      if (kind != SemanticBranchKind.CONDITION) {
        throw new IllegalArgumentException("Condition branch kind must be CONDITION");
      }
      role = DesignArtifacts.requireNonNull(role, "role");
      predicate = DesignArtifacts.optionalText(predicate);
      entryNodeId = DesignArtifacts.requireText(entryNodeId, "entryNodeId");
      exitNodeIds = DesignArtifacts.copyList(exitNodeIds);
    }

    public Condition(
        String branchId,
        ConditionBranchRole role,
        String predicate,
        int priority,
        String entryNodeId,
        List<String> exitNodeIds) {
      this(
          branchId,
          SemanticBranchKind.CONDITION,
          role,
          predicate,
          priority,
          entryNodeId,
          exitNodeIds);
    }
  }

  record Split(
      String branchId,
      SemanticBranchKind kind,
      int order,
      String entryNodeId,
      List<String> exitNodeIds)
      implements SemanticBranch {

    public Split {
      branchId = DesignArtifacts.requireText(branchId, "branchId");
      if (kind != SemanticBranchKind.SPLIT) {
        throw new IllegalArgumentException("Split branch kind must be SPLIT");
      }
      entryNodeId = DesignArtifacts.requireText(entryNodeId, "entryNodeId");
      exitNodeIds = DesignArtifacts.copyList(exitNodeIds);
    }

    public Split(String branchId, int order, String entryNodeId, List<String> exitNodeIds) {
      this(branchId, SemanticBranchKind.SPLIT, order, entryNodeId, exitNodeIds);
    }
  }
}
