package org.qubership.integration.platform.ai.compiler.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedCipKnowledgeFile;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

/** Builds {@link CompilerGeneratorPolicy} from ingested compiler pack docs and addons. */
public final class CompilerGeneratorPolicyBuilder {

  private static final String GENERATOR_CONTRACTS_PATH = "knowledge/ai/GENERATOR_CONTRACTS.md";
  private static final String RULE_MAPPING_PATH = "knowledge/ai/generator-rule-mapping.md";

  private final AddonReadinessParser addonReadinessParser = new AddonReadinessParser();

  public CompilerGeneratorPolicyBuildResult build(
      CipKnowledgePackScanResult scanResult,
      CapabilityRegistry registry,
      CompilerSkillCatalog skillCatalog,
      CompilerGeneratorSpecIndex specIndex,
      Path addonPackRoot) {
    if (Files.isRegularFile(scanResult.packRoot().resolve("runtime/generator-contracts.json"))) {
      return buildRuntimePolicy(scanResult, registry, skillCatalog, specIndex, addonPackRoot);
    }
    ScannedCipKnowledgeFile contractsFile = requireFile(scanResult, GENERATOR_CONTRACTS_PATH);
    ScannedCipKnowledgeFile ruleMappingFile = requireFile(scanResult, RULE_MAPPING_PATH);

    List<String> executionOrder =
        GeneratorContractsParser.parseExecutionOrder(contractsFile.content());
    Map<String, GeneratorContractsParser.ParsedContract> contracts =
        GeneratorContractsParser.parseContracts(contractsFile.content());
    Map<String, List<String>> summaryRules =
        GeneratorRuleMappingParser.parseGeneratorSummary(ruleMappingFile.content());

    List<CompilerGeneratorPolicyIssue> issues =
        validateRuntimeSupportedGenerators(
            specIndex, skillCatalog, registry, executionOrder, contracts, summaryRules);
    if (!issues.isEmpty()) {
      throw new CompilerGeneratorPolicyValidationException(
          "Runtime-supported generators are missing policy contracts or mappings", issues);
    }

    List<CompilerGeneratorDescriptor> generators = new ArrayList<>();
    int order = 1;
    for (String generatorId : executionOrder) {
      GeneratorContractsParser.ParsedContract contract = contracts.get(generatorId);
      if (contract == null) {
        throw new CompilerGeneratorPolicyParseException(
            "Execution order references missing contract: " + generatorId);
      }
      List<String> ownedRules = summaryRules.get(generatorId);
      if (ownedRules == null) {
        throw new CompilerGeneratorPolicyParseException(
            "Generator Summary missing entry for " + generatorId);
      }
      String skillId = resolveRunnableSkillId(specIndex, skillCatalog, registry, generatorId);
      if (skillId == null) {
        continue;
      }
      CompilerGeneratorReadiness readiness = loadReadiness(addonPackRoot, skillId);
      generators.add(
          new CompilerGeneratorDescriptor(
              generatorId,
              skillId,
              order++,
              GeneratorContractsParser.toPlanArtifact(contract.name()),
              ownedRules,
              readiness));
    }

    if (generators.isEmpty()) {
      throw new CompilerGeneratorPolicyValidationException(
          "No runnable runtime-supported generators mapped from compiler pack",
          List.of(
              new CompilerGeneratorPolicyIssue(
                  "policy", "policy", "Generator policy is empty after validation")));
    }

    CompilerGeneratorPolicy policy =
        new CompilerGeneratorPolicy(
            scanResult.version(),
            new CompilerGeneratorPolicySource(
                contractsFile.sha256(), ruleMappingFile.sha256()),
            List.copyOf(generators));
    return new CompilerGeneratorPolicyBuildResult(policy, List.of());
  }

