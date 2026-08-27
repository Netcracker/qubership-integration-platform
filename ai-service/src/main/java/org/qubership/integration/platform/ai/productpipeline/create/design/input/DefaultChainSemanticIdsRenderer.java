package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;

/**
 * Deterministic IDS renderer. One {@code sequenceDiagram} with {@code autonumber} per entry point,
 * ordered by {@link SemanticEntryPoint#order()}.
 */
@ApplicationScoped
public class DefaultChainSemanticIdsRenderer implements ChainSemanticIdsRenderer {

  static final String RENDERER_VERSION = "chain-semantic-ids-renderer@1";

  @Override
  public IdsDocument render(ChainSemanticRevision revision, CompilerContract contract) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(contract, "contract");
    String digest = CanonicalPayloadHash.sha256Hex(revision);
    String markdown = markdown(revision);
    return new IdsDocument(
        "1",
        IdsDocument.Mode.DERIVED,
        revision.revisionId(),
        digest,
        digest,
        RENDERER_VERSION,
        markdown);
  }

  private static String markdown(ChainSemanticRevision revision) {
    StringBuilder body = new StringBuilder();
    body.append("# Integration Design Specification\n\n");
    body.append("## Integration Process\n");
    List<SemanticEntryPoint> entries = new ArrayList<>(revision.entryPoints());
    entries.sort(
        Comparator.comparingInt(SemanticEntryPoint::order)
            .thenComparing(SemanticEntryPoint::entryPointId));
    Map<String, SemanticNode> nodes = indexNodes(revision);
    Map<String, List<SemanticExecutionEdge>> outgoing = indexOutgoing(revision);
    for (SemanticEntryPoint entry : entries) {
      body.append('\n');
      body.append("### Integration flow for CIP Chain - ").append(entryLabel(entry)).append("\n\n");
      body.append("```mermaid\n");
      body.append("sequenceDiagram\n");
      body.append("    autonumber\n");
      appendDiagram(body, revision, entry, nodes, outgoing);
      body.append("```\n");
    }
    return body.toString();
  }

  private static void appendDiagram(
      StringBuilder body,
      ChainSemanticRevision revision,
      SemanticEntryPoint entry,
      Map<String, SemanticNode> nodes,
      Map<String, List<SemanticExecutionEdge>> outgoing) {
    Set<String> reachable = reachable(entry.triggerNodeId(), outgoing);
    LinkedHashSet<String> participants = new LinkedHashSet<>();
    participants.add("Client");
    participants.add("CIP");
    for (String nodeId : reachable) {
      SemanticNode node = nodes.get(nodeId);
      if (node instanceof SemanticNode.ServiceCall call) {
        participants.add(participantId(call.operation()));
      }
    }
    for (String participant : participants) {
      body.append("    participant ").append(participant).append('\n');
    }
    SemanticNode trigger = nodes.get(entry.triggerNodeId());
    if (trigger instanceof SemanticNode.Trigger triggerNode) {
      body.append("    Client->>CIP: ").append(escape(triggerNode.capabilityKey())).append('\n');
    }
    for (SemanticRegion region : revision.regions()) {
      if (!ownerReachable(region, reachable)) {
        continue;
      }
      appendRegion(body, region, nodes);
    }
    List<SemanticExecutionEdge> edges = new ArrayList<>(revision.executionEdges());
    edges.sort(Comparator.comparing(SemanticExecutionEdge::edgeId));
    for (SemanticExecutionEdge edge : edges) {
      if (!reachable.contains(edge.sourceNodeId()) || !reachable.contains(edge.targetNodeId())) {
        continue;
      }
      if (edge.regionId() != null) {
        continue;
      }
      SemanticNode target = nodes.get(edge.targetNodeId());
      appendMessage(body, target);
    }
  }

  private static void appendRegion(
      StringBuilder body, SemanticRegion region, Map<String, SemanticNode> nodes) {
    switch (region) {
      case SemanticRegion.Condition condition -> {
        List<SemanticBranch.Condition> branches = condition.branches();
        if (!branches.isEmpty()) {
          boolean first = true;
          for (SemanticBranch.Condition branch : branches) {
            if (first) {
              body.append("    alt ").append(escape(branchLabel(branch))).append('\n');
              first = false;
            } else {
              body.append("    else ").append(escape(branchLabel(branch))).append('\n');
            }
            appendMessage(body, nodes.get(branch.entryNodeId()));
          }
          body.append("    end\n");
        }
      }
      case SemanticRegion.Loop loop -> {
        body.append("    loop ").append(escape(loop.policy().expression())).append('\n');
        appendMessage(body, nodes.get(loop.bodyEntryNodeId()));
        body.append("    end\n");
      }
      case SemanticRegion.Retry retry -> {
        body.append("    opt retry\n");
        appendMessage(body, nodes.get(retry.bodyEntryNodeId()));
        body.append("    end\n");
      }
      case SemanticRegion.ErrorScope error -> {
        appendMessage(body, nodes.get(error.tryEntryNodeId()));
        for (ErrorHandler handler : error.handlers()) {
          body.append("    opt catch ").append(escape(handler.exceptionClass())).append('\n');
          appendMessage(body, nodes.get(handler.entryNodeId()));
          body.append("    end\n");
        }
      }
      case SemanticRegion.Split split -> {
        body.append("    par split\n");
        for (SemanticBranch.Split branch : split.branches()) {
          appendMessage(body, nodes.get(branch.entryNodeId()));
        }
        body.append("    end\n");
      }
      case SemanticRegion.Sequence ignored -> {}
    }
  }

  private static void appendMessage(StringBuilder body, SemanticNode node) {
    if (node instanceof SemanticNode.ServiceCall call) {
      body.append("    CIP->>")
          .append(participantId(call.operation()))
          .append(": ")
          .append(escape(call.operation()))
          .append('\n');
      return;
    }
    if (node instanceof SemanticNode.Operation operation) {
      body.append("    CIP->>CIP: ").append(escape(operation.elementType())).append('\n');
    }
  }

  private static String branchLabel(SemanticBranch.Condition branch) {
    if (branch.predicate() != null && !branch.predicate().isBlank()) {
      return branch.predicate();
    }
    return branch.role() == ConditionBranchRole.ELSE ? "" : "condition";
  }

  private static boolean ownerReachable(SemanticRegion region, Set<String> reachable) {
    return switch (region) {
      case SemanticRegion.Sequence ignored -> false;
      case SemanticRegion.Condition condition -> reachable.contains(condition.ownerNodeId());
      case SemanticRegion.Split split -> reachable.contains(split.ownerNodeId());
      case SemanticRegion.Loop loop -> reachable.contains(loop.ownerNodeId());
      case SemanticRegion.Retry retry -> reachable.contains(retry.ownerNodeId());
      case SemanticRegion.ErrorScope error -> reachable.contains(error.ownerNodeId());
    };
  }

  private static Set<String> reachable(
      String start, Map<String, List<SemanticExecutionEdge>> outgoing) {
    Set<String> seen = new HashSet<>();
    ArrayList<String> stack = new ArrayList<>();
    stack.add(start);
    while (!stack.isEmpty()) {
      String nodeId = stack.removeLast();
      if (!seen.add(nodeId)) {
        continue;
      }
      for (SemanticExecutionEdge edge : outgoing.getOrDefault(nodeId, List.of())) {
        stack.add(edge.targetNodeId());
      }
    }
    return seen;
  }

  private static Map<String, SemanticNode> indexNodes(ChainSemanticRevision revision) {
    Map<String, SemanticNode> nodes = new LinkedHashMap<>();
    for (SemanticNode node : revision.nodes()) {
      nodes.put(node.nodeId(), node);
    }
    return nodes;
  }

  private static Map<String, List<SemanticExecutionEdge>> indexOutgoing(
      ChainSemanticRevision revision) {
    Map<String, List<SemanticExecutionEdge>> outgoing = new HashMap<>();
    List<SemanticExecutionEdge> edges = new ArrayList<>(revision.executionEdges());
    edges.sort(Comparator.comparing(SemanticExecutionEdge::edgeId));
    for (SemanticExecutionEdge edge : edges) {
      outgoing.computeIfAbsent(edge.sourceNodeId(), key -> new ArrayList<>()).add(edge);
    }
    return outgoing;
  }

  private static String entryLabel(SemanticEntryPoint entry) {
    if (entry.presentation() != null
        && entry.presentation().label() != null
        && !entry.presentation().label().isBlank()) {
      return entry.presentation().label();
    }
    return entry.entryPointId();
  }

  private static String participantId(String operation) {
    String raw = operation == null || operation.isBlank() ? "Service" : operation.trim();
    StringBuilder id = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        id.append(ch);
      }
    }
    return id.isEmpty() ? "Service" : id.toString();
  }

  private static String escape(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.replace('"', '\'');
  }
}
