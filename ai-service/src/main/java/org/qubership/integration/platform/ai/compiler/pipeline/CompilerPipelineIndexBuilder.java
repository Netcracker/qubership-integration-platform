package org.qubership.integration.platform.ai.compiler.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.compiler.addon.AddonRuntimeMetadata;
import org.qubership.integration.platform.ai.compiler.addon.AddonRuntimeMetadataParser;
import org.qubership.integration.platform.ai.compiler.policy.AddonReadinessParser;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorDescriptor;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorReadiness;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/** Builds {@link CompilerPipelineIndex} from pack metadata and canonical schema-v2 sources. */
public final class CompilerPipelineIndexBuilder {

  public static final int SCHEMA_VERSION = 2;
  public static final int SCHEMA_VERSION_V1 = 1;

  public static final String FINDING_MISSING_ADDON = "MISSING_ADDON_RUNTIME_METADATA";
  public static final String FINDING_MISSING_CAPTURE_TOOL = "MISSING_CAPTURE_TOOL";
  public static final String FINDING_MISSING_JAVA_ADAPTER = "MISSING_JAVA_ADAPTER_ID";
  public static final String FINDING_MISSING_RUNTIME_OUTPUTS = "MISSING_RUNTIME_OUTPUTS";

  public static final String ADAPTER_GRAPH_ASSEMBLY = "graph-assembly";

  private static final Pattern APPLICABILITY_SECTION =
      Pattern.compile(
          "(?is)^##\\s+Applicability in ai-service\\s*$([\\s\\S]*?)(?=^##\\s+|\\z)",
          Pattern.MULTILINE);
  private static final Pattern BULLET = Pattern.compile("^\\s*-\\s+\\*?\\*?(.+?)\\*?\\*?\\s*$");

  private static final Set<String> GENERATION_MACROS =
      Set.of(
          "all-generation-skills",
          "all generation-stage skills",
          "all-generators",
          "12-generation-skills");
  private static final Set<String> VALIDATION_MACROS =
      Set.of("all-validation-skills", "5-validators");

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
  private final ObjectMapper jsonMapper =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final AddonRuntimeMetadataParser runtimeMetadataParser = new AddonRuntimeMetadataParser();
  private final AddonReadinessParser readinessParser = new AddonReadinessParser();

  public CompilerPipelineIndex build(CompilerPipelineSourceLoader.SourceSet sources) {
    return build(sources, null, null);
  }

  public CompilerPipelineIndex build(
      QipKnowledgePackScanResult scanResult, CompilerGeneratorPolicy policy) {
    return build(scanResult, policy, resolveAddonRoot());
  }

  public CompilerPipelineIndex build(
      QipKnowledgePackScanResult scanResult, CompilerGeneratorPolicy policy, Path addonRoot) {
    if (!hasCanonicalSources(scanResult.packRoot())) {
      return buildPolicyCompatibilityIndex(scanResult.version(), policy);
    }
    CompilerPipelineSourceLoader.SourceSet sources =
        new CompilerPipelineSourceLoader().load(scanResult.packRoot(), addonRoot);
    return build(sources, scanResult.version(), policy);
  }

  private static boolean hasCanonicalSources(Path packRoot) {
    return Files.isRegularFile(
            packRoot.resolve("knowledge/runtime-substrate/runtime-dependency-model.yaml"))
        && Files.isRegularFile(packRoot.resolve("skills/skill-catalog.yaml"))
        && Files.isRegularFile(
            packRoot.resolve("knowledge/runtime-substrate/generator-packages.yaml"))
        && Files.isRegularFile(packRoot.resolve("product-pipelines/artifact-schemas.yaml"));
  }

