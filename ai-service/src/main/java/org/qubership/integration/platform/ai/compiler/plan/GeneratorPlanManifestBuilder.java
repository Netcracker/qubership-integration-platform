package org.qubership.integration.platform.ai.compiler.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator.EvaluationResult;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorDescriptor;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorReadiness;
import org.qubership.integration.platform.ai.llm.agent.ReadinessIntentClassifierAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;

/** Builds the generator execution manifest from policy and workspace state. */
@ApplicationScoped
public class GeneratorPlanManifestBuilder {

  private static final Logger LOG = Logger.getLogger(GeneratorPlanManifestBuilder.class);

  private final GeneratorReadinessEvaluator readinessEvaluator;
  private final ReadinessIntentClassifierAgent intentClassifier;

  public GeneratorPlanManifestBuilder(
      GeneratorReadinessEvaluator readinessEvaluator,
      ReadinessIntentClassifierAgent intentClassifier) {
    this.readinessEvaluator = readinessEvaluator;
    this.intentClassifier = intentClassifier;
  }

  public BuildResult build(
      CompilerGeneratorPolicy policy, List<String> wiredSkillIds, SkillWorkspace workspace) {
    ChainPlanGraph graph = readGraph(workspace);
    String rawUserRequest = readRawUserRequest(workspace);
    String requirementBrief = readRequirementBrief(workspace);

    List<CompilerGeneratorDescriptor> wired =
        policy.generators().stream()
            .filter(descriptor -> wiredSkillIds.contains(descriptor.skillId()))
            .toList();
    Set<String> matchedIntents = classifyIntents(wired, rawUserRequest, requirementBrief);

    List<GeneratorPlan> plans = new ArrayList<>();
    for (CompilerGeneratorDescriptor descriptor : wired) {
      plans.add(planFor(descriptor, graph, matchedIntents));
    }

    GeneratorPlanManifest manifest =
        new GeneratorPlanManifest(policy.packVersion().normalized(), List.copyOf(plans));
    CompilerStatus status = buildCompilerStatus(manifest);
    return new BuildResult(manifest, status);
  }

  private GeneratorPlan planFor(
      CompilerGeneratorDescriptor descriptor, ChainPlanGraph graph, Set<String> matchedIntents) {
    CompilerGeneratorReadiness readiness = descriptor.readiness();
    if (readiness == null || readiness.signals() == null || readiness.signals().isEmpty()) {
      return new GeneratorPlan(
          descriptor.generatorId(),
          descriptor.skillId(),
          GeneratorPlanStatus.BLOCKED,
          List.of(),
          List.of());
    }
    EvaluationResult result = readinessEvaluator.evaluate(readiness.signals(), graph, matchedIntents);
    boolean ready = result.status() == GeneratorPlanStatus.READY;
    return new GeneratorPlan(
        descriptor.generatorId(),
        descriptor.skillId(),
        result.status(),
        result.matchedSignals(),
        ready ? result.targetNodeIds() : List.of());
  }

  /**
   * Classifies which intent concepts the request asks for. Skips the LLM call when no wired
   * generator declares an intent signal. Fails fast if the classifier is unavailable — the whole
   * generation pipeline is LLM-driven, so there is no meaningful degraded mode.
   */
  private Set<String> classifyIntents(
      List<CompilerGeneratorDescriptor> wired, String rawUserRequest, String requirementBrief) {
    List<String> allSignals =
        wired.stream()
            .map(CompilerGeneratorDescriptor::readiness)
            .filter(readiness -> readiness != null && readiness.signals() != null)
            .flatMap(readiness -> readiness.signals().stream())
            .toList();
    if (!GeneratorReadinessEvaluator.requiresIntentClassification(allSignals)) {
      return Set.of();
    }
    String raw =
        intentClassifier.classify(
            readinessEvaluator.intentCatalogText(), rawUserRequest, requirementBrief);
    Set<String> matched = parseIntents(raw);
    suppressNegatedIntents(matched, rawUserRequest, requirementBrief);
    LOG.infof("Readiness intent classifier matched=%s", matched);
    return matched;
  }

  private Set<String> parseIntents(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<String> known = readinessEvaluator.intentConcepts();
    Set<String> matched = new LinkedHashSet<>();
    for (String token : raw.toLowerCase(Locale.ROOT).split("[^a-z_]+")) {
      if (known.contains(token)) {
        matched.add(token);
      }
    }
    return matched;
  }

  private static void suppressNegatedIntents(
      Set<String> matched, String rawUserRequest, String requirementBrief) {
    if (matched.isEmpty()) {
      return;
    }
    String text = (nullToEmpty(rawUserRequest) + "\n" + nullToEmpty(requirementBrief))
        .toLowerCase(Locale.ROOT);
    if (mentionsNoRouting(text) || !mentionsBranching(text)) {
      matched.remove("branching");
    }
    if (mentionsNoSecurity(text)) {
      matched.remove("rbac");
      matched.remove("abac");
      matched.remove("credentials");
    }
  }

  private static boolean mentionsNoRouting(String text) {
    return text.contains("no routing")
        || text.contains("without routing")
        || text.contains("routing is not required");
  }

  private static boolean mentionsBranching(String text) {
    return text.contains("route by")
        || text.contains("routing by")
        || text.contains("branch")
        || text.contains("condition")
        || text.contains("if/else")
        || text.contains("if else")
        || text.contains("else branch")
        || text.contains("split by");
  }

  private static boolean mentionsNoSecurity(String text) {
    return text.contains("no security")
        || text.contains("no auth")
        || text.contains("no rbac")
        || text.contains("no abac")
        || text.contains("without authentication")
        || text.contains("without authorization")
        || text.contains("open access")
        || text.contains("accessible to all");
  }

  private static CompilerStatus buildCompilerStatus(GeneratorPlanManifest manifest) {
    String nextSkillId =
        manifest.plans().stream()
            .filter(plan -> plan.status() == GeneratorPlanStatus.READY)
            .map(GeneratorPlan::skillId)
            .findFirst()
            .orElse(null);
    List<String> skipped =
        manifest.plans().stream()
            .filter(plan -> plan.status() == GeneratorPlanStatus.SKIPPED)
            .map(GeneratorPlan::skillId)
            .toList();
    return new CompilerStatus("generation", nextSkillId, List.of(), skipped);
  }

  private static ChainPlanGraph readGraph(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(artifact -> ((SkillArtifactPayload.ChainPlanGraphPayload) artifact.payload()).graph())
        .orElse(null);
  }

  private static String readRawUserRequest(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.RAW_USER_REQUEST)
        .map(artifact -> ((SkillArtifactPayload.RawUserRequestPayload) artifact.payload()).effectiveText())
        .orElse("");
  }

  private static String readRequirementBrief(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.REQUIREMENT_BRIEF)
        .map(
            artifact ->
                RequirementBriefText.format(
                    ((SkillArtifactPayload.RequirementBriefPayload) artifact.payload()).brief()))
        .orElse("");
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }

  public record BuildResult(GeneratorPlanManifest manifest, CompilerStatus status) {}
}
