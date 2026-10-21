package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Claim;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Owner;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Step;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanContractValidator.TargetKey;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Builds the complete plan from approved targets and the pinned compiler DAG. */
public final class DesignPlanCaptureAdapter {

  public static final String SCHEMA_VERSION = "design-plan-contract/v2";

  public DesignPlanContract adapt(
      DesignPlanCapture capture, ChainSemanticRevision revision, RequirementBrief brief,
      CompilerRunPin pin, String apiRelease) {
    Objects.requireNonNull(capture, "capture");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(pin, "pin");
    Map<TargetKey, String> required = DesignPlanContractValidator.expectedOwners(
        revision, brief, DesignPlanContractValidator.BindingPolicy.CATALOG_FIRST);
    if (required.keySet().stream().filter(key -> key.kind() == TargetKind.ENTRY_POINT).count()
        != revision.entryPoints().size()) {
      throw new PlannerContractException("An entry point has no resolved producer owner.");
    }
    for (var target : required.entrySet()) {
      if (target.getKey().kind() == TargetKind.CATALOG_BINDING) {
        throw new PlannerContractException(
            "Resolve catalog operation for " + target.getKey().id() + " before planning.");
      }
    }

    Map<TargetKey, String> summaries = summaries(capture, required);
    Map<String, ResolvedCompilerNode> pinned = new LinkedHashMap<>();
    for (ResolvedCompilerNode node : pin.resolvedDag().nodes()) {
      pinned.put(node.skillId(), node);
    }
    for (String support : List.of("cip-structure-generator", "cip-chain-assembler")) {
      if (!pinned.containsKey(support)) {
        throw new PlannerContractException("Pinned compiler DAG lacks " + support + ".");
      }
    }
    Set<String> selected = new LinkedHashSet<>(required.values());
    selected.add("cip-structure-generator");
    for (String owner : selected) {
      if (!pinned.containsKey(owner)) {
        throw new PlannerContractException("Pinned compiler DAG lacks producer " + owner + ".");
      }
    }

    List<String> owners = orderedOwners(selected, pinned);
    List<Step> steps = new ArrayList<>();
    for (String owner : owners) {
      List<TargetKey> targets = required.entrySet().stream()
          .filter(entry -> owner.equals(entry.getValue()))
          .map(Map.Entry::getKey).toList();
      if (targets.isEmpty()) {
        append(steps, "Prepare chain structure", owner, List.of());
      } else {
        for (TargetKey target : targets) {
          String summary = summaries.getOrDefault(target,
              "Configure " + target.kind().name().toLowerCase(java.util.Locale.ROOT)
                  .replace('_', ' ') + " " + target.id());
          append(steps, summary, owner,
              List.of(new Claim(target.kind(), target.id(), ClaimRole.PRODUCER)));
        }
      }
    }
    List<Claim> references = required.keySet().stream()
        .map(target -> new Claim(target.kind(), target.id(), ClaimRole.REFERENCE)).toList();
    append(steps, "Connect generated elements", "cip-structure-generator", references);
    append(steps, "Assemble chain", "cip-chain-assembler", List.of());
    append(steps, "Validate chain", DesignPlanProjector.CHAIN_VALIDATOR_SKILL_ID, List.of());

    String contractId = semanticContractId(pin.subjectSha256(), steps);
    DesignPlanContract contract = new DesignPlanContract(
        SCHEMA_VERSION, contractId, revision.revisionId(), pin.subjectSha256(), apiRelease,
        steps);
    List<DesignPlanContractFinding> findings = new DesignPlanContractValidator()
        .findings(contract, revision, brief, pin);
    if (!findings.isEmpty()) {
      throw new PlannerContractException(DesignPlanContractValidator.format(findings), findings);
    }
    return contract;
  }

  private static Map<TargetKey, String> summaries(
      DesignPlanCapture capture, Map<TargetKey, String> required) {
    Map<TargetKey, String> summaries = new HashMap<>();
    for (int i = 0; i < capture.notes().size(); i++) {
      DesignPlanCapture.Note note = capture.notes().get(i);
      if (note == null || note.targetKind() == null || note.targetId() == null
          || note.targetId().isBlank() || note.summary() == null || note.summary().isBlank()) {
        throw new IllegalArgumentException("Note " + i + " needs a target and summary.");
      }
      TargetKey key = new TargetKey(note.targetKind(), note.targetId().trim());
      if (!required.containsKey(key)) {
        throw new IllegalArgumentException("Unknown planning target " + key + ".");
      }
      if (summaries.putIfAbsent(key, note.summary().trim()) != null) {
        throw new IllegalArgumentException("Duplicate planning note for " + key + ".");
      }
    }
    return summaries;
  }

  private static List<String> orderedOwners(
      Set<String> selected, Map<String, ResolvedCompilerNode> pinned) {
    List<String> ordered = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    for (String owner : selected) {
      visit(owner, selected, pinned, visited, new HashSet<>(), ordered);
    }
    return ordered;
  }

  private static void visit(
      String owner, Set<String> selected, Map<String, ResolvedCompilerNode> pinned,
      Set<String> visited, Set<String> visiting, List<String> ordered) {
    if (visited.contains(owner)) {
      return;
    }
    if (!visiting.add(owner)) {
      throw new PlannerContractException("Pinned compiler DAG contains a cycle at " + owner + ".");
    }
    for (String dependency : pinned.get(owner).dependsOn()) {
      if (selected.contains(dependency)) {
        visit(dependency, selected, pinned, visited, visiting, ordered);
      }
    }
    visiting.remove(owner);
    visited.add(owner);
    ordered.add(owner);
  }

  private static void append(
      List<Step> steps, String summary, String owner, List<Claim> claims) {
    String stepId = "plan-" + String.format(java.util.Locale.ROOT, "%03d", steps.size() + 1);
    List<String> dependencies = steps.isEmpty()
        ? List.of() : List.of(steps.getLast().stepId());
    steps.add(new Step(stepId, summary, new Owner(OwnerKind.SKILL, owner), claims,
        dependencies));
  }

  static String semanticContractId(
      String semanticRevisionHash, List<DesignPlanContract.Step> steps) {
    StringBuilder canonical = new StringBuilder(Objects.requireNonNull(semanticRevisionHash));
    for (DesignPlanContract.Step step : steps) {
      canonical.append('\u0000').append(step.stepId());
      canonical
          .append('\u0000')
          .append(step.owner().kind())
          .append('\u0000')
          .append(step.owner().id());
      for (DesignPlanContract.Claim claim : step.claims()) {
        canonical
            .append('\u0000')
            .append(claim.targetKind())
            .append('\u0000')
            .append(claim.targetId())
            .append('\u0000')
            .append(claim.role());
      }
      for (String dependency : step.dependsOnStepIds()) {
        canonical.append('\u0000').append(dependency);
      }
    }
    return "plan-" + sha256(canonical.toString()).substring(0, 24);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
