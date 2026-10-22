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
  @JsonSubTypes.Type(value = SemanticRoute.Reconverge.class, name = "RECONVERGE"),
  @JsonSubTypes.Type(value = SemanticRoute.LoopBody.class, name = "LOOP_BODY"),
  @JsonSubTypes.Type(value = SemanticRoute.LoopExit.class, name = "LOOP_EXIT"),
  @JsonSubTypes.Type(value = SemanticRoute.RetryAttempt.class, name = "RETRY_ATTEMPT"),
  @JsonSubTypes.Type(value = SemanticRoute.RetryExhausted.class, name = "RETRY_EXHAUSTED"),
  @JsonSubTypes.Type(value = SemanticRoute.TryPath.class, name = "TRY_PATH"),
  @JsonSubTypes.Type(value = SemanticRoute.CatchPath.class, name = "CATCH_PATH"),
  @JsonSubTypes.Type(value = SemanticRoute.FinallyPath.class, name = "FINALLY_PATH")
})
public sealed interface SemanticRoute
    permits SemanticRoute.Sequence,
        SemanticRoute.ConditionBranch,
        SemanticRoute.SplitBranch,
        SemanticRoute.Reconverge,
        SemanticRoute.LoopBody,
        SemanticRoute.LoopExit,
        SemanticRoute.RetryAttempt,
        SemanticRoute.RetryExhausted,
        SemanticRoute.TryPath,
        SemanticRoute.CatchPath,
        SemanticRoute.FinallyPath {

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

  record LoopBody(SemanticRouteKind kind) implements SemanticRoute {

    public LoopBody {
      if (kind != SemanticRouteKind.LOOP_BODY) {
        throw new IllegalArgumentException("LoopBody kind must be LOOP_BODY");
      }
    }

    public LoopBody() {
      this(SemanticRouteKind.LOOP_BODY);
    }
  }

  record LoopExit(SemanticRouteKind kind) implements SemanticRoute {

    public LoopExit {
      if (kind != SemanticRouteKind.LOOP_EXIT) {
        throw new IllegalArgumentException("LoopExit kind must be LOOP_EXIT");
      }
    }

    public LoopExit() {
      this(SemanticRouteKind.LOOP_EXIT);
    }
  }

  record RetryAttempt(SemanticRouteKind kind) implements SemanticRoute {

    public RetryAttempt {
      if (kind != SemanticRouteKind.RETRY_ATTEMPT) {
        throw new IllegalArgumentException("RetryAttempt kind must be RETRY_ATTEMPT");
      }
    }

    public RetryAttempt() {
      this(SemanticRouteKind.RETRY_ATTEMPT);
    }
  }

  record RetryExhausted(SemanticRouteKind kind) implements SemanticRoute {

    public RetryExhausted {
      if (kind != SemanticRouteKind.RETRY_EXHAUSTED) {
        throw new IllegalArgumentException("RetryExhausted kind must be RETRY_EXHAUSTED");
      }
    }

    public RetryExhausted() {
      this(SemanticRouteKind.RETRY_EXHAUSTED);
    }
  }

  record TryPath(SemanticRouteKind kind) implements SemanticRoute {

    public TryPath {
      if (kind != SemanticRouteKind.TRY_PATH) {
        throw new IllegalArgumentException("TryPath kind must be TRY_PATH");
      }
    }

    public TryPath() {
      this(SemanticRouteKind.TRY_PATH);
    }
  }

  record CatchPath(SemanticRouteKind kind, String handlerId) implements SemanticRoute {

    public CatchPath {
      if (kind != SemanticRouteKind.CATCH_PATH) {
        throw new IllegalArgumentException("CatchPath kind must be CATCH_PATH");
      }
      handlerId = DesignArtifacts.requireText(handlerId, "handlerId");
    }

    public CatchPath(String handlerId) {
      this(SemanticRouteKind.CATCH_PATH, handlerId);
    }
  }

  record FinallyPath(SemanticRouteKind kind) implements SemanticRoute {

    public FinallyPath {
      if (kind != SemanticRouteKind.FINALLY_PATH) {
        throw new IllegalArgumentException("FinallyPath kind must be FINALLY_PATH");
      }
    }

    public FinallyPath() {
      this(SemanticRouteKind.FINALLY_PATH);
    }
  }
}