  private CompilerPipelineIndex buildPolicyCompatibilityIndex(
      QipKnowledgePackVersion packVersion, CompilerGeneratorPolicy policy) {
    List<CompilerPipelineEntry> entries =
        policy == null
            ? List.of()
            : legacyEntriesFromPolicy(
                policy,
                new CompilerPipelineSourceLoader.SourceSet(
                    "", "", "", "", Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    return new CompilerPipelineIndex(
        SCHEMA_VERSION,
        packVersion,
        new CompilerPipelineIndexSource(
            policy == null ? "" : policy.sources().generatorContractsSha(),
            policy == null ? "" : policy.sources().ruleMappingSha()),
        entries,
        null,
        Map.of(),
        List.of(),
        List.of());
  }

  CompilerPipelineIndex build(
      CompilerPipelineSourceLoader.SourceSet sources,
      QipKnowledgePackVersion packVersion,
      CompilerGeneratorPolicy policy) {
    Objects.requireNonNull(sources, "sources");
    ParsedModel model = parseDependencyModel(sources.runtimeDependencyModelYaml());
    Map<String, CatalogSkill> catalog = parseSkillCatalog(sources.skillCatalogYaml());
    Map<String, String> generatorIds = parseGeneratorIds(sources.generatorPackagesYaml());
    Map<String, GraphPatchOwnershipPolicy> ownershipEnvelopes =
        parseOwnershipEnvelopes(sources.generatorPackagesYaml());
    Map<String, List<String>> artifactFlow = parseArtifactFlow(sources.runtimeDependencyModelYaml());

    Set<String> generationSkills = generationSkillIds(catalog);
    Set<String> validationSkills = validationSkillIds(catalog);
    Set<String> knownSkills = new LinkedHashSet<>();
    knownSkills.addAll(model.dependsOn.keySet());
    knownSkills.addAll(catalog.keySet());

    validateCatalogAgainstModel(catalog, model.dependsOn, generationSkills, validationSkills);

    List<CompilerPipelineDependency> dependencies =
        buildDependencies(model.dependsOn, knownSkills, generationSkills, validationSkills, artifactFlow);
    Map<String, Integer> levels = topologicalLevels(knownSkills, dependencies);

    Map<String, Integer> tieBreakers = stableTieBreakers(catalog);
    List<CompilerPipelineNode> nodes = new ArrayList<>();
    for (String skillId : knownSkills) {
      CatalogSkill catalogSkill = catalog.get(skillId);
      String phase =
          catalogSkill != null && catalogSkill.stage != null && !catalogSkill.stage.isBlank()
              ? catalogSkill.stage
              : "Generation";
      String generatorId = generatorIds.getOrDefault(skillId, "");
      List<String> consumes =
          catalogSkill == null
              ? List.of()
              : typedSkillArtifactNames(catalogSkill.inputs);
      List<String> produces =
          catalogSkill == null
              ? List.of()
              : typedSkillArtifactNames(catalogSkill.generatedArtifacts);
      List<String> dependsOn =
          expandDeps(
              model.dependsOn.getOrDefault(skillId, List.of()),
              generationSkills,
              validationSkills,
              knownSkills);

      String addonContent = sources.addonContentsById().get(skillId);
      AddonRuntimeMetadata runtimeMetadata =
          addonContent == null
              ? null
              : runtimeMetadataParser.parseAddonContent(addonContent, skillId + ".addon.md");
      if (runtimeMetadata != null) {
        if (!runtimeMetadata.inputArtifacts().isEmpty()) {
          consumes = runtimeMetadata.inputArtifacts();
        }
        if (!runtimeMetadata.outputArtifacts().isEmpty()) {
          produces = runtimeMetadata.outputArtifacts();
        }
      }
      CompilerGeneratorReadiness readiness =
          addonContent == null
              ? null
              : readinessParser.parseAddonContent(addonContent, skillId + ".addon.md");
      List<String> readinessSignals =
          readiness == null ? List.of() : List.copyOf(readiness.signals());
      List<String> applicabilitySignals =
          addonContent == null ? List.of() : parseApplicabilitySignals(addonContent);

      CompilerNodeExecutionMode executionMode = executionModeFor(skillId, phase);
      String adapterId = adapterIdFor(skillId, executionMode);
      String captureTool =
          runtimeMetadata == null || runtimeMetadata.captureTool() == null
              ? null
              : runtimeMetadata.captureTool().toolName();

      List<String> findings = new ArrayList<>();
      boolean runtimeReady =
          deriveRuntimeReady(executionMode, adapterId, runtimeMetadata, captureTool, produces, findings);
      GraphPatchOwnershipPolicy ownership =
          effectiveOwnership(skillId, executionMode, runtimeMetadata, ownershipEnvelopes.get(skillId));

      nodes.add(
          new CompilerPipelineNode(
              skillId,
              phase,
              generatorId,
              consumes,
              produces,
              dependsOn,
              captureTool,
              applicabilitySignals,
              readinessSignals,
              runtimeReady,
              findings,
              sources.skillSha256ById().getOrDefault(skillId, ""),
              sources.addonSha256ById().getOrDefault(skillId, ""),
              levels.getOrDefault(skillId, 0),
              tieBreakers.getOrDefault(skillId, 0),
              true,
              executionMode,
              adapterId,
              ownership));
    }

    nodes.sort(
        Comparator.comparingInt(CompilerPipelineNode::topologicalLevel)
            .thenComparingInt(CompilerPipelineNode::stableTieBreaker)
            .thenComparing(CompilerPipelineNode::skillId));

    String packageDigest =
        packageDigest(sources.sourceDigests(), nodes, dependencies);
    CompilerPackageIdentity identity =
        new CompilerPackageIdentity(model.packageId, model.packageVersion, packageDigest);

    List<CompilerPipelineEntry> legacyEntries =
        policy == null ? List.of() : legacyEntriesFromPolicy(policy, sources);

    return new CompilerPipelineIndex(
        SCHEMA_VERSION,
        packVersion,
        new CompilerPipelineIndexSource(
            sources.sourceDigests().getOrDefault(CompilerPipelineSourceLoader.GENERATOR_PACKAGES, ""),
            sources.sourceDigests().getOrDefault(CompilerPipelineSourceLoader.SKILL_CATALOG, "")),
        legacyEntries,
        identity,
        sources.sourceDigests(),
        List.copyOf(nodes),
        List.copyOf(dependencies));
  }

  private List<CompilerPipelineEntry> legacyEntriesFromPolicy(
      CompilerGeneratorPolicy policy, CompilerPipelineSourceLoader.SourceSet sources) {
    List<CompilerPipelineEntry> entries = new ArrayList<>();
    for (CompilerGeneratorDescriptor generator : policy.generators()) {
      entries.add(
          new CompilerPipelineEntry(
              generator.skillId(),
              "generation",
              "generation",
              generator.order(),
              generator.generatorId(),
              true,
              "skills/" + generator.skillId() + "/SKILL.md",
              sources.skillSha256ById().getOrDefault(generator.skillId(), ""),
              "high",
              List.of(),
              List.of(),
              List.of()));
    }
    return List.copyOf(entries);
  }

  private ParsedModel parseDependencyModel(String yaml) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      JsonNode model = root.path("model");
      String packageId = textOrDefault(model.get("package"), "compiler-v2");
      String packageVersion = textOrDefault(model.get("version"), "1.0.0");
      Map<String, List<String>> dependsOn = new LinkedHashMap<>();
      JsonNode deps = root.path("skill-dependencies");
      if (deps.isArray()) {
        for (JsonNode node : deps) {
          String skill = CompilerPipelineSourceLoader.normalizeSkillId(textOrNull(node.get("skill")));
          if (skill.isBlank()) {
            continue;
          }
          dependsOn.put(skill, strings(node.path("depends-on")));
        }
      }
      return new ParsedModel(packageId, packageVersion, dependsOn);
    } catch (Exception e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to parse runtime-dependency-model.yaml: " + e.getMessage());
    }
  }

