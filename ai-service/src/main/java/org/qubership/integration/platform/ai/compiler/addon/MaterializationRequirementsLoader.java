package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/** Loads {@link MaterializationRequirements} from the compiler skill addon pack. */
@ApplicationScoped
public class MaterializationRequirementsLoader {

  static final String MATERIALIZATION_REQUIREMENTS_PATH =
      "global/materialization-requirements.yaml";

  private final CompilerSkillAddonRepository addonRepository;
  private final ObjectMapper yamlMapper;

  @Inject
  public MaterializationRequirementsLoader(CompilerSkillAddonRepository addonRepository) {
    this.addonRepository = Objects.requireNonNull(addonRepository, "addonRepository");
    this.yamlMapper = new ObjectMapper(new YAMLFactory());
  }

  MaterializationRequirementsLoader(
      CompilerSkillAddonRepository addonRepository, ObjectMapper yamlMapper) {
    this.addonRepository = Objects.requireNonNull(addonRepository, "addonRepository");
    this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
  }

  public MaterializationRequirements load() {
    return addonRepository
        .readGlobalDataDocument(MATERIALIZATION_REQUIREMENTS_PATH)
        .map(this::parse)
        .orElse(MaterializationRequirements.empty());
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
}
