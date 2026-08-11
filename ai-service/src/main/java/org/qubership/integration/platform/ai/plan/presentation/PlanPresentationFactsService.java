package org.qubership.integration.platform.ai.plan.presentation;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.DecisionTrace;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Builds deterministic plan presentation facts from workspace artifacts. */
@ApplicationScoped
public class PlanPresentationFactsService {

  private static final String LIFECYCLE_CAPTURED_NOT_BUILT = "captured_not_built";
  private static final String IMPLEMENT_CTA =
      " Reply Implement it to materialize the chain in the runtime catalog.";
  /** Keep enough script body for required response literals (e.g. even/odd minute). */
  private static final int SCRIPT_OUTCOME_MAX_CHARS = 512;

  public PlanPresentationFacts build(SkillWorkspace workspace) {
    Objects.requireNonNull(workspace, "workspace");

    String userRequest = readUserRequest(workspace);
    ChainPlanGraph graph = readGraph(workspace);

    String chainName = graph != null && graph.chain() != null ? nullToEmpty(graph.chain().name()) : "";
    String chainDescription =
        graph != null && graph.chain() != null ? nullToEmpty(graph.chain().description()) : "";

    int nodeCount = graph != null && graph.nodes() != null ? graph.nodes().size() : 0;
    int edgeCount = graph != null && graph.edges() != null ? graph.edges().size() : 0;

    Map<String, ChainPlanNode> nodesById = indexNodes(graph);
    Set<String> compilerNodeIds = compilerNodeIds(nodesById);
    List<PlanPresentationNode> coreFlowNodes = coreNodes(nodesById, compilerNodeIds);
    List<PlanPresentationEdge> coreFlowEdges = coreEdges(graph, nodesById, compilerNodeIds);
    List<PlanCompilerAddition> compilerAdditions = describeCompilerAdditions(nodesById, compilerNodeIds);

    SelectedPattern pattern = readSelectedPattern(workspace);
    DecisionTrace trace = readDecisionTrace(workspace);
    ValidationResult validation = readValidation(workspace);
    SkillArtifactPayload.PlanCaptureOutcomePayload capture = readPlanCapture(workspace);
    StructuredPlanFacts structured = extractStructuredFacts(graph, workspace);

    return new PlanPresentationFacts(
        userRequest,
        chainName,
        chainDescription,
        nodeCount,
        edgeCount,
        coreFlowNodes,
        coreFlowEdges,
        compilerAdditions,
        pattern != null ? pattern.patternId() : null,
        pattern != null ? nullToEmpty(pattern.summary()) : null,
        trace != null ? nullToEmpty(trace.summary()) : null,
        validation != null ? validation.valid() : null,
        validation != null ? nullToEmpty(validation.summary()) : null,
        capture != null ? capture.captured() : null,
        capture != null ? nullToEmpty(capture.message()) : null,
        LIFECYCLE_CAPTURED_NOT_BUILT,
        structured.endpointFacts(),
        structured.branchFacts(),
        structured.scriptOutcomes(),
        structured.serviceBindings(),
        structured.negativeConstraints(),
        structured.skillOwnership());
  }

  /** Deterministic English fallback when the presenter agent is unavailable. */
  public String formatFallbackSummary(PlanPresentationFacts facts) {
    return formatPlanBody(facts) + IMPLEMENT_CTA;
  }

  /**
   * Deterministic English summary while the implementation plan awaits user approval. Omits the
   * post-approval Implement CTA used by {@link #formatFallbackSummary}.
   */
  public String formatPlanReviewSummary(PlanPresentationFacts facts) {
    return formatPlanBody(facts);
  }

  private static String formatPlanBody(PlanPresentationFacts facts) {
    Objects.requireNonNull(facts, "facts");

    String chainLabel =
        facts.chainName() != null && !facts.chainName().isBlank() ? facts.chainName() : "chain";

    StringBuilder sb = new StringBuilder();
    sb.append("Plan captured (not built in the catalog yet).");
    sb.append(" Chain \"").append(chainLabel).append("\": ");
    sb.append(facts.nodeCount()).append(" nodes, ").append(facts.edgeCount()).append(" edges.");

    if (!facts.coreFlowNodes().isEmpty()) {
      String coreTypes =
          facts.coreFlowNodes().stream()
              .map(PlanPresentationNode::type)
              .filter(type -> type != null && !type.isBlank())
              .distinct()
              .sorted()
              .collect(Collectors.joining(", "));
      if (!coreTypes.isEmpty()) {
        sb.append(" Core flow: ").append(coreTypes).append(".");
      }
    }

    if (!facts.compilerAdditions().isEmpty()) {
      sb.append(" Compiler additions: ");
      sb.append(
          facts.compilerAdditions().stream()
              .map(PlanCompilerAddition::description)
              .collect(Collectors.joining("; ")));
      sb.append(".");
    }

    if (facts.validationPassed() != null) {
      sb.append(" Validation: ").append(facts.validationPassed() ? "passed" : "failed").append(".");
    }

    if (facts.selectedPatternId() != null && !facts.selectedPatternId().isBlank()) {
      sb.append(" Pattern: ").append(facts.selectedPatternId()).append(".");
    } else {
      sb.append(" Pattern: none.");
    }

    appendFactList(sb, "Endpoints", facts.endpointFacts());
    appendFactList(sb, "Branches", facts.branchFacts());
    appendFactList(sb, "Scripts", facts.scriptOutcomes());
    appendFactList(sb, "Bindings", facts.serviceBindings());
    appendFactList(sb, "Excluded", facts.negativeConstraints());

    return sb.toString();
  }

