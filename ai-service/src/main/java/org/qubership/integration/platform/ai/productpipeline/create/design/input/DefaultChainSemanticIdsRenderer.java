package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;

/**
 * Deterministic IDS renderer. One {@code sequenceDiagram} with {@code autonumber} per entry point,
 * ordered by {@link SemanticEntryPoint#order()}.
 */
@ApplicationScoped
public class DefaultChainSemanticIdsRenderer implements ChainSemanticIdsRenderer {

  static final String RENDERER_VERSION = "chain-semantic-ids-renderer@3";

  @Override
  public IdsDocument render(ChainSemanticRevision revision, CompilerContract contract) {
    return render(revision, contract, null);
  }

  @Override
  public IdsDocument render(
      ChainSemanticRevision revision, CompilerContract contract, RequirementBrief brief) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(contract, "contract");
    String digest = CanonicalPayloadHash.sha256Hex(revision);
    String markdown = markdown(revision, brief);
    return new IdsDocument(
        "1",
        IdsDocument.Mode.DERIVED,
        revision.revisionId(),
        digest,
        digest,
        RENDERER_VERSION,
        markdown);
  }

  private static String markdown(ChainSemanticRevision revision, RequirementBrief brief) {
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
      appendDiagram(body, revision, entry, nodes, outgoing, brief);
      body.append("```\n");
    }
    return body.toString();
  }

  private static void appendDiagram(
      StringBuilder body,
      ChainSemanticRevision revision,
      SemanticEntryPoint entry,
      Map<String, SemanticNode> nodes,
      Map<String, List<SemanticExecutionEdge>> outgoing,
      RequirementBrief brief) {
    Set<String> reachable = reachable(entry.triggerNodeId(), outgoing);
    List<SemanticExecutionEdge> edges = executionOrder(entry.triggerNodeId(), outgoing);
    Map<String, Interaction> interactions = indexInteractions(brief);
    LinkedHashMap<String, String> externalParticipants = new LinkedHashMap<>();
    Map<String, CallPresentation> calls = new HashMap<>();
    SemanticNode trigger = nodes.get(entry.triggerNodeId());
    CallPresentation triggerPresentation =
        trigger instanceof SemanticNode.Trigger triggerNode
            ? presentation(
                triggerNode.interactionId(),
                "Client",
                triggerNode.capabilityKey(),
                interactions,
                externalParticipants)
            : presentation("", "Client", "", interactions, externalParticipants);
    for (SemanticExecutionEdge edge : edges) {
      SemanticNode node = nodes.get(edge.targetNodeId());
      if (node instanceof SemanticNode.ServiceCall call) {
        calls.put(
            call.nodeId(),
            presentation(
                call.serviceCallId(),
                call.operation(),
                call.operation(),
                interactions,
                externalParticipants));
      }
    }
    appendParticipant(body, triggerPresentation, externalParticipants);
    body.append("    participant CIP\n");
    for (Map.Entry<String, String> participant : externalParticipants.entrySet()) {
      if (!participant.getKey().equals(triggerPresentation.participantId())) {
        body.append("    participant ")
            .append(participant.getKey())
            .append(" as ")
            .append(escape(participant.getValue()))
            .append('\n');
      }
    }
    if (trigger instanceof SemanticNode.Trigger) {
      body.append("    ")
          .append(triggerPresentation.participantId())
          .append("->>CIP: ")
          .append(escape(triggerPresentation.operation()))
          .append('\n');
    }
    for (SemanticRegion region : revision.regions()) {
      if (!ownerReachable(region, reachable)) {
        continue;
      }
      appendRegion(body, region, nodes, calls);
    }
    for (SemanticExecutionEdge edge : edges) {
      if (edge.regionId() != null) {
        continue;
      }
      SemanticNode target = nodes.get(edge.targetNodeId());
      appendMessage(body, target, calls);
    }
  }

  private static List<SemanticExecutionEdge> executionOrder(
      String start, Map<String, List<SemanticExecutionEdge>> outgoing) {
    List<SemanticExecutionEdge> ordered = new ArrayList<>();
    List<String> queue = new ArrayList<>();
    Set<String> expanded = new HashSet<>();
    queue.add(start);
    for (int index = 0; index < queue.size(); index++) {
      String sourceNodeId = queue.get(index);
      if (!expanded.add(sourceNodeId)) {
        continue;
      }
      for (SemanticExecutionEdge edge : outgoing.getOrDefault(sourceNodeId, List.of())) {
        ordered.add(edge);
        queue.add(edge.targetNodeId());
      }
    }
    return ordered;
  }

  private static void appendRegion(
      StringBuilder body,
      SemanticRegion region,
      Map<String, SemanticNode> nodes,
      Map<String, CallPresentation> calls) {
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
            appendMessage(body, nodes.get(branch.entryNodeId()), calls);
          }
          body.append("    end\n");
        }
      }
      case SemanticRegion.Loop loop -> {
        body.append("    loop ").append(escape(loop.policy().expression())).append('\n');
        appendMessage(body, nodes.get(loop.bodyEntryNodeId()), calls);
        body.append("    end\n");
      }
      case SemanticRegion.Retry retry -> {
        body.append("    opt retry\n");
        appendMessage(body, nodes.get(retry.bodyEntryNodeId()), calls);
        body.append("    end\n");
      }
      case SemanticRegion.ErrorScope error -> {
        appendMessage(body, nodes.get(error.tryEntryNodeId()), calls);
        for (ErrorHandler handler : error.handlers()) {
          body.append("    opt catch ").append(escape(handler.exceptionClass())).append('\n');
          appendMessage(body, nodes.get(handler.entryNodeId()), calls);
          body.append("    end\n");
        }
      }
      case SemanticRegion.Split split -> {
        body.append("    par split\n");
        for (SemanticBranch.Split branch : split.branches()) {
          appendMessage(body, nodes.get(branch.entryNodeId()), calls);
        }
        body.append("    end\n");
      }
      case SemanticRegion.Sequence ignored -> {}
    }
  }

  private static void appendMessage(
      StringBuilder body, SemanticNode node, Map<String, CallPresentation> calls) {
    if (node instanceof SemanticNode.ServiceCall call) {
      CallPresentation presentation =
          calls.getOrDefault(
              call.nodeId(), new CallPresentation(participantId(call.operation()), call.operation()));
      body.append("    CIP->>")
          .append(presentation.participantId())
          .append(": ")
          .append(escape(presentation.operation()))
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

  private static Map<String, Interaction> indexInteractions(RequirementBrief brief) {
    Map<String, Interaction> interactions = new HashMap<>();
    if (brief == null) {
      return interactions;
    }
    for (Interaction interaction : brief.flow().interactions()) {
      interactions.put(interaction.interactionId(), interaction);
    }
    return interactions;
  }

  private static CallPresentation presentation(
      String interactionId,
      String fallbackParticipant,
      String fallbackOperation,
      Map<String, Interaction> interactions,
      LinkedHashMap<String, String> externalParticipants) {
    Interaction interaction = interactions.get(interactionId);
    String participant =
        interaction == null || interaction.participant().isBlank()
            ? fallbackParticipant
            : interaction.participant();
    String operation =
        interaction == null || interaction.operation().isBlank()
            ? fallbackOperation
            : interaction.operation();
    for (Map.Entry<String, String> registered : externalParticipants.entrySet()) {
      if (registered.getValue().equals(participant)) {
        return new CallPresentation(registered.getKey(), operation);
      }
    }
    String participantId = "Service" + (externalParticipants.size() + 1);
    externalParticipants.put(participantId, participant);
    return new CallPresentation(participantId, operation);
  }

  private static void appendParticipant(
      StringBuilder body,
      CallPresentation presentation,
      Map<String, String> externalParticipants) {
    body.append("    participant ")
        .append(presentation.participantId())
        .append(" as ")
        .append(escape(externalParticipants.get(presentation.participantId())))
        .append('\n');
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

  private record CallPresentation(String participantId, String operation) {}
}
