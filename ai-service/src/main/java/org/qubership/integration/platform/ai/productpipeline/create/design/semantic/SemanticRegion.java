package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

  default String ownerNodeId() {
    return null;
  }

  /** Body work skips nested branch bodies and resumes at their declared continuation. */
  default Set<String> bodyNodeIds(List<SemanticExecutionEdge> edges, List<SemanticRegion> regions) {
    Map<String, List<String>> continuations = continuationsByOwner(regions, edges);
    Set<String> entries = new LinkedHashSet<>();
    Set<String> exits = new LinkedHashSet<>();
    Set<String> outside = new LinkedHashSet<>();
    switch (this) {
      case Sequence ignored -> { return Set.of(); }
      case Condition condition -> {
        condition.branches().forEach(branch -> {
          entries.add(branch.entryNodeId());
          exits.addAll(branch.exitNodeIds());
        });
        outside.add(condition.reconvergenceNodeId());
      }
      case Split split -> {
        split.branches().forEach(branch -> {
          entries.add(branch.entryNodeId());
          exits.addAll(branch.exitNodeIds());
        });
        outside.add(split.reconvergenceNodeId());
      }
      case Loop loop -> {
        entries.add(loop.bodyEntryNodeId());
        exits.addAll(loop.bodyExitNodeIds());
        outside.add(loop.exitNodeId());
      }
      case Retry retry -> {
        entries.add(retry.bodyEntryNodeId());
        exits.addAll(retry.bodyExitNodeIds());
        outside.add(retry.exhaustedNodeId());
      }
      case ErrorScope scope -> {
        entries.add(scope.tryEntryNodeId());
        if (scope.finallyEntryNodeId() != null) {
          entries.add(scope.finallyEntryNodeId());
        }
        scope.handlers().forEach(handler -> {
          entries.add(handler.entryNodeId());
          exits.addAll(handler.exitNodeIds());
        });
        exits.addAll(scope.exitNodeIds());
      }
    }
    Set<String> members = new LinkedHashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>(entries);
    while (!pending.isEmpty()) {
      String nodeId = pending.removeFirst();
      if (outside.contains(nodeId) || !members.add(nodeId) || exits.contains(nodeId)) {
        continue;
      }
      if (continuations.containsKey(nodeId)) {
        pending.addAll(continuations.get(nodeId));
        continue;
      }
      for (SemanticExecutionEdge edge : edges) {
        if (nodeId.equals(edge.sourceNodeId()) && edge.route() instanceof SemanticRoute.Sequence) {
          pending.add(edge.targetNodeId());
        }
      }
    }
    members.remove(ownerNodeId());
    return Set.copyOf(members);
  }

  /** A nested region's continuation belongs to its enclosing branch, not its own body. */
  static Map<String, List<String>> continuationsByOwner(
      List<SemanticRegion> regions, List<SemanticExecutionEdge> edges) {
    Map<String, List<String>> continuations = new LinkedHashMap<>();
    for (SemanticRegion region : regions) {
      String owner = region.ownerNodeId();
      if (owner == null) {
        continue;
      }
      Set<String> targets = new LinkedHashSet<>();
      String declared = switch (region) {
        case Condition condition -> condition.reconvergenceNodeId();
        case Split split -> split.mode() == SplitMode.SYNC ? split.reconvergenceNodeId() : null;
        case Loop loop -> loop.exitNodeId();
        case Retry retry -> retry.exhaustedNodeId();
        default -> null;
      };
      if (declared != null) {
        targets.add(declared);
      }
      for (SemanticExecutionEdge edge : edges) {
        if (owner.equals(edge.sourceNodeId()) && edge.route() instanceof SemanticRoute.Sequence) {
          targets.add(edge.targetNodeId());
        }
      }
      continuations.put(owner, List.copyOf(targets));
    }
    return Map.copyOf(continuations);
  }

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
      reconvergenceNodeId = DesignArtifacts.optionalText(reconvergenceNodeId);
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
      String reconvergenceNodeId,
      String mainBranchId)
      implements SemanticRegion {

    public Split {
      regionId = DesignArtifacts.requireText(regionId, "regionId");
      if (kind != SemanticRegionKind.SPLIT) {
        throw new IllegalArgumentException("Split kind must be SPLIT");
      }
      ownerNodeId = DesignArtifacts.requireText(ownerNodeId, "ownerNodeId");
      mode = DesignArtifacts.requireNonNull(mode, "mode");
      branches = DesignArtifacts.copyList(branches);
      reconvergenceNodeId = DesignArtifacts.optionalText(reconvergenceNodeId);
      mainBranchId = DesignArtifacts.optionalText(mainBranchId);
    }

    public Split(
        String regionId,
        String ownerNodeId,
        SplitMode mode,
        List<SemanticBranch.Split> branches,
        String reconvergenceNodeId) {
      this(regionId, SemanticRegionKind.SPLIT, ownerNodeId, mode, branches, reconvergenceNodeId, null);
    }

    public Split(
        String regionId,
        String ownerNodeId,
        SplitMode mode,
        List<SemanticBranch.Split> branches,
        String reconvergenceNodeId,
        String mainBranchId) {
      this(regionId, SemanticRegionKind.SPLIT, ownerNodeId, mode, branches, reconvergenceNodeId,
          mainBranchId);
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