  private CompilerGeneratorPolicyBuildResult buildRuntimePolicy(
      CipKnowledgePackScanResult scanResult, CapabilityRegistry registry, CompilerSkillCatalog catalog,
      CompilerGeneratorSpecIndex specs, Path metadataRoot) {
    var source = requireFile(scanResult, "runtime/generator-contracts.json");
    List<CompilerGeneratorDescriptor> generators = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    try {
      var declared = new ObjectMapper().readValue(
          source.content(), CompilerGeneratorDescriptor[].class);
      for (var entry : declared) {
        String resolved = resolveRunnableSkillId(specs, catalog, registry, entry.generatorId());
        if (!ids.add(entry.generatorId()) || !entry.skillId().equals(resolved)
            || entry.order() != generators.size() + 1 || entry.planArtifact() == null
            || entry.planArtifact().isBlank() || entry.ownedRules() == null) {
          throw new CompilerGeneratorPolicyParseException("Invalid runtime generator contract: " + entry.generatorId());
        }
        generators.add(new CompilerGeneratorDescriptor(entry.generatorId(), entry.skillId(), entry.order(),
            entry.planArtifact(), entry.ownedRules(), loadReadiness(metadataRoot, entry.skillId())));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read runtime generator contracts", e);
    }
    for (var candidate : specs.specs()) {
      if (candidate.generatorId() != null
          && resolveRunnableSkillId(specs, catalog, registry, candidate.generatorId()) != null
          && !ids.contains(candidate.generatorId())) {
        throw new CompilerGeneratorPolicyParseException("Missing runtime generator contract: " + candidate.generatorId());
      }
    }
    if (generators.isEmpty()) throw new CompilerGeneratorPolicyParseException("Runtime generator policy is empty");
    return new CompilerGeneratorPolicyBuildResult(new CompilerGeneratorPolicy(scanResult.version(),
        new CompilerGeneratorPolicySource(source.sha256(), source.sha256()), List.copyOf(generators)), List.of());
  }

  private List<CompilerGeneratorPolicyIssue> validateRuntimeSupportedGenerators(
      CompilerGeneratorSpecIndex specIndex,
      CompilerSkillCatalog skillCatalog,
      CapabilityRegistry registry,
      List<String> executionOrder,
      Map<String, GeneratorContractsParser.ParsedContract> contracts,
      Map<String, List<String>> summaryRules) {
    Map<String, CompilerGeneratorPolicyIssue> issues = new LinkedHashMap<>();
    for (CompilerGeneratorSpec candidate : specIndex.specs()) {
      String generatorId = candidate.generatorId();
      if (generatorId == null || generatorId.isBlank()) {
        continue;
      }
      if (resolveRunnableSkillId(specIndex, skillCatalog, registry, generatorId) == null) {
        continue;
      }
      if (!executionOrder.contains(generatorId)) {
        issues.putIfAbsent(
            generatorId,
            new CompilerGeneratorPolicyIssue(
                generatorId,
                candidate.skillName(),
                "Missing execution-order entry in GENERATOR_CONTRACTS.md"));
      }
      if (!contracts.containsKey(generatorId)) {
        issues.putIfAbsent(
            generatorId,
            new CompilerGeneratorPolicyIssue(
                generatorId,
                candidate.skillName(),
                "Missing contract section in GENERATOR_CONTRACTS.md"));
      }
      if (!summaryRules.containsKey(generatorId)) {
        issues.putIfAbsent(
            generatorId,
            new CompilerGeneratorPolicyIssue(
                generatorId,
                candidate.skillName(),
                "Missing Generator Summary row in generator-rule-mapping.md"));
      }
    }
    return List.copyOf(issues.values());
  }

  private CompilerGeneratorReadiness loadReadiness(Path addonPackRoot, String skillId) {
    if (addonPackRoot == null || !Files.isDirectory(addonPackRoot)) {
      return null;
    }
    Path addonFile = addonPackRoot.resolve("skills").resolve(skillId + ".json");
    if (!Files.isRegularFile(addonFile)) {
      addonFile = addonPackRoot.resolve("skills").resolve(skillId + ".addon.md");
    }
    return addonReadinessParser.parseAddonFile(addonFile);
  }

  private static ScannedCipKnowledgeFile requireFile(
      CipKnowledgePackScanResult scanResult, String relativePath) {
    return scanResult.files().stream()
        .filter(file -> relativePath.equals(file.relativePath()))
        .findFirst()
        .orElseThrow(
            () ->
                new CompilerGeneratorPolicyParseException(
                    "Missing required knowledge file: " + relativePath));
  }

  private static String resolveRunnableSkillId(
      CompilerGeneratorSpecIndex specIndex,
      CompilerSkillCatalog skillCatalog,
      CapabilityRegistry registry,
      String generatorId) {
    for (CompilerGeneratorSpec candidate : specIndex.specs()) {
      if (!generatorId.equals(candidate.generatorId())) {
        continue;
      }
      CompilerSkillDescriptor catalogSkill =
          skillCatalog.find(candidate.skillName()).orElse(null);
      if (catalogSkill == null
          || !catalogSkill.runnable()
          || skillCatalog.excludesFromRuntimePolicy(catalogSkill)) {
        continue;
      }
      CapabilityDescriptor capability = findCapability(registry, candidate.skillName());
      if (capability == null
          || capability.phase() != CipKnowledgeCapabilityPhase.GENERATOR
          || !capability.supported()) {
        continue;
      }
      return candidate.skillName();
    }
    return null;
  }

  private static CapabilityDescriptor findCapability(
      CapabilityRegistry registry, String skillId) {
    return registry.capabilities().stream()
        .filter(capability -> skillId.equals(capability.id()))
        .findFirst()
        .orElse(null);
  }
}