  private static void appendFactList(StringBuilder sb, String label, List<String> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    sb.append(' ').append(label).append(": ").append(String.join("; ", facts)).append('.');
  }

  private static StructuredPlanFacts extractStructuredFacts(
      ChainPlanGraph graph, SkillWorkspace workspace) {
    List<String> endpoints = new ArrayList<>();
    List<String> branches = new ArrayList<>();
    List<String> scripts = new ArrayList<>();
    List<String> bindings = new ArrayList<>();
    List<String> negatives = new ArrayList<>();
    List<String> skills = new ArrayList<>();

    if (graph != null && graph.nodes() != null) {
      for (ChainPlanNode node : graph.nodes()) {
        String type = node.type() == null ? "" : node.type().trim();
        if ("http-trigger".equals(type)) {
          String method = propertyValue(node, "httpMethodRestrict");
          String path = propertyValue(node, "contextPath");
          String visibility = propertyValue(node, "externalRoute");
          if (method != null) {
            endpoints.add(method);
          }
          if (path != null) {
            endpoints.add(path);
          }
          if ("false".equalsIgnoreCase(visibility) || "internal".equalsIgnoreCase(visibility)) {
            endpoints.add("internal");
          } else if ("true".equalsIgnoreCase(visibility)) {
            endpoints.add("external");
          }
          String param = propertyValue(node, "queryParameters");
          if (param != null) {
            endpoints.add(param);
          }
        } else if ("if".equals(type) || "condition".equals(type)) {
          String condition = propertyValue(node, "condition");
          if (condition != null) {
            branches.add(condition);
          }
        } else if ("else".equals(type)) {
          String elseCondition = propertyValue(node, "condition");
          String elsePriority = propertyValue(node, "priority");
          if (elseCondition != null) {
            branches.add("else.condition=" + elseCondition);
          }
          if (elsePriority != null) {
            branches.add("else.priority=" + elsePriority);
          }
          if (elseCondition == null && elsePriority == null) {
            // Property-less else is valid CIP; do not emit "else:" (looks like else.condition).
            branches.add("else");
          }
        } else if ("script".equals(type)) {
          String script = propertyValue(node, "script");
          if (script != null && !script.isBlank()) {
            scripts.add(
                script.length() > SCRIPT_OUTCOME_MAX_CHARS
                    ? script.substring(0, SCRIPT_OUTCOME_MAX_CHARS)
                    : script);
          } else if (node.label() != null && !node.label().isBlank()) {
            scripts.add(node.label());
          }
        } else if ("service-call".equals(type)) {
          String op = propertyValue(node, "integrationOperationPath");
          if (op == null) {
            op = propertyValue(node, "operationName");
          }
          if (op == null) {
            op = node.label();
          }
          if (op != null && !op.isBlank()) {
            bindings.add(op);
          }
          String systemId = propertyValue(node, "systemId");
          if (systemId != null) {
            bindings.add("systemId=" + systemId);
          }
        }
      }
    }

    workspace
        .get(SkillArtifactType.REQUIREMENT_BRIEF)
        .map(a -> ((SkillArtifactPayload.RequirementBriefPayload) a.payload()).brief())
        .ifPresent(
            brief -> {
              for (var fact : brief.facts()) {
                if (fact.polarity()
                    == org.qubership.integration.platform.ai.plan.RequirementFactPolarity.NEGATIVE) {
                  negatives.add(fact.text());
                }
              }
              if (negatives.isEmpty()) {
                negatives.addAll(brief.constraints());
              }
            });

    return new StructuredPlanFacts(endpoints, branches, scripts, bindings, negatives, skills);
  }

  private static String propertyValue(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (var property : node.properties()) {
      if (property.key() != null && property.key().equals(key) && property.value() != null) {
        String value = property.value().trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  private record StructuredPlanFacts(
      List<String> endpointFacts,
      List<String> branchFacts,
      List<String> scriptOutcomes,
      List<String> serviceBindings,
      List<String> negativeConstraints,
      List<String> skillOwnership) {}

  private static String readUserRequest(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.RAW_USER_REQUEST)
        .map(a -> ((SkillArtifactPayload.RawUserRequestPayload) a.payload()).effectiveText())
        .orElse("");
  }

  private static ChainPlanGraph readGraph(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
        .orElse(null);
  }

  private static SelectedPattern readSelectedPattern(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.SELECTED_PATTERN)
        .map(a -> ((SkillArtifactPayload.SelectedPatternPayload) a.payload()).pattern())
        .orElse(null);
  }

  private static DecisionTrace readDecisionTrace(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.DECISION_TRACE)
        .map(a -> ((SkillArtifactPayload.DecisionTracePayload) a.payload()).trace())
        .orElse(null);
  }

