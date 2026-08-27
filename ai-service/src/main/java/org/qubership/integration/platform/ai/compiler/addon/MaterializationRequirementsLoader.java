package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirements.ElementRequirement;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;

/**
 * Loads {@link MaterializationRequirements} from the compiler skill addon pack. Required properties
 * come from {@link CompilerContract#elements()}; the addon YAML supplies owner and examples only.
 */
@ApplicationScoped
public class MaterializationRequirementsLoader {

  static final String MATERIALIZATION_REQUIREMENTS_PATH =
      "global/materialization-requirements.yaml";

  private final CompilerSkillAddonRepository addonRepository;
  private final CompilerContractRepository contractRepository;
  private final ObjectMapper yamlMapper;

  @Inject
  public MaterializationRequirementsLoader(
      CompilerSkillAddonRepository addonRepository,
      CompilerContractRepository contractRepository) {
    this(addonRepository, contractRepository, new ObjectMapper(new YAMLFactory()));
  }

  public MaterializationRequirementsLoader(CompilerSkillAddonRepository addonRepository) {
    this(
        addonRepository,
        new ClasspathCompilerContractRepository(),
        new ObjectMapper(new YAMLFactory()));
  }

  MaterializationRequirementsLoader(
      CompilerSkillAddonRepository addonRepository,
      CompilerContractRepository contractRepository,
      ObjectMapper yamlMapper) {
    this.addonRepository = Objects.requireNonNull(addonRepository, "addonRepository");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
    this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
  }

  public MaterializationRequirements load() {
    MaterializationRequirements overlay =
        addonRepository
            .readGlobalDataDocument(MATERIALIZATION_REQUIREMENTS_PATH)
            .map(this::parse)
            .orElse(MaterializationRequirements.empty());
    return applyContractRequiredProperties(overlay);
  }

  private MaterializationRequirements parse(String yaml) {
    try {
      MaterializationRequirements requirements =
          yamlMapper.readValue(yaml, MaterializationRequirements.class);
      return requirements != null ? requirements : MaterializationRequirements.empty();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to parse " + MATERIALIZATION_REQUIREMENTS_PATH + ": " + e.getMessage(), e);
    }
  }

  private MaterializationRequirements applyContractRequiredProperties(
      MaterializationRequirements overlay) {
    CompilerContract contract = contractRepository.require(CompilerContract.V1);
    Map<String, ElementRequirement> merged =
        new LinkedHashMap<>(overlay.elementRequirements());
    for (Map.Entry<String, ElementContract> entry : contract.elements().entrySet()) {
      ElementRequirement existing = merged.get(entry.getKey());
      if (existing == null) {
        merged.put(
            entry.getKey(),
            new ElementRequirement(null, entry.getValue().requiredProperties(), Map.of()));
      } else {
        merged.put(
            entry.getKey(),
            new ElementRequirement(
                existing.ownerGenerator(),
                entry.getValue().requiredProperties(),
                existing.examples()));
      }
    }
    return new MaterializationRequirements(overlay.version(), merged);
  }
}
