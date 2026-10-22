package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Blocks topology and generator ownership that contradict explicit negative requirement facts.
 * Loads {@code create-exclusions-v1.yaml} and maps forbidden capability keys to element types and
 * owner skills.
 */
@ApplicationScoped
public class RequirementTopologyGuard {

  public static final String RULE_RESOURCE =
      "product-pipelines/rules/create-exclusions-v1.yaml";

  private static final Map<String, ForbiddenTopology> DEFAULT_CAPABILITY_TOPOLOGY =
      Map.of(
          "error-handling",
          new ForbiddenTopology(
              Set.of("try-catch-finally-2", "try-2", "catch-2", "finally-2"),
              Set.of("cip-error-handling-generator")),
          "mcp",
          new ForbiddenTopology(
              Set.of("mcp-trigger", "mcp-server"),
              Set.of("cip-mcp-service-generator", "cip-mcp-trigger-generator")),
          "service-call",
          new ForbiddenTopology(Set.of("service-call"), Set.of("cip-service-call-generator")),
          "chain-failure-handler",
          new ForbiddenTopology(
              Set.of("chain-failure-handler"), Set.of("cip-chain-failure-handler-generator")));

  private final CreateExclusionsDocument document;
  private final Map<String, ForbiddenTopology> capabilityTopology;

  public RequirementTopologyGuard() {
    this(loadDocument(), DEFAULT_CAPABILITY_TOPOLOGY);
  }

  RequirementTopologyGuard(
      CreateExclusionsDocument document, Map<String, ForbiddenTopology> capabilityTopology) {
    this.document = document == null ? new CreateExclusionsDocument(1, "create-exclusions-v1", List.of(), Map.of()) : document;
    this.capabilityTopology =
        capabilityTopology == null || capabilityTopology.isEmpty()
            ? DEFAULT_CAPABILITY_TOPOLOGY
            : Map.copyOf(capabilityTopology);
  }

  public List<String> evaluateAfterPatternSelection(
      List<RequirementFact> facts, String selectedPatternSummary) {
    return evaluate(facts, selectedPatternSummary, null, List.of());
  }

  public List<String> evaluateAfterGraphCapture(
      List<RequirementFact> facts, ChainPlanGraph graph) {
    return evaluate(facts, null, graph, List.of());
  }

  public List<String> evaluateAfterGeneratorManifest(
      List<RequirementFact> facts, List<String> ownerSkills) {
    return evaluate(facts, null, null, ownerSkills == null ? List.of() : ownerSkills);
  }

  public List<String> pipelineStageExclusions() {
    return document.exclusions();
  }

  private List<String> evaluate(
      List<RequirementFact> facts,
      String patternSummary,
      ChainPlanGraph graph,
      List<String> ownerSkills) {
    List<String> blockers = new ArrayList<>();
    Set<String> forbiddenCapabilities = negativeCapabilityKeys(facts);
    for (String capability : forbiddenCapabilities) {
      ForbiddenTopology topology = capabilityTopology.get(capability);
      if (topology == null) {
        continue;
      }
      if (patternSummary != null && !patternSummary.isBlank()) {
        String lower = patternSummary.toLowerCase(Locale.ROOT);
        for (String elementType : topology.elementTypes()) {
          if (lower.contains(elementType.toLowerCase(Locale.ROOT))) {
            blockers.add(
                "selected pattern contradicts negative fact forbidding "
                    + capability
                    + " (mentions "
                    + elementType
                    + ")");
          }
        }
        for (String skill : topology.ownerSkills()) {
          if (lower.contains(skill.toLowerCase(Locale.ROOT))) {
            blockers.add(
                "selected pattern contradicts negative fact forbidding "
                    + capability
                    + " (mentions "
                    + skill
                    + ")");
          }
        }
      }
      if (graph != null && graph.nodes() != null) {
        for (ChainPlanNode node : graph.nodes()) {
          if (node.type() != null && topology.elementTypes().contains(node.type().trim())) {
            blockers.add(
                "graph contains forbidden element type '"
                    + node.type()
                    + "' for negative capability '"
                    + capability
                    + "'");
          }
        }
      }
      for (String skill : ownerSkills) {
        if (skill != null && topology.ownerSkills().contains(skill.trim())) {
          blockers.add(
              "generator manifest includes forbidden owner skill '"
                  + skill
                  + "' for negative capability '"
                  + capability
                  + "'");
        }
      }
    }
    return List.copyOf(new LinkedHashSet<>(blockers));
  }

  private static Set<String> negativeCapabilityKeys(List<RequirementFact> facts) {
    Set<String> keys = new LinkedHashSet<>();
    if (facts == null) {
      return keys;
    }
    for (RequirementFact fact : facts) {
      if (fact == null || fact.polarity() != RequirementFactPolarity.NEGATIVE) {
        continue;
      }
      if (fact.capabilityKey() != null && !fact.capabilityKey().isBlank()) {
        keys.add(fact.capabilityKey().trim().toLowerCase(Locale.ROOT));
      }
    }
    return keys;
  }

  private static CreateExclusionsDocument loadDocument() {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(RULE_RESOURCE)) {
      if (in == null) {
        // Worktree path for unit tests that do not copy skills resources onto the classpath.
        try (InputStream fallback =
            java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("integration-platform-skills")
                    .resolve(RULE_RESOURCE)
                    .toAbsolutePath()
                    .normalize())) {
          return yaml.readValue(fallback, CreateExclusionsDocument.class);
        } catch (IOException ignored) {
          return new CreateExclusionsDocument(1, "create-exclusions-v1", List.of(), Map.of());
        }
      }
      return yaml.readValue(in, CreateExclusionsDocument.class);
    } catch (IOException e) {
      return new CreateExclusionsDocument(1, "create-exclusions-v1", List.of(), Map.of());
    }
  }

  public record ForbiddenTopology(Set<String> elementTypes, Set<String> ownerSkills) {
    public ForbiddenTopology {
      elementTypes = elementTypes == null ? Set.of() : Set.copyOf(elementTypes);
      ownerSkills = ownerSkills == null ? Set.of() : Set.copyOf(ownerSkills);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CreateExclusionsDocument(
      int schemaVersion,
      String ruleId,
      List<String> exclusions,
      Map<String, ForbiddenTopology> capabilityForbiddenTopology) {
    public CreateExclusionsDocument {
      exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
      capabilityForbiddenTopology =
          capabilityForbiddenTopology == null
              ? Map.of()
              : Map.copyOf(capabilityForbiddenTopology);
    }
  }
}