  private static ValidationResult readValidation(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.PRE_BUILD_VALIDATION)
        .map(a -> ((SkillArtifactPayload.ValidationResultPayload) a.payload()).result())
        .orElse(null);
  }

  private static SkillArtifactPayload.PlanCaptureOutcomePayload readPlanCapture(
      SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.PLAN_CAPTURE_OUTCOME)
        .map(a -> (SkillArtifactPayload.PlanCaptureOutcomePayload) a.payload())
        .orElse(null);
  }

  private static Map<String, ChainPlanNode> indexNodes(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return Map.of();
    }
    Map<String, ChainPlanNode> byId = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node.nodeId() != null) {
        byId.put(node.nodeId(), node);
      }
    }
    return byId;
  }

  private static Set<String> compilerNodeIds(Map<String, ChainPlanNode> nodesById) {
    Set<String> compilerIds = new LinkedHashSet<>();
    for (ChainPlanNode node : nodesById.values()) {
      if (isCompilerAdditionNode(node, nodesById)) {
        compilerIds.add(node.nodeId());
      }
    }
    return compilerIds;
  }

  private static boolean isCompilerAdditionNode(
      ChainPlanNode node, Map<String, ChainPlanNode> nodesById) {
    if (ChainElementFamilies.isTryCatch(node.type())) {
      return true;
    }
    return isUnderCatchOrFinally(node, nodesById);
  }

  private static boolean isUnderCatchOrFinally(
      ChainPlanNode node, Map<String, ChainPlanNode> nodesById) {
    String parentId = node.parentNodeId();
    while (parentId != null) {
      ChainPlanNode parent = nodesById.get(parentId);
      if (parent == null) {
        break;
      }
      if ("catch-2".equals(parent.type()) || "finally-2".equals(parent.type())) {
        return true;
      }
      parentId = parent.parentNodeId();
    }
    return false;
  }

  private static List<PlanPresentationNode> coreNodes(
      Map<String, ChainPlanNode> nodesById, Set<String> compilerNodeIds) {
    List<PlanPresentationNode> core = new ArrayList<>();
    for (ChainPlanNode node : nodesById.values()) {
      if (compilerNodeIds.contains(node.nodeId())) {
        continue;
      }
      core.add(toPresentationNode(node));
    }
    return List.copyOf(core);
  }

  private static List<PlanPresentationEdge> coreEdges(
      ChainPlanGraph graph,
      Map<String, ChainPlanNode> nodesById,
      Set<String> compilerNodeIds) {
    if (graph == null || graph.edges() == null) {
      return List.of();
    }
    List<PlanPresentationEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      if (edge.fromNodeId() == null || edge.toNodeId() == null) {
        continue;
      }
      if (compilerNodeIds.contains(edge.fromNodeId())
          || compilerNodeIds.contains(edge.toNodeId())) {
        continue;
      }
      ChainPlanNode from = nodesById.get(edge.fromNodeId());
      ChainPlanNode to = nodesById.get(edge.toNodeId());
      edges.add(
          new PlanPresentationEdge(
              edge.fromNodeId(),
              edge.toNodeId(),
              from != null ? nullToEmpty(from.label()) : edge.fromNodeId(),
              to != null ? nullToEmpty(to.label()) : edge.toNodeId()));
    }
    return List.copyOf(edges);
  }

  private static List<PlanCompilerAddition> describeCompilerAdditions(
      Map<String, ChainPlanNode> nodesById, Set<String> compilerNodeIds) {
    if (compilerNodeIds.isEmpty()) {
      return List.of();
    }
    List<String> ehTypes =
        nodesById.values().stream()
            .filter(node -> compilerNodeIds.contains(node.nodeId()))
            .map(ChainPlanNode::type)
            .filter(type -> type != null && !type.isBlank())
            .distinct()
            .sorted()
            .toList();

    boolean hasWrapper =
        nodesById.values().stream().anyMatch(node -> "try-catch-finally-2".equals(node.type()));

    List<PlanCompilerAddition> additions = new ArrayList<>();
    if (hasWrapper) {
      additions.add(
          new PlanCompilerAddition(
              "error_handling_wrapper",
              "try-catch-finally-2 error-handling wrapper (GEN-04)",
              ehTypes));
    } else {
      additions.add(
          new PlanCompilerAddition(
              "compiler_nodes",
              "Compiler-added nodes: " + String.join(", ", ehTypes),
              ehTypes));
    }
    return List.copyOf(additions);
  }

  private static PlanPresentationNode toPresentationNode(ChainPlanNode node) {
    List<String> propertyFacts = new ArrayList<>();
    if (node.properties() != null) {
      for (var property : node.properties()) {
        if (property.key() == null || property.key().isBlank() || property.value() == null) {
          continue;
        }
        propertyFacts.add(property.key().trim() + "=" + property.value().trim());
      }
    }
    return new PlanPresentationNode(
        node.nodeId(), node.type(), node.label(), node.parentNodeId(), propertyFacts);
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }
}