  private Map<String, CatalogSkill> parseSkillCatalog(String yaml) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      Map<String, CatalogSkill> catalog = new LinkedHashMap<>();
      JsonNode skills = root.path("normalized-skills");
      if (!skills.isArray()) {
        throw new CompilerPipelineIndexParseException(
            "skill-catalog.yaml must contain normalized-skills");
      }
      int index = 0;
      for (JsonNode node : skills) {
        String name = CompilerPipelineSourceLoader.normalizeSkillId(textOrNull(node.get("name")));
        if (name.isBlank()) {
          continue;
        }
        catalog.put(
            name,
            new CatalogSkill(
                name,
                textOrDefault(node.get("stage"), ""),
                textOrDefault(node.get("category"), ""),
                strings(node.path("dependencies")),
                strings(node.path("inputs")),
                strings(node.path("generated-artifacts")),
                index++));
      }
      return catalog;
    } catch (CompilerPipelineIndexParseException e) {
      throw e;
    } catch (Exception e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to parse skill-catalog.yaml: " + e.getMessage());
    }
  }

  private Map<String, String> parseGeneratorIds(String yaml) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      Map<String, String> ids = new LinkedHashMap<>();
      JsonNode baseline = root.path("baseline-generators");
      if (baseline.isArray()) {
        for (JsonNode node : baseline) {
          String skill =
              CompilerPipelineSourceLoader.normalizeSkillId(
                  textOrNull(node.get("canonical-skill")));
          String id = textOrNull(node.get("id"));
          if (!skill.isBlank() && id != null && !id.isBlank()) {
            ids.put(skill, id);
          }
        }
      }
      JsonNode v2 = root.path("v2-generators").path("generators");
      if (v2.isArray()) {
        for (JsonNode node : v2) {
          String skill =
              CompilerPipelineSourceLoader.normalizeSkillId(textOrNull(node.get("skill")));
          String id = textOrNull(node.get("id"));
          if (!skill.isBlank() && id != null && !id.isBlank()) {
            ids.putIfAbsent(skill, id);
          }
        }
      }
      return ids;
    } catch (Exception e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to parse generator-packages.yaml: " + e.getMessage());
    }
  }

  private Map<String, List<String>> parseArtifactFlow(String yaml) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      Map<String, List<String>> flow = new LinkedHashMap<>();
      JsonNode artifacts = root.path("artifact-flow");
      if (!artifacts.isArray()) {
        return flow;
      }
      for (JsonNode node : artifacts) {
        String producer =
            CompilerPipelineSourceLoader.normalizeSkillId(textOrNull(node.get("producer")));
        String artifact = textOrNull(node.get("artifact"));
        if (producer.isBlank() || artifact == null || artifact.isBlank()) {
          continue;
        }
        flow.computeIfAbsent(producer, key -> new ArrayList<>()).add(artifact.trim());
      }
      return flow;
    } catch (Exception e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to parse artifact-flow from runtime-dependency-model.yaml: " + e.getMessage());
    }
  }

  private void validateCatalogAgainstModel(
      Map<String, CatalogSkill> catalog,
      Map<String, List<String>> modelDependsOn,
      Set<String> generationSkills,
      Set<String> validationSkills) {
    for (CatalogSkill skill : catalog.values()) {
      List<String> catalogDeps =
          expandDeps(skill.dependencies, generationSkills, validationSkills, catalog.keySet());
      List<String> modelDeps =
          expandDeps(
              modelDependsOn.getOrDefault(skill.name, List.of()),
              generationSkills,
              validationSkills,
              catalog.keySet());
      if (catalogDeps.isEmpty() || modelDeps.isEmpty()) {
        continue;
      }
      boolean overlap = catalogDeps.stream().anyMatch(modelDeps::contains);
      if (!overlap) {
        throw new CompilerPipelineIndexParseException(
            "Conflicting dependency declarations for "
                + skill.name
                + ": catalog="
                + catalogDeps
                + " model="
                + modelDeps);
      }
    }
  }

  private List<CompilerPipelineDependency> buildDependencies(
      Map<String, List<String>> modelDependsOn,
      Set<String> knownSkills,
      Set<String> generationSkills,
      Set<String> validationSkills,
      Map<String, List<String>> artifactFlow) {
    List<CompilerPipelineDependency> edges = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Map.Entry<String, List<String>> entry : modelDependsOn.entrySet()) {
      String consumer = entry.getKey();
      if (!knownSkills.contains(consumer)) {
        continue;
      }
      List<String> producers =
          expandDeps(entry.getValue(), generationSkills, validationSkills, knownSkills);
      for (String producer : producers) {
        if (!knownSkills.contains(producer)) {
          throw new CompilerPipelineIndexParseException(
              "Missing producer skill '" + producer + "' required by " + consumer);
        }
        String key = producer + "->" + consumer;
        if (!seen.add(key)) {
          continue;
        }
        edges.add(
            new CompilerPipelineDependency(
                producer, consumer, artifactFlow.getOrDefault(producer, List.of())));
      }
    }
    edges.sort(
        Comparator.comparing(CompilerPipelineDependency::producerSkillId)
            .thenComparing(CompilerPipelineDependency::consumerSkillId));
    return edges;
  }

  private Map<String, Integer> topologicalLevels(
      Set<String> knownSkills, List<CompilerPipelineDependency> dependencies) {
    Map<String, Set<String>> outgoing = new LinkedHashMap<>();
    Map<String, Integer> indegree = new LinkedHashMap<>();
    for (String skill : knownSkills) {
      outgoing.put(skill, new LinkedHashSet<>());
      indegree.put(skill, 0);
    }
    for (CompilerPipelineDependency edge : dependencies) {
      if (!outgoing.containsKey(edge.producerSkillId())
          || !indegree.containsKey(edge.consumerSkillId())) {
        continue;
      }
      if (outgoing.get(edge.producerSkillId()).add(edge.consumerSkillId())) {
        indegree.merge(edge.consumerSkillId(), 1, Integer::sum);
      }
    }

    ArrayDeque<String> queue = new ArrayDeque<>();
    Map<String, Integer> levels = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
        levels.put(entry.getKey(), 0);
      }
    }

    int visited = 0;
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      visited++;
      int currentLevel = levels.getOrDefault(current, 0);
      for (String next : outgoing.getOrDefault(current, Set.of())) {
        levels.merge(next, currentLevel + 1, Math::max);
        int remaining = indegree.merge(next, -1, Integer::sum);
        if (remaining == 0) {
          queue.add(next);
        }
      }
    }
    if (visited != knownSkills.size()) {
      throw new CompilerPipelineIndexParseException(
          "Dependency cycle detected in runtime-dependency-model.yaml");
    }
    return levels;
  }

  private Map<String, Integer> stableTieBreakers(Map<String, CatalogSkill> catalog) {
    Map<String, Integer> tieBreakers = new LinkedHashMap<>();
    for (CatalogSkill skill : catalog.values()) {
      tieBreakers.put(skill.name, skill.catalogIndex);
    }
    return tieBreakers;
  }

  private static Set<String> generationSkillIds(Map<String, CatalogSkill> catalog) {
    Set<String> skills = new LinkedHashSet<>();
    for (CatalogSkill skill : catalog.values()) {
      if (skill.stage.toLowerCase(Locale.ROOT).startsWith("generation")
          || "generator".equalsIgnoreCase(skill.category)) {
        skills.add(skill.name);
      }
    }
    return skills;
  }

  private static Set<String> validationSkillIds(Map<String, CatalogSkill> catalog) {
    Set<String> skills = new LinkedHashSet<>();
    for (CatalogSkill skill : catalog.values()) {
      if (skill.stage.toLowerCase(Locale.ROOT).startsWith("validation")
          || skill.name.endsWith("-validator")) {
        skills.add(skill.name);
      }
    }
    return skills;
  }

  private static List<String> expandDeps(
      List<String> rawDeps,
      Set<String> generationSkills,
      Set<String> validationSkills,
      Set<String> knownSkills) {
    LinkedHashSet<String> expanded = new LinkedHashSet<>();
    for (String raw : rawDeps) {
      String normalized = CompilerPipelineSourceLoader.normalizeSkillId(raw);
      if (normalized.isBlank()) {
        continue;
      }
      if (GENERATION_MACROS.contains(normalized)) {
        expanded.addAll(generationSkills);
        continue;
      }
      if (VALIDATION_MACROS.contains(normalized)) {
        expanded.addAll(validationSkills);
        continue;
      }
      if (!looksLikeSkillId(normalized)) {
        continue;
      }
      if (!knownSkills.contains(normalized)) {
        // Leave unresolved concrete ids for missing-producer detection at edge build time.
        expanded.add(normalized);
        continue;
      }
      expanded.add(normalized);
    }
    return List.copyOf(expanded);
  }

  private static boolean looksLikeSkillId(String value) {
    return value.startsWith("cip-") || value.startsWith("skill-");
  }

  /**
   * Catalog {@code inputs}/{@code generated-artifacts} are often human labels ("All Generation
   * Stage outputs"). Scheduler consumes must be {@link SkillArtifactType} names; drop prose so
   * dependsOn edges remain the authority for stage gating.
   */
  private static List<String> typedSkillArtifactNames(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> typed = new LinkedHashSet<>();
    for (String item : raw) {
      if (item == null || item.isBlank()) {
        continue;
      }
      String token = item.trim();
      if (token.startsWith("`") && token.endsWith("`") && token.length() > 2) {
        token = token.substring(1, token.length() - 1).trim();
      }
      String candidate = token.replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
      if (!TYPED_ARTIFACT_NAME.matcher(candidate).matches()) {
        continue;
      }
      try {
        typed.add(SkillArtifactType.valueOf(candidate).name());
      } catch (IllegalArgumentException ignored) {
        // Not a workspace artifact slot — ignore catalog prose / file paths.
      }
    }
    return List.copyOf(typed);
  }

  private static final Pattern TYPED_ARTIFACT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  private static CompilerNodeExecutionMode executionModeFor(String skillId, String phase) {
    if ("cip-requirement-analyzer".equals(skillId)) {
      return CompilerNodeExecutionMode.PRE_SATISFIED;
    }
    if ("cip-chain-generator".equals(skillId)) {
      return CompilerNodeExecutionMode.VIRTUAL_ORCHESTRATOR;
    }
    if ("cip-chain-assembler".equals(skillId)) {
      return CompilerNodeExecutionMode.JAVA_ADAPTER;
    }
    if (skillId.endsWith("-validator")
        || (phase != null && phase.toLowerCase(Locale.ROOT).startsWith("validation"))) {
      return CompilerNodeExecutionMode.JAVA_ADAPTER;
    }
    return CompilerNodeExecutionMode.LLM_SKILL;
  }

  private static String adapterIdFor(String skillId, CompilerNodeExecutionMode mode) {
    if (mode != CompilerNodeExecutionMode.JAVA_ADAPTER) {
      return null;
    }
    if ("cip-chain-assembler".equals(skillId)) {
      return ADAPTER_GRAPH_ASSEMBLY;
    }
    return skillId;
  }

  private static boolean deriveRuntimeReady(
      CompilerNodeExecutionMode mode,
      String adapterId,
      AddonRuntimeMetadata runtimeMetadata,
      String captureTool,
      List<String> produces,
      List<String> findings) {
    return switch (mode) {
      case PRE_SATISFIED, VIRTUAL_ORCHESTRATOR -> true;
      case JAVA_ADAPTER -> {
        if (adapterId == null || adapterId.isBlank()) {
          findings.add(FINDING_MISSING_JAVA_ADAPTER);
          yield false;
        }
        yield true;
      }
      case LLM_SKILL -> {
        boolean ready = true;
        if (runtimeMetadata == null) {
          findings.add(FINDING_MISSING_ADDON);
          ready = false;
        }
        if (captureTool == null || captureTool.isBlank()) {
          findings.add(FINDING_MISSING_CAPTURE_TOOL);
          ready = false;
        }
        if (produces == null || produces.isEmpty()) {
          findings.add(FINDING_MISSING_RUNTIME_OUTPUTS);
          ready = false;
        }
        yield ready;
      }
    };
  }

  private static List<String> parseApplicabilitySignals(String addonContent) {
    Matcher section = APPLICABILITY_SECTION.matcher(addonContent);
    if (!section.find()) {
      return List.of();
    }
    List<String> signals = new ArrayList<>();
    for (String line : section.group(1).split("\\R")) {
      Matcher bullet = BULLET.matcher(line);
      if (!bullet.matches()) {
        continue;
      }
      String text = bullet.group(1).trim();
      if (!text.isBlank()) {
        signals.add(text);
      }
    }
    return List.copyOf(signals);
  }

  private String packageDigest(
      Map<String, String> sourceDigests,
      List<CompilerPipelineNode> nodes,
      List<CompilerPipelineDependency> dependencies) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("sourceDigests", new TreeMap<>(sourceDigests));
    canonical.put(
        "nodes",
        nodes.stream()
            .map(
                node -> {
                  Map<String, Object> map = new TreeMap<>();
                  map.put("skillId", node.skillId());
                  map.put("compilerPhase", node.compilerPhase());
                  map.put("generatorId", node.generatorId());
                  map.put("consumes", node.consumes());
                  map.put("produces", node.produces());
                  map.put("dependsOn", node.dependsOn());
                  map.put("captureTool", node.captureTool());
                  map.put("applicabilitySignals", node.applicabilitySignals());
                  map.put("readinessSignals", node.readinessSignals());
                  map.put("runtimeReady", node.runtimeReady());
                  map.put("runtimeReadinessFindings", node.runtimeReadinessFindings());
                  map.put("skillSha256", node.skillSha256());
                  map.put("addonSha256", node.addonSha256());
                  map.put("topologicalLevel", node.topologicalLevel());
                  map.put("stableTieBreaker", node.stableTieBreaker());
                  map.put("mandatory", node.mandatory());
                  map.put("executionMode", node.executionMode().name());
                  map.put("adapterId", node.adapterId());
                  map.put("ownership", canonicalOwnership(node.ownership()));
                  return map;
                })
            .toList());
    canonical.put(
        "dependencies",
        dependencies.stream()
            .map(
                edge -> {
                  Map<String, Object> map = new TreeMap<>();
                  map.put("producerSkillId", edge.producerSkillId());
                  map.put("consumerSkillId", edge.consumerSkillId());
                  map.put("artifactTypes", edge.artifactTypes());
                  return map;
                })
            .toList());
    try {
      return CompilerPipelineSourceLoader.sha256(jsonMapper.writeValueAsString(canonical));
    } catch (JsonProcessingException e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to compute package digest: " + e.getMessage());
    }
  }

  private static Path resolveAddonRoot() {
    String property = System.getProperty("qip.ai.qipknowledge.addon-pack-root");
    if (property == null || property.isBlank()) {
      return null;
    }
    return Path.of(property);
  }

  private static List<String> strings(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual() && !item.asText().isBlank()) {
          values.add(item.asText().trim());
        }
      }
      return List.copyOf(values);
    }
    if (node.isTextual() && !node.asText().isBlank()) {
      return List.of(node.asText().trim());
    }
    return List.of();
  }

  private static String textOrNull(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static String textOrDefault(JsonNode node, String defaultValue) {
    String value = textOrNull(node);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private Map<String, GraphPatchOwnershipPolicy> parseOwnershipEnvelopes(String yaml) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      JsonNode envelopes = root.path("ownership-envelopes");
      if (!envelopes.isArray()) {
        return Map.of();
      }
      Map<String, GraphPatchOwnershipPolicy> result = new LinkedHashMap<>();
      for (JsonNode node : envelopes) {
        String skillId = CompilerPipelineSourceLoader.normalizeSkillId(textOrNull(node.get("skill")));
        if (skillId.isBlank()) {
          continue;
        }
        GraphPatchOwnershipPolicy policy =
            new GraphPatchOwnershipPolicy(
                node.path("mayAddNodes").asBoolean(false),
                node.path("mayAddEdges").asBoolean(false),
                strings(node.path("nodeTypes")).stream()
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll),
                strings(node.path("chainFields")).stream()
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll),
                propertyMap(node.path("properties")));
        result.put(skillId, policy);
      }
      return Map.copyOf(result);
    } catch (Exception e) {
      throw new CompilerPipelineIndexParseException(
          "Failed to parse ownership-envelopes from generator-packages.yaml: " + e.getMessage());
    }
  }

  private static Map<String, Set<String>> propertyMap(JsonNode node) {
    if (node == null || node.isMissingNode() || !node.isObject()) {
      return Map.of();
    }
    Map<String, Set<String>> result = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            field ->
                result.put(
                    field.getKey(),
                    strings(field.getValue()).stream()
                        .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)));
    return result.isEmpty() ? Map.of() : Map.copyOf(result);
  }

  private static GraphPatchOwnershipPolicy effectiveOwnership(
      String skillId,
      CompilerNodeExecutionMode executionMode,
      AddonRuntimeMetadata runtimeMetadata,
      GraphPatchOwnershipPolicy envelope) {
    if (executionMode != CompilerNodeExecutionMode.LLM_SKILL) {
      return GraphPatchOwnershipPolicy.denyAll();
    }
    GraphPatchOwnershipPolicy declared =
        runtimeMetadata == null || runtimeMetadata.ownership() == null
            ? GraphPatchOwnershipPolicy.denyAll()
            : runtimeMetadata.ownership();
    if (runtimeMetadata != null && runtimeMetadata.ownership() != null) {
      validateNoWildcards(skillId, declared);
    }
    if (envelope == null) {
      return declared;
    }
    if (runtimeMetadata == null || runtimeMetadata.ownership() == null) {
      throw new CompilerPipelineIndexParseException(
          "Promoted patch generator " + skillId + " is missing runtime ownership metadata");
    }
    validateNoWildcards(skillId, envelope);
    validateNarrowedOwnership(skillId, declared, envelope);
    return declared;
  }

  private static void validateNoWildcards(String skillId, GraphPatchOwnershipPolicy policy) {
    if (policy.nodeTypes().contains("*")
        || policy.chainFields().contains("*")
        || policy.properties().containsKey("*")
        || policy.properties().values().stream().anyMatch(values -> values.contains("*"))) {
      throw new CompilerPipelineIndexParseException(
          "Ownership policy for " + skillId + " contains wildcard access");
    }
  }

  private static void validateNarrowedOwnership(
      String skillId, GraphPatchOwnershipPolicy declared, GraphPatchOwnershipPolicy envelope) {
    if (declared.mayAddNodes() && !envelope.mayAddNodes()) {
      throw new CompilerPipelineIndexParseException(
          "Ownership policy for " + skillId + " widens mayAddNodes");
    }
    if (declared.mayAddEdges() && !envelope.mayAddEdges()) {
      throw new CompilerPipelineIndexParseException(
          "Ownership policy for " + skillId + " widens mayAddEdges");
    }
    if (!envelope.nodeTypes().containsAll(declared.nodeTypes())) {
      throw new CompilerPipelineIndexParseException(
          "Ownership policy for " + skillId + " widens nodeTypes");
    }
    if (!envelope.chainFields().containsAll(declared.chainFields())) {
      throw new CompilerPipelineIndexParseException(
          "Ownership policy for " + skillId + " widens chainFields");
    }
    for (Map.Entry<String, Set<String>> entry : declared.properties().entrySet()) {
      Set<String> envelopeProperties = envelope.properties().get(entry.getKey());
      if (envelopeProperties == null) {
        throw new CompilerPipelineIndexParseException(
            "Ownership policy for " + skillId + " declares unknown node type " + entry.getKey());
      }
      if (!envelopeProperties.containsAll(entry.getValue())) {
        throw new CompilerPipelineIndexParseException(
            "Ownership policy for " + skillId + " widens properties for " + entry.getKey());
      }
    }
  }

  private static Map<String, Object> canonicalOwnership(GraphPatchOwnershipPolicy policy) {
    Map<String, Object> map = new TreeMap<>();
    map.put("mayAddNodes", policy.mayAddNodes());
    map.put("mayAddEdges", policy.mayAddEdges());
    map.put("nodeTypes", policy.nodeTypes().stream().sorted().toList());
    map.put("chainFields", policy.chainFields().stream().sorted().toList());
    Map<String, Object> properties = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : new TreeMap<>(policy.properties()).entrySet()) {
      properties.put(entry.getKey(), entry.getValue().stream().sorted().toList());
    }
    map.put("properties", properties);
    return map;
  }

  private record ParsedModel(
      String packageId, String packageVersion, Map<String, List<String>> dependsOn) {}

  private record CatalogSkill(
      String name,
      String stage,
      String category,
      List<String> dependencies,
      List<String> inputs,
      List<String> generatedArtifacts,
      int catalogIndex) {}
}
